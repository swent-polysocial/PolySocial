// Contributors: Claude (Opus 5.5, Anthropic) drafted the account deletion flow, #207; Mohamed
// Khellaf (reviewed and revised).
package com.polysocial.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.account.AccountDeletionRepository
import com.polysocial.model.account.AccountDeletionResult
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.ReauthResult
import com.polysocial.model.auth.SignInMethod
import com.polysocial.model.auth.VerificationStore
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.SessionCache
import com.polysocial.model.network.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the Delete account sheet is (#207). */
enum class DeleteAccountStep {
  /** No sheet. */
  Hidden,
  /** The consequences and the destructive button. */
  Confirm,
  /** Password and/or Google, to confirm it's the account's owner. */
  Reauthenticate,
  Reauthenticating,
  /** The server deletes the profile, then the sign-in. */
  Deleting,
  /** The deletion failed; Retry calls it again (each server step is idempotent). */
  DeleteFailed,
  /** An association: an admin deletes it, through the privacy contact. */
  NotAllowed,
  /** Clearing this device's data and signing out. */
  Clearing,
  /** The account is gone but this device's data isn't cleared: Retry only, like logout's. */
  CleanupFailed,
  /** Signed out with a clean cache; the screen opens Welcome. */
  Done,
}

/** Why the last attempt didn't go through; shown on the current step. */
enum class DeleteAccountError {
  Offline,
  WrongPassword,
  /** Firebase blocks sign-in attempts for a while after too many wrong passwords. */
  TooManyAttempts,
  /** The Google picker was dismissed; nothing changed. */
  GoogleCancelled,
  /** The sign-in is too old for the server: confirm it's you again. */
  SignInAgain,
  Network,
  Unexpected,
}

/**
 * The Delete account sheet's state. [email] is the signed-in account's, shown on the
 * re-authentication step; [password] is kept in memory only, never saved. [deleted] tells whether
 * the server confirmed the deletion, so a lost session's cleanup never claims it.
 */
data class DeleteAccountUiState(
    val step: DeleteAccountStep = DeleteAccountStep.Hidden,
    val methods: Set<SignInMethod> = emptySet(),
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val error: DeleteAccountError? = null,
    val deleted: Boolean = false,
) {
  /** Something runs: every action is ignored and the sheet can't be dismissed. */
  val busy: Boolean
    get() =
        step == DeleteAccountStep.Reauthenticating ||
            step == DeleteAccountStep.Deleting ||
            step == DeleteAccountStep.Clearing ||
            step == DeleteAccountStep.Done

  /** Cancel, Back and a swipe close the sheet only here; nothing has changed yet, or it's safe. */
  val dismissible: Boolean
    get() =
        step == DeleteAccountStep.Confirm ||
            step == DeleteAccountStep.Reauthenticate ||
            step == DeleteAccountStep.DeleteFailed ||
            step == DeleteAccountStep.NotAllowed
}

/**
 * Deletes the signed-in student's account from Privacy & data (#207), following the policy agreed
 * in #201 (Architecture Diagram, decision 15):
 * 1. [request] opens the consequences; [cancel] closes the sheet without any call.
 * 2. [confirm] refuses offline (a deletion is never queued), then asks to confirm it's you with the
 *    account's sign-in methods ([submitPassword], [continueWithGoogle]).
 * 3. Once confirmed, [AccountDeletionRepository.deleteAccount] runs once; duplicate taps are
 *    ignored while it runs. A sign-in too old for the server returns to step 2; an association is
 *    refused ([DeleteAccountStep.NotAllowed]) and pointed to the privacy contact; other failures
 *    offer [retry], which is safe because the server steps are idempotent.
 * 4. On success: clear the Firestore cache first ([SessionCache.clear]), then the account's resend
 *    cooldowns ([VerificationStore.clear]), then sign out ([AuthRepository.logOut]), and report
 *    [DeleteAccountStep.Done] so the screen opens Welcome. A failed clear blocks with [retry], as
 *    logout does (#156), since the next account must not see this one's cache.
 *
 * A lost session ([ReauthResult.NotSignedIn], [AccountDeletionResult.NotSignedIn]; for example an
 * earlier attempt deleted the account but its answer was lost) runs the same cleanup and opens
 * Welcome, without claiming the account was deleted. Caller cancellation propagates.
 */
@HiltViewModel
class DeleteAccountViewModel
@Inject
constructor(
    private val auth: AuthRepository,
    private val deletion: AccountDeletionRepository,
    private val network: NetworkMonitor,
    private val cache: SessionCache,
    private val verification: VerificationStore,
) : ViewModel() {
  private val state = MutableStateFlow(DeleteAccountUiState())
  val uiState = state.asStateFlow()

  /**
   * The account being deleted, read when the flow starts: Firebase signs a deleted, disabled or
   * expired user out locally, so it can't be read after a failure.
   */
  private var uid: String? = null

  /** Opens the consequences; ignored while the sheet is already open. */
  fun request() {
    if (state.value.step == DeleteAccountStep.Hidden) {
      state.value = DeleteAccountUiState(step = DeleteAccountStep.Confirm)
    }
  }

  /** Closes the sheet and forgets the password; nothing is called. Ignored while it can't close. */
  fun cancel() {
    if (state.value.dismissible) state.value = DeleteAccountUiState()
  }

  /** "Delete account" on the consequences: refuses offline, then asks to confirm it's you. */
  fun confirm() {
    if (state.value.step != DeleteAccountStep.Confirm) return
    val user = auth.currentUser()
    uid = user?.uid
    if (user == null) {
      clearSession(deleted = false)
      return
    }
    if (!network.isOnline()) {
      state.update { it.copy(error = DeleteAccountError.Offline) }
      return
    }
    val methods = auth.currentSignInMethods()
    if (methods.isEmpty()) {
      state.update { it.copy(error = DeleteAccountError.Unexpected) }
      return
    }
    state.value =
        DeleteAccountUiState(
            step = DeleteAccountStep.Reauthenticate,
            methods = methods,
            email = user.email,
        )
  }

  fun onPasswordChange(password: String) {
    if (state.value.step == DeleteAccountStep.Reauthenticate) {
      state.update { it.copy(password = password, error = null) }
    }
  }

  fun togglePasswordVisibility() {
    if (state.value.step == DeleteAccountStep.Reauthenticate) {
      state.update { it.copy(passwordVisible = !it.passwordVisible) }
    }
  }

  /** Confirms the password, then deletes the account. */
  fun submitPassword() {
    val current = state.value
    if (SignInMethod.PASSWORD !in current.methods || current.password.isEmpty()) return
    val password = current.password
    reauthenticate { auth.reauthenticateWithPassword(password) }
  }

  /** Confirms with the Google picker on [context], then deletes the account. */
  fun continueWithGoogle(context: Context) {
    if (SignInMethod.GOOGLE !in state.value.methods) return
    reauthenticate { auth.reauthenticateWithGoogle(context) }
  }

  /** Tries the failed deletion or the failed local cleanup again; ignored otherwise. */
  fun retry() {
    when (state.value.step) {
      DeleteAccountStep.DeleteFailed ->
          if (!network.isOnline()) {
            state.update { it.copy(error = DeleteAccountError.Offline) }
          } else {
            state.value = state.value.copy(step = DeleteAccountStep.Deleting, error = null)
            viewModelScope.launch { delete() }
          }
      DeleteAccountStep.CleanupFailed -> clearSession(state.value.deleted)
      else -> Unit
    }
  }

  private fun reauthenticate(call: suspend () -> ReauthResult) {
    if (state.value.step != DeleteAccountStep.Reauthenticate) return
    if (!network.isOnline()) {
      state.update { it.copy(error = DeleteAccountError.Offline) }
      return
    }
    state.update { it.copy(step = DeleteAccountStep.Reauthenticating, error = null) }
    viewModelScope.launch {
      val result =
          try {
            call()
          } catch (cancelled: CancellationException) {
            backToReauth(DeleteAccountError.Unexpected)
            throw cancelled
          } catch (_: Exception) {
            ReauthResult.UnexpectedError
          }
      when (result) {
        ReauthResult.Success -> {
          state.update { it.copy(step = DeleteAccountStep.Deleting, password = "") }
          delete()
        }
        ReauthResult.WrongPassword -> backToReauth(DeleteAccountError.WrongPassword)
        ReauthResult.TooManyAttempts -> backToReauth(DeleteAccountError.TooManyAttempts)
        ReauthResult.Cancelled -> backToReauth(DeleteAccountError.GoogleCancelled)
        ReauthResult.NotSignedIn -> clearSession(deleted = false)
        ReauthResult.NetworkError -> backToReauth(DeleteAccountError.Network)
        ReauthResult.UnexpectedError -> backToReauth(DeleteAccountError.Unexpected)
      }
    }
  }

  private suspend fun delete() {
    val result =
        try {
          deletion.deleteAccount()
        } catch (cancelled: CancellationException) {
          failDeletion(DeleteAccountError.Unexpected)
          throw cancelled
        } catch (_: Exception) {
          AccountDeletionResult.UnexpectedError
        }
    when (result) {
      AccountDeletionResult.Deleted -> clearSession(deleted = true)
      AccountDeletionResult.NotSignedIn -> clearSession(deleted = false)
      AccountDeletionResult.RecentLoginRequired -> backToReauth(DeleteAccountError.SignInAgain)
      AccountDeletionResult.NotAllowed ->
          state.value = state.value.copy(step = DeleteAccountStep.NotAllowed, error = null)
      AccountDeletionResult.NetworkError -> failDeletion(DeleteAccountError.Network)
      AccountDeletionResult.UnexpectedError -> failDeletion(DeleteAccountError.Unexpected)
    }
  }

  /**
   * Clears the cache first, so nothing this session still writes can reach Firestore, then the
   * account's resend cooldowns, then signs out. Reports [DeleteAccountStep.Done] only after all
   * three; [deleted] is whether the server confirmed the deletion.
   */
  private fun clearSession(deleted: Boolean) {
    // Keeps the methods and email, so the re-authentication step stays on screen, faded.
    state.update {
      it.copy(step = DeleteAccountStep.Clearing, password = "", error = null, deleted = deleted)
    }
    viewModelScope.launch {
      try {
        if (cache.clear() != CacheClearResult.Cleared) {
          cleanupFailed(deleted)
          return@launch
        }
        uid?.let { verification.clear(it) }
        auth.logOut()
        state.update { it.copy(step = DeleteAccountStep.Done) }
      } catch (cancelled: CancellationException) {
        cleanupFailed(deleted)
        throw cancelled
      } catch (_: Exception) {
        cleanupFailed(deleted)
      }
    }
  }

  private fun backToReauth(error: DeleteAccountError?) {
    state.update { it.copy(step = DeleteAccountStep.Reauthenticate, password = "", error = error) }
  }

  private fun failDeletion(error: DeleteAccountError) {
    state.update { it.copy(step = DeleteAccountStep.DeleteFailed, error = error) }
  }

  private fun cleanupFailed(deleted: Boolean) {
    state.update { it.copy(step = DeleteAccountStep.CleanupFailed, deleted = deleted) }
  }
}
