// Contributors: OpenAI Codex (verification, restart, cooldown and failure regression tests for
// #31).
package com.polysocial.auth

import androidx.lifecycle.ViewModelStore
import com.polysocial.model.auth.*
import com.polysocial.ui.auth.*
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MemoryVerificationStore : VerificationStore {
  val timings = mutableMapOf<String, VerificationTiming>()
  var failure: Exception? = null

  override suspend fun read(uid: String): VerificationTiming {
    failure?.let { throw it }
    return timings[uid] ?: VerificationTiming()
  }

  override suspend fun write(uid: String, timing: VerificationTiming) {
    failure?.let { throw it }
    timings[uid] = timing
  }

  override suspend fun clear(uid: String) {
    timings.remove(uid)
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VerifyEmailViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val user = AuthUser("test-uid", "student.test@epfl.ch", false)
  private val repository = FakeAuthRepository(user)
  private val store = MemoryVerificationStore()
  private var now = 1_000_000L

  private fun viewModel() = VerifyEmailViewModel(repository, store, VerificationClock { now })

  private fun dispose(vm: VerifyEmailViewModel) =
      ViewModelStore().apply {
        put("verification", vm)
        clear()
      }

  @Test
  fun startupRoutesOnlyUnverifiedSessionsAndOnlyOnce() {
    val vm = viewModel()
    assertTrue(vm.startsWithVerification)
    assertTrue(vm.takeStartupVerification())
    assertFalse(vm.takeStartupVerification())
    repository.user = null
    assertFalse(viewModel().startsWithVerification)
    repository.user = user.copy(isEmailVerified = true)
    assertFalse(viewModel().startsWithVerification)
  }

  @Test
  fun entrySendsFirstEmailAndBlocksDuplicateClicks() = runTest {
    val vm = viewModel()
    repository.sendGate = CompletableDeferred()
    vm.enter()
    assertTrue(vm.uiState.value.preparing)
    vm.enter()
    runCurrent()
    assertTrue(vm.uiState.value.sending)
    vm.resend()
    assertEquals(1, repository.sendCalls)
    repository.sendGate!!.complete(Unit)
    runCurrent()
    assertEquals(user.email, vm.uiState.value.email)
    assertEquals(45L, vm.uiState.value.remainingSeconds)
    assertEquals(VerificationMessage.Sent, vm.uiState.value.snackbar)
    assertFalse(vm.uiState.value.canResend)
    assertEquals(VerificationTiming(now, now + 45_000), store.timings[user.uid])
    vm.snackbarShown()
    assertNull(vm.uiState.value.snackbar)
    dispose(vm)
  }

  @Test
  fun processRestartUsesRemainingDeadlineWithoutSendingAgain() = runTest {
    store.timings[user.uid] = VerificationTiming(now - 15_000, now + 30_000)
    val vm = viewModel()
    vm.enter()
    runCurrent()
    assertEquals(30L, vm.uiState.value.remainingSeconds)
    assertEquals(VerificationMessage.AlreadySent, vm.uiState.value.snackbar)
    assertEquals(0, repository.sendCalls)
    now += 29_001
    advanceTimeBy(1000)
    runCurrent()
    assertEquals(1L, vm.uiState.value.remainingSeconds)
    now += 999
    advanceTimeBy(1000)
    runCurrent()
    assertTrue(vm.uiState.value.canResend)
    vm.resend()
    runCurrent()
    assertEquals(1, repository.sendCalls)
    dispose(vm)
  }

  @Test
  fun recreationKeepsConsumedSnackbarWhileExplicitReentryAndRestartNotify() = runTest {
    store.timings[user.uid] = VerificationTiming(now - 15_000, now + 30_000)
    val vm = viewModel()
    vm.enter()
    runCurrent()
    assertEquals(VerificationMessage.AlreadySent, vm.uiState.value.snackbar)
    vm.snackbarShown()
    vm.enter()
    runCurrent()
    assertNull(vm.uiState.value.snackbar)
    assertEquals(30L, vm.uiState.value.remainingSeconds)
    vm.leave()
    vm.enter()
    runCurrent()
    assertEquals(VerificationMessage.AlreadySent, vm.uiState.value.snackbar)
    val restarted = viewModel()
    restarted.enter()
    runCurrent()
    assertEquals(VerificationMessage.AlreadySent, restarted.uiState.value.snackbar)
    assertEquals(0, repository.sendCalls)
    dispose(vm)
    dispose(restarted)
  }

  @Test
  fun expiredCooldownDoesNotAutomaticallySendOnRestart() = runTest {
    store.timings[user.uid] = VerificationTiming(now - 90_000, now - 45_000)
    val vm = viewModel()
    vm.enter()
    runCurrent()
    assertTrue(vm.uiState.value.canResend)
    assertEquals(0, repository.sendCalls)
    dispose(vm)
  }

  @Test
  fun cooldownIsSeparateForEachAccount() = runTest {
    store.timings["another-uid"] = VerificationTiming(now, now + 45_000)
    val vm = viewModel()
    vm.enter()
    runCurrent()
    assertEquals(1, repository.sendCalls)
    assertEquals(45L, vm.uiState.value.remainingSeconds)
    dispose(vm)
  }

  @Test
  fun throttlingHasDistinctMessageAndSurvivesRestart() = runTest {
    repository.sendResult = SendVerificationResult.Throttled
    val first = viewModel()
    first.enter()
    runCurrent()
    assertEquals(VerificationMessage.Throttled, first.uiState.value.banner)
    assertEquals(180L, first.uiState.value.remainingSeconds)
    assertEquals(0L, store.timings[user.uid]!!.sentAtMillis)
    dispose(first)
    now += 20_000
    val restarted = viewModel()
    restarted.enter()
    runCurrent()
    assertEquals(160L, restarted.uiState.value.remainingSeconds)
    assertEquals(1, repository.sendCalls)
    assertNull(restarted.uiState.value.snackbar)
    dispose(restarted)
  }

  @Test
  fun sendFailuresRemainRetryableWithoutClaimingEmailSent() = runTest {
    for ((result, message) in
        listOf(
            SendVerificationResult.NetworkError to VerificationMessage.NetworkError,
            SendVerificationResult.NotSignedIn to VerificationMessage.NotSignedIn,
            SendVerificationResult.UnexpectedError to VerificationMessage.UnexpectedError,
        )) {
      repository.sendResult = result
      val vm = viewModel()
      vm.enter()
      runCurrent()
      assertEquals(message, vm.uiState.value.banner)
      assertFalse(vm.uiState.value.busy)
      assertTrue(vm.uiState.value.canResend)
      assertNull(vm.uiState.value.snackbar)
      assertNull(store.timings[user.uid])
      dispose(vm)
    }
  }

  @Test
  fun manualCheckReportsUnverifiedWhileResumeCheckIsQuiet() = runTest {
    val vm = viewModel()
    repository.checkGate = CompletableDeferred()
    vm.checkVerified(manual = false)
    assertTrue(vm.uiState.value.checking)
    vm.checkVerified()
    runCurrent()
    assertEquals(1, repository.checkCalls)
    repository.checkGate!!.complete(Unit)
    runCurrent()
    assertNull(vm.uiState.value.banner)
    vm.checkVerified()
    runCurrent()
    assertEquals(VerificationMessage.StillUnverified, vm.uiState.value.banner)
    assertFalse(vm.uiState.value.verified)
    dispose(vm)
  }

  @Test
  fun successOnResumeClearsTimingAndDoesNotRequireLogin() = runTest {
    store.timings[user.uid] = VerificationTiming(now, now + 45_000)
    val vm = viewModel()
    vm.enter()
    runCurrent()
    repository.verificationResult = VerificationResult.Verified
    vm.checkVerified(manual = false)
    runCurrent()
    assertTrue(vm.uiState.value.verified)
    assertFalse(vm.uiState.value.checking)
    assertNull(store.timings[user.uid])
    assertEquals(0, repository.logInCalls)
    vm.checkVerified()
    assertEquals(1, repository.checkCalls)
    dispose(vm)
  }

  @Test
  fun checkErrorsDoNotGrantAccess() = runTest {
    for ((result, message) in
        listOf(
            VerificationResult.NetworkError to VerificationMessage.NetworkError,
            VerificationResult.NotSignedIn to VerificationMessage.NotSignedIn,
            VerificationResult.UnexpectedError to VerificationMessage.UnexpectedError,
        )) {
      repository.verificationResult = result
      val vm = viewModel()
      vm.checkVerified()
      runCurrent()
      assertEquals(message, vm.uiState.value.banner)
      assertFalse(vm.uiState.value.verified)
      assertFalse(vm.uiState.value.checking)
      dispose(vm)
    }
  }

  @Test
  fun changedSessionCannotNavigateFromOldVerificationResult() = runTest {
    val vm = viewModel()
    repository.checkGate = CompletableDeferred()
    repository.verificationResult = VerificationResult.Verified
    vm.checkVerified()
    runCurrent()
    repository.user = user.copy(uid = "another-uid")
    repository.checkGate!!.complete(Unit)
    runCurrent()
    assertFalse(vm.uiState.value.verified)
    dispose(vm)
  }

  @Test
  fun signedOutEntryAndManualCheckShowLoginMessageWithoutBackendCalls() = runTest {
    repository.user = null
    val vm = viewModel()
    vm.enter()
    vm.checkVerified()
    runCurrent()
    assertEquals(VerificationMessage.NotSignedIn, vm.uiState.value.banner)
    assertEquals(0, repository.sendCalls)
    assertEquals(0, repository.checkCalls)
    vm.resend()
    assertEquals(VerificationMessage.NotSignedIn, vm.uiState.value.banner)
    dispose(vm)
  }

  @Test
  fun storageFailureReportsErrorAndAllowsRetry() = runTest {
    store.failure = IllegalStateException("test failure")
    val vm = viewModel()
    vm.enter()
    runCurrent()
    assertEquals(VerificationMessage.UnexpectedError, vm.uiState.value.banner)
    assertFalse(vm.uiState.value.preparing)
    assertEquals(0, repository.sendCalls)
    store.failure = null
    vm.enter()
    runCurrent()
    assertEquals(1, repository.sendCalls)
    dispose(vm)
  }

  @Test
  fun cancelledSendResetsLoadingAndPropagatesCancellation() = runTest {
    repository.sendGate = CompletableDeferred<Unit>().apply { cancel() }
    val vm = viewModel()
    vm.enter()
    runCurrent()
    assertFalse(vm.uiState.value.sending)
    assertNull(store.timings[user.uid])
    dispose(vm)
  }

  @Test
  fun resumeDuringInitialSendWaitsForPersistenceBeforeClearingTiming() = runTest {
    repository.sendGate = CompletableDeferred()
    val vm = viewModel()
    vm.enter()
    runCurrent()
    repository.verificationResult = VerificationResult.Verified
    vm.checkVerified(manual = false)
    runCurrent()
    assertEquals(0, repository.checkCalls)
    assertTrue(vm.uiState.value.checking)
    repository.sendGate!!.complete(Unit)
    runCurrent()
    assertTrue(vm.uiState.value.verified)
    assertNull(store.timings[user.uid])
    dispose(vm)
  }

  @Test
  fun initialResumeAndScreenEntryDoNotLoseTheFirstEmail() = runTest {
    repository.checkGate = CompletableDeferred()
    val vm = viewModel()
    vm.checkVerified(manual = false)
    vm.enter()
    runCurrent()
    assertFalse(vm.uiState.value.preparing)
    assertEquals(user.email, vm.uiState.value.email)
    assertEquals(0, repository.sendCalls)
    repository.checkGate!!.complete(Unit)
    runCurrent()
    assertEquals(1, repository.sendCalls)
    assertTrue(vm.uiState.value.hasSentEmail)
    dispose(vm)
  }

  @Test
  fun changingAddressCancelsPendingCheckAndPreservesTheOriginalAccountsTiming() = runTest {
    store.timings[user.uid] = VerificationTiming(now, now + 45_000)
    val vm = viewModel()
    vm.enter()
    runCurrent()
    repository.checkGate = CompletableDeferred()
    repository.verificationResult = VerificationResult.Verified
    vm.checkVerified()
    runCurrent()
    vm.changeAddress()
    runCurrent()
    assertEquals(1, repository.logOutCalls)
    assertNull(repository.currentUser())
    assertEquals(VerifyEmailUiState(), vm.uiState.value)
    assertEquals(VerificationTiming(now, now + 45_000), store.timings[user.uid])
    dispose(vm)
  }

  @Test
  fun alreadyVerifiedSessionDoesNotGetReroutedOnOrdinaryResume() = runTest {
    repository.user = user.copy(isEmailVerified = true)
    val vm = viewModel()
    vm.checkVerified(manual = false)
    runCurrent()
    assertEquals(0, repository.checkCalls)
    assertFalse(vm.uiState.value.verified)
    dispose(vm)
  }

  @Test
  fun cancelledEntryAndCheckClearLoadingWithoutReportingFailure() = runTest {
    store.failure = kotlinx.coroutines.CancellationException("test cancellation")
    val vm = viewModel()
    vm.enter()
    runCurrent()
    assertFalse(vm.uiState.value.preparing)
    assertNull(vm.uiState.value.banner)
    repository.checkGate = CompletableDeferred<Unit>().apply { cancel() }
    vm.checkVerified()
    runCurrent()
    assertFalse(vm.uiState.value.checking)
    assertFalse(vm.uiState.value.verified)
    assertNull(vm.uiState.value.banner)
    dispose(vm)
  }

  @Test
  fun persistedClockDeadlineIsBoundedAfterClockMovesBackwards() = runTest {
    store.timings[user.uid] = VerificationTiming(now, now + 1_000_000)
    val vm = viewModel()
    vm.enter()
    runCurrent()
    assertEquals(180L, vm.uiState.value.remainingSeconds)
    assertFalse(vm.uiState.value.canResend)
    dispose(vm)
  }
}
