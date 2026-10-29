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
  /** The sign-in is too old for the server: confirm it's you again. */
  SignInAgain,
  Network,
  Unexpected,
}

/** The Delete account sheet's state; [password] is kept in memory only, never saved. */
data class DeleteAccountUiState(
    val step: DeleteAccountStep = DeleteAccountStep.Hidden,
    val methods: Set<SignInMethod> = emptySet(),
    val password: String = "",
    val error: DeleteAccountError? = null,
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
 * 4. On success: clear the Firestore cache ([SessionCache.clear]), the account's resend cooldowns
 *    ([VerificationStore.clear]), then sign out ([AuthRepository.logOut]), and report
 *    [DeleteAccountStep.Done] so the screen opens Welcome. A failed clear blocks with [retry], as
 *    logout does (#156), since the next account must not see this one's cache.
 *
 * A lost session ([ReauthResult.NotSignedIn], [AccountDeletionResult.NotSignedIn]) takes logout's
 * path: clear the cache, sign out, Welcome. Caller cancellation propagates.
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

  /** The account being deleted, read before anything signs it out. */
  private var uid: String? = null

  /** Whether the pending cleanup follows a deletion (also clears the resend cooldowns). */
  private var deleted = false

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
    if (user == null) {
      clearSession(afterDeletion = false)
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
    uid = user.uid
    state.value = DeleteAccountUiState(step = DeleteAccountStep.Reauthenticate, methods = methods)
  }

  fun onPasswordChange(password: String) {
    if (state.value.step == DeleteAccountStep.Reauthenticate) {
      state.update { it.copy(password = password, error = null) }
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
      DeleteAccountStep.CleanupFailed -> clearSession(deleted)
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
        ReauthResult.Cancelled -> backToReauth(null)
        ReauthResult.NotSignedIn -> clearSession(afterDeletion = false)
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
      AccountDeletionResult.Deleted -> clearSession(afterDeletion = true)
      AccountDeletionResult.NotSignedIn -> clearSession(afterDeletion = false)
      AccountDeletionResult.RecentLoginRequired -> backToReauth(DeleteAccountError.SignInAgain)
      AccountDeletionResult.NotAllowed ->
          state.value = state.value.copy(step = DeleteAccountStep.NotAllowed, error = null)
      AccountDeletionResult.NetworkError -> failDeletion(DeleteAccountError.Network)
      AccountDeletionResult.UnexpectedError -> failDeletion(DeleteAccountError.Unexpected)
    }
  }

  /**
   * Clears this device's data, then signs out; reports [DeleteAccountStep.Done] only after both.
   */
  private fun clearSession(afterDeletion: Boolean) {
    deleted = afterDeletion
    state.value = DeleteAccountUiState(step = DeleteAccountStep.Clearing)
    viewModelScope.launch {
      try {
        if (cache.clear() != CacheClearResult.Cleared) {
          cleanupFailed()
          return@launch
        }
        val account = uid
        if (afterDeletion && account != null) verification.clear(account)
        auth.logOut()
        state.value = DeleteAccountUiState(step = DeleteAccountStep.Done)
      } catch (cancelled: CancellationException) {
        cleanupFailed()
        throw cancelled
      } catch (_: Exception) {
        cleanupFailed()
      }
    }
  }

  private fun backToReauth(error: DeleteAccountError?) {
    state.update { it.copy(step = DeleteAccountStep.Reauthenticate, password = "", error = error) }
  }

  private fun failDeletion(error: DeleteAccountError) {
    state.update { it.copy(step = DeleteAccountStep.DeleteFailed, error = error) }
  }

  private fun cleanupFailed() {
    state.value = DeleteAccountUiState(step = DeleteAccountStep.CleanupFailed)
  }
}
