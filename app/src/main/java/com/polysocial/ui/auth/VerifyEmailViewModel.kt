// Contributors: OpenAI Codex (verification state, startup routing, persistent cooldown and Back
// sign-out for #31; documented lifecycle ordering, removed unused leave() and modeled sealed
// entry; reset retained verification state on logout, #109).
package com.polysocial.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.SendVerificationResult
import com.polysocial.model.auth.VerificationClock
import com.polysocial.model.auth.VerificationResult
import com.polysocial.model.auth.VerificationStore
import com.polysocial.model.auth.VerificationTiming
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Distinct messages for the banner and the one-shot snackbar. */
enum class VerificationMessage {
  Sent,
  AlreadySent,
  Throttled,
  StillUnverified,
  NetworkError,
  UnexpectedError,
  NotSignedIn,
}

data class VerifyEmailUiState(
    val email: String = "",
    val hasSentEmail: Boolean = false,
    val preparing: Boolean = false,
    val sending: Boolean = false,
    val checking: Boolean = false,
    val verified: Boolean = false,
    val remainingSeconds: Long = 0,
    val banner: VerificationMessage? = null,
    val snackbar: VerificationMessage? = null,
) {
  val busy: Boolean
    get() = preparing || sending || checking

  val canResend: Boolean
    get() = !busy && !verified && remainingSeconds == 0L
}

/**
 * Owns verification and resend timing. Back and change-address actions cancel pending work and sign
 * out; browser return checks verification while the account remains signed in.
 */
@HiltViewModel
class VerifyEmailViewModel
@Inject
constructor(
    private val repository: AuthRepository,
    private val store: VerificationStore,
    private val clock: VerificationClock,
) : ViewModel() {
  val startsWithVerification = repository.currentUser()?.isEmailVerified == false
  private var startupRoutingPending = startsWithVerification

  /**
   * Apply cold-start routing once per ViewModel, while keeping an explicit Back across rotation.
   */
  fun takeStartupVerification(): Boolean = startupRoutingPending.also {
    startupRoutingPending = false
  }

  private val mutableState = MutableStateFlow(VerifyEmailUiState())
  val uiState = mutableState.asStateFlow()
  // Timing also belongs to manual sends before entry, so it is independent of entry readiness.
  private var timing = VerificationTiming()
  private var accountUid: String? = null
  private var ticker: Job? = null

  /** Entry completion and the initial delivery allowance share one lifecycle. */
  private sealed interface EntryState {
    data object NotEntered : EntryState

    data class Preparing(val uid: String) : EntryState

    data class Ready(val initialDelivery: InitialDelivery = InitialDelivery.Pending) : EntryState
  }

  private enum class InitialDelivery {
    Pending,
    Attempted,
  }

  private var entryState: EntryState = EntryState.NotEntered
  private var entryJob: Job? = null
  private var sendJob: Job? = null
  private var checkJob: Job? = null

  /** Signs out and resets verification for Back or the explicit change-address action. */
  fun changeAddress() {
    reset()
    repository.logOut()
  }

  /** Cancels account work and clears screen state without signing out or deleting cooldowns. */
  fun reset() {
    entryJob?.cancel()
    sendJob?.cancel()
    checkJob?.cancel()
    ticker?.cancel()
    startupRoutingPending = false
    accountUid = null
    entryState = EntryState.NotEntered
    timing = VerificationTiming()
    mutableState.value = VerifyEmailUiState()
  }

  /**
   * Prepares the active account once, restoring resend timing before initial delivery.
   *
   * Entry moves from NotEntered to Preparing, then Ready after the timing read. Ready makes
   * recreation idempotent, including snackbar consumption, and owns whether initial delivery has
   * been attempted. A failed/cancelled read returns to NotEntered so entry can retry; a failed
   * delivery stays Ready with its allowance consumed, so further sends require Resend.
   *
   * ON_RESUME may check verification before entry or during preparation. The check delays initial
   * delivery until it finishes; entry preserves any verification success that arrived first.
   * Back/change address resets entry; an account replacement cancels the previous account's work.
   */
  fun enter() {
    val user = repository.currentUser()
    if (user == null) {
      mutableState.update { it.copy(banner = VerificationMessage.NotSignedIn) }
      return
    }
    if (entryState is EntryState.Ready && accountUid == user.uid) return
    if (accountUid != null && accountUid != user.uid) {
      entryJob?.cancel()
      sendJob?.cancel()
      checkJob?.cancel()
      ticker?.cancel()
      mutableState.value = VerifyEmailUiState()
    }
    if (mutableState.value.preparing || mutableState.value.sending || mutableState.value.verified)
        return
    val preparation = EntryState.Preparing(user.uid)
    entryState = preparation
    accountUid = user.uid
    mutableState.update { it.copy(email = user.email, preparing = true, verified = false) }
    entryJob = viewModelScope.launch {
      try {
        timing = store.read(user.uid)
        if (repository.currentUser()?.uid != preparation.uid) {
          if (entryState === preparation) entryState = EntryState.NotEntered
          mutableState.update { it.copy(preparing = false) }
          return@launch
        }
        entryState = EntryState.Ready()
        updateRemaining()
        mutableState.update {
          it.copy(
              preparing = false,
              hasSentEmail = timing.sentAtMillis > 0,
              banner =
                  if (
                      timing.retryAtMillis > timing.sentAtMillis + RESEND_DELAY_MILLIS &&
                          timing.retryAtMillis > clock.nowMillis()
                  )
                      VerificationMessage.Throttled
                  else null,
              snackbar = if (timing.sentAtMillis > 0) VerificationMessage.AlreadySent else null,
          )
        }
        startTicker()
        maybeSendInitialEmail()
      } catch (cancelled: CancellationException) {
        // A cancelled old entry must not reset a replacement account's entry.
        if (entryState === preparation) entryState = EntryState.NotEntered
        mutableState.update { it.copy(preparing = false) }
        throw cancelled
      } catch (_: Exception) {
        if (entryState === preparation) entryState = EntryState.NotEntered
        mutableState.update {
          it.copy(preparing = false, banner = VerificationMessage.UnexpectedError)
        }
      }
    }
  }

  fun resend() {
    if (!mutableState.value.canResend) return
    val user =
        repository.currentUser()
            ?: run {
              mutableState.update { it.copy(banner = VerificationMessage.NotSignedIn) }
              return
            }
    accountUid = user.uid
    mutableState.update { it.copy(sending = true, banner = null, snackbar = null) }
    sendJob = viewModelScope.launch {
      try {
        val result = repository.sendVerificationEmail()
        if (repository.currentUser()?.uid != user.uid) return@launch
        when (result) {
          SendVerificationResult.Sent -> {
            val now = clock.nowMillis()
            timing = VerificationTiming(now, now + RESEND_DELAY_MILLIS)
            // Keep the in-memory deadline even if a local disk write fails.
            updateRemaining()
            store.write(user.uid, timing)
            mutableState.update {
              it.copy(
                  hasSentEmail = true,
                  banner = VerificationMessage.Sent,
                  snackbar = VerificationMessage.Sent,
              )
            }
          }
          SendVerificationResult.Throttled -> {
            timing = timing.copy(retryAtMillis = clock.nowMillis() + THROTTLE_DELAY_MILLIS)
            updateRemaining()
            store.write(user.uid, timing)
            mutableState.update { it.copy(banner = VerificationMessage.Throttled) }
          }
          SendVerificationResult.NetworkError ->
              mutableState.update { it.copy(banner = VerificationMessage.NetworkError) }
          SendVerificationResult.NotSignedIn ->
              mutableState.update { it.copy(banner = VerificationMessage.NotSignedIn) }
          SendVerificationResult.UnexpectedError ->
              mutableState.update { it.copy(banner = VerificationMessage.UnexpectedError) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(banner = VerificationMessage.UnexpectedError) }
      } finally {
        mutableState.update { it.copy(sending = false) }
        startTicker()
      }
    }
  }

  /** Manual checks show an unverified banner; lifecycle checks leave the current message alone. */
  fun checkVerified(manual: Boolean = true) = check(manual)

  /**
   * Serializes verification with entry and delivery, including a resume before screen entry.
   *
   * An already-verified session that never entered verification needs no reload. Once this flow
   * reports success, later resumes also return immediately, including while the app shell is shown.
   * An active check suppresses duplicate requests. Joining [entryJob] and [sendJob] lets their
   * timing writes finish before success clears the account's stored deadline; UID checks discard
   * results for a session replaced during either await. After an unverified check,
   * [maybeSendInitialEmail] can deliver the first email if entry has become ready. Only manual
   * checks show StillUnverified.
   */
  private fun check(manual: Boolean) {
    if (mutableState.value.checking || mutableState.value.verified) return
    val user =
        repository.currentUser()
            ?: run {
              if (manual) mutableState.update { it.copy(banner = VerificationMessage.NotSignedIn) }
              return
            }
    if (!manual && user.isEmailVerified && accountUid == null && !startsWithVerification) return
    accountUid = user.uid
    mutableState.update { it.copy(checking = true) }
    checkJob = viewModelScope.launch {
      try {
        // A resume may arrive while the initial email is being persisted. Finish that write
        // before clearing its timing on successful verification.
        entryJob?.join()
        sendJob?.join()
        if (repository.currentUser()?.uid != user.uid) return@launch
        val result = repository.reloadAndCheckVerified()
        if (repository.currentUser()?.uid != user.uid) return@launch
        when (result) {
          VerificationResult.Verified -> {
            ticker?.cancel()
            mutableState.update { it.copy(verified = true, banner = null, snackbar = null) }
            store.clear(user.uid)
          }
          VerificationResult.Unverified ->
              if (manual)
                  mutableState.update { it.copy(banner = VerificationMessage.StillUnverified) }
          VerificationResult.NotSignedIn ->
              mutableState.update { it.copy(banner = VerificationMessage.NotSignedIn) }
          VerificationResult.NetworkError ->
              mutableState.update { it.copy(banner = VerificationMessage.NetworkError) }
          VerificationResult.UnexpectedError ->
              mutableState.update { it.copy(banner = VerificationMessage.UnexpectedError) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (!mutableState.value.verified)
            mutableState.update { it.copy(banner = VerificationMessage.UnexpectedError) }
      } finally {
        mutableState.update { it.copy(checking = false) }
        maybeSendInitialEmail()
      }
    }
  }

  private fun maybeSendInitialEmail() {
    val ready = entryState as? EntryState.Ready ?: return
    if (
        ready.initialDelivery == InitialDelivery.Pending &&
            timing.sentAtMillis == 0L &&
            mutableState.value.canResend
    ) {
      entryState = ready.copy(initialDelivery = InitialDelivery.Attempted)
      resend()
    }
  }

  fun snackbarShown() {
    mutableState.update { it.copy(snackbar = null) }
  }

  private fun updateRemaining() {
    val remaining = (timing.retryAtMillis - clock.nowMillis()).coerceIn(0, THROTTLE_DELAY_MILLIS)
    mutableState.update { it.copy(remainingSeconds = (remaining + 999) / 1000) }
  }

  private fun startTicker() {
    ticker?.cancel()
    ticker = viewModelScope.launch {
      updateRemaining()
      while (mutableState.value.remainingSeconds > 0) {
        delay(1000)
        updateRemaining()
      }
    }
  }

  companion object {
    const val RESEND_DELAY_MILLIS = 45_000L
    const val THROTTLE_DELAY_MILLIS = 180_000L
  }
}
