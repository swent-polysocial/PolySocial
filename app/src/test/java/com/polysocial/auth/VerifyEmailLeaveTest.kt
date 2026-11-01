// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests for leaving Verify Email through the
// account deletion and for the address-keyed cooldown, #225; Mohamed Khellaf (reviewed and
// revised).
package com.polysocial.auth

import androidx.lifecycle.ViewModelStore
import com.polysocial.model.account.AccountDeletionRepository
import com.polysocial.model.account.AccountDeletionResult
import com.polysocial.model.account.FakeAccountDeletionRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.SendVerificationResult
import com.polysocial.model.auth.VerificationClock
import com.polysocial.model.auth.VerificationResult
import com.polysocial.model.auth.VerificationTiming
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.ui.auth.VerificationExit
import com.polysocial.ui.auth.VerificationLeave
import com.polysocial.ui.auth.VerificationLeaveError
import com.polysocial.ui.auth.VerificationMessage
import com.polysocial.ui.auth.VerifyEmailUiState
import com.polysocial.ui.auth.VerifyEmailViewModel
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Back and "Change address" delete the unverified account before signing out (#225), and the resend
 * cooldown follows the address across accounts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifyEmailLeaveTest {
  @get:Rule val main = MainDispatcherRule()
  private val user = AuthUser("test-uid", "student.test@epfl.ch", false)
  private val repository = FakeAuthRepository(user)
  private val store = MemoryVerificationStore()
  private val deletion = FakeAccountDeletionRepository()
  private val network = FakeNetworkMonitor()
  private var now = 1_000_000L

  private fun viewModel(
      deletion: AccountDeletionRepository = this.deletion,
      network: NetworkMonitor = this.network,
  ) = VerifyEmailViewModel(repository, store, VerificationClock { now }, deletion, network)

  private fun dispose(vm: VerifyEmailViewModel) =
      ViewModelStore().apply {
        put("verification", vm)
        clear()
      }

  /** A view model that has entered Verify Email for [user]. */
  private fun TestScope.entered(
      deletion: AccountDeletionRepository = this@VerifyEmailLeaveTest.deletion,
      network: NetworkMonitor = this@VerifyEmailLeaveTest.network,
  ): VerifyEmailViewModel =
      viewModel(deletion, network).also {
        it.enter()
        runCurrent()
      }

  @Test
  fun changeAddressDeletesTheAccountThenSignsOutAndKeepsOnlyTheAddressCooldown() = runTest {
    store.timings[user.uid] = VerificationTiming(now, now + 45_000)
    store.addressTimings[user.email] = VerificationTiming(now, now + 45_000)
    val vm = entered()
    vm.leave(VerificationLeave.ChangeAddress)
    runCurrent()
    assertEquals(1, network.checks)
    assertEquals(1, deletion.calls)
    assertEquals(1, repository.logOutCalls)
    assertNull(repository.currentUser())
    assertEquals(VerifyEmailUiState(exit = VerificationExit.SignUp(user.email)), vm.uiState.value)
    assertNull(store.timings[user.uid])
    assertEquals(VerificationTiming(now, now + 45_000), store.addressTimings[user.email])
    dispose(vm)
  }

  @Test
  fun backDeletesTheAccountThenExitsToWelcomeOnce() = runTest {
    val vm = entered()
    vm.leave(VerificationLeave.Back)
    runCurrent()
    assertEquals(1, deletion.calls)
    assertEquals(VerifyEmailUiState(exit = VerificationExit.Welcome), vm.uiState.value)
    assertNull(repository.currentUser())
    // The exit is one-shot: the flow's reset consumes it.
    vm.reset()
    assertEquals(VerifyEmailUiState(), vm.uiState.value)
    dispose(vm)
  }

  @Test
  fun offlineLeaveIsRefusedBeforeAnyCallAndKeepsTheAccount() = runTest {
    network.online = false
    store.timings[user.uid] = VerificationTiming(now, now + 45_000)
    val vm = entered()
    vm.leave(VerificationLeave.ChangeAddress)
    runCurrent()
    assertEquals(0, deletion.calls)
    assertEquals(0, repository.logOutCalls)
    assertEquals(user, repository.currentUser())
    val state = vm.uiState.value
    assertEquals(
        VerificationLeaveError(VerificationLeave.ChangeAddress, VerificationMessage.NetworkError),
        state.leaveError,
    )
    assertFalse(state.leaving)
    assertNull(state.exit)
    assertEquals(user.email, state.email)
    assertEquals(VerificationTiming(now, now + 45_000), store.timings[user.uid])

    // Retry once online goes through with the same way out.
    network.online = true
    vm.retryLeave()
    runCurrent()
    assertEquals(1, deletion.calls)
    assertEquals(VerificationExit.SignUp(user.email), vm.uiState.value.exit)
    dispose(vm)
  }

  @Test
  fun fallbackAnswersOnlySignOutAndKeepTheAccountsCooldown() = runTest {
    for (answer in
        listOf(
            AccountDeletionResult.RecentLoginRequired,
            AccountDeletionResult.NotAllowed,
            AccountDeletionResult.NotSignedIn,
        )) {
      repository.user = user
      store.timings[user.uid] = VerificationTiming(now, now + 45_000)
      deletion.result = answer
      val vm = entered()
      vm.leave(VerificationLeave.Back)
      runCurrent()
      val name = answer.toString()
      assertEquals(name, VerificationExit.Welcome, vm.uiState.value.exit)
      assertNull(name, vm.uiState.value.leaveError)
      assertNull(name, repository.currentUser())
      assertEquals(name, VerificationTiming(now, now + 45_000), store.timings[user.uid])
      dispose(vm)
    }
    assertEquals(3, deletion.calls)
    assertEquals(3, repository.logOutCalls)
  }

  @Test
  fun networkFailureKeepsTheUserSignedInAndRetryRepeatsTheSameWayOut() = runTest {
    deletion.result = AccountDeletionResult.NetworkError
    val vm = entered()
    vm.leave(VerificationLeave.Back)
    runCurrent()
    assertEquals(
        VerificationLeaveError(VerificationLeave.Back, VerificationMessage.DeleteNetworkError),
        vm.uiState.value.leaveError,
    )
    assertNull(vm.uiState.value.exit)
    assertFalse(vm.uiState.value.busy)
    assertEquals(0, repository.logOutCalls)
    assertEquals(user, repository.currentUser())

    deletion.result = AccountDeletionResult.Deleted
    vm.retryLeave()
    runCurrent()
    assertEquals(2, deletion.calls)
    assertEquals(VerificationExit.Welcome, vm.uiState.value.exit)
    assertEquals(1, repository.logOutCalls)
    dispose(vm)
  }

  @Test
  fun unexpectedAnswerKeepsTheUserOnVerifyEmail() = runTest {
    deletion.result = AccountDeletionResult.UnexpectedError
    val vm = entered()
    vm.leave(VerificationLeave.ChangeAddress)
    runCurrent()
    assertEquals(
        VerificationLeaveError(VerificationLeave.ChangeAddress, VerificationMessage.DeleteError),
        vm.uiState.value.leaveError,
    )
    assertEquals(0, repository.logOutCalls)
    dispose(vm)
  }

  @Test
  fun thrownFailuresKeepTheUserOnVerifyEmail() = runTest {
    val throwingDeletion =
        object : AccountDeletionRepository {
          override suspend fun deleteAccount(): AccountDeletionResult =
              throw IllegalStateException("test deletion failure")
        }
    val throwingNetwork =
        object : NetworkMonitor {
          override fun isOnline(): Boolean = throw IllegalStateException("test check failure")
        }
    for (vm in listOf(entered(deletion = throwingDeletion), entered(network = throwingNetwork))) {
      vm.leave(VerificationLeave.Back)
      runCurrent()
      assertEquals(
          VerificationLeaveError(VerificationLeave.Back, VerificationMessage.DeleteError),
          vm.uiState.value.leaveError,
      )
      assertFalse(vm.uiState.value.leaving)
      assertNull(vm.uiState.value.exit)
      dispose(vm)
    }
    assertEquals(0, repository.logOutCalls)
    assertEquals(user, repository.currentUser())
  }

  @Test
  fun duplicateTapsWhileDeletingCallTheFunctionOnceAndBlockOtherActions() = runTest {
    deletion.gate = CompletableDeferred()
    val vm = entered()
    val sends = repository.sendCalls
    vm.leave(VerificationLeave.ChangeAddress)
    runCurrent()
    vm.leave(VerificationLeave.Back)
    vm.retryLeave()
    vm.resend()
    vm.checkVerified()
    vm.checkVerified(manual = false)
    vm.enter()
    runCurrent()
    val state = vm.uiState.value
    assertTrue(state.leaving)
    assertTrue(state.busy)
    assertFalse(state.canResend)
    assertEquals(1, deletion.calls)
    assertEquals(sends, repository.sendCalls)
    assertEquals(0, repository.checkCalls)
    assertEquals(user, repository.currentUser())

    deletion.gate!!.complete(Unit)
    runCurrent()
    assertEquals(VerificationExit.SignUp(user.email), vm.uiState.value.exit)
    vm.leave(VerificationLeave.Back)
    runCurrent()
    assertEquals(1, deletion.calls)
    assertEquals(1, repository.logOutCalls)
    dispose(vm)
  }

  @Test
  fun resetDuringTheDeletionIgnoresItsLateAnswer() = runTest {
    deletion.gate = CompletableDeferred()
    val vm = entered()
    vm.leave(VerificationLeave.Back)
    runCurrent()
    vm.reset()
    deletion.gate!!.complete(Unit)
    runCurrent()
    assertEquals(VerifyEmailUiState(), vm.uiState.value)
    assertEquals(0, repository.logOutCalls)
    dispose(vm)
  }

  @Test
  fun leavingCancelsAPendingCheck() = runTest {
    val vm = entered()
    repository.checkGate = CompletableDeferred()
    repository.verificationResult = VerificationResult.Verified
    vm.checkVerified()
    runCurrent()
    assertTrue(vm.uiState.value.checking)
    deletion.gate = CompletableDeferred()
    vm.leave(VerificationLeave.Back)
    runCurrent()
    assertFalse(vm.uiState.value.checking)
    repository.checkGate!!.complete(Unit)
    runCurrent()
    assertFalse(vm.uiState.value.verified)
    deletion.gate!!.complete(Unit)
    runCurrent()
    assertEquals(VerificationExit.Welcome, vm.uiState.value.exit)
    dispose(vm)
  }

  @Test
  fun leavingWaitsForASendInFlightSoTheAddressKeepsItsCooldown() = runTest {
    repository.sendGate = CompletableDeferred()
    val vm = entered()
    assertTrue(vm.uiState.value.sending)
    vm.leave(VerificationLeave.ChangeAddress)
    runCurrent()
    assertEquals(0, deletion.calls)

    repository.sendGate!!.complete(Unit)
    runCurrent()
    assertEquals(1, deletion.calls)
    assertEquals(VerificationExit.SignUp(user.email), vm.uiState.value.exit)
    assertNull(store.timings[user.uid])
    assertEquals(VerificationTiming(now, now + 45_000), store.addressTimings[user.email])
    dispose(vm)
  }

  @Test
  fun cancellingTheViewModelWhileDeletingStopsLeaving() = runTest {
    deletion.gate = CompletableDeferred()
    val vm = entered()
    vm.leave(VerificationLeave.Back)
    runCurrent()
    dispose(vm)
    runCurrent()
    assertFalse(vm.uiState.value.leaving)
    assertNull(vm.uiState.value.exit)
    assertEquals(0, repository.logOutCalls)
  }

  @Test
  fun aFailedTimingCleanupAfterTheDeletionStillSignsOut() = runTest {
    val vm = entered()
    store.clearFailure = IllegalStateException("test cleanup failure")
    vm.leave(VerificationLeave.ChangeAddress)
    runCurrent()
    assertEquals(VerificationExit.SignUp(user.email), vm.uiState.value.exit)
    assertEquals(1, repository.logOutCalls)
    dispose(vm)
  }

  @Test
  fun leavingWithNobodySignedInOnlySignsOut() = runTest {
    repository.user = null
    val vm = viewModel()
    vm.retryLeave()
    assertEquals(0, repository.logOutCalls)
    vm.leave(VerificationLeave.ChangeAddress)
    runCurrent()
    assertEquals(0, deletion.calls)
    assertEquals(0, network.checks)
    assertEquals(1, repository.logOutCalls)
    assertEquals(VerificationExit.SignUp(""), vm.uiState.value.exit)
    dispose(vm)
  }

  @Test
  fun aNewActionClearsTheFailedWayOutButAResumeKeepsIt() = runTest {
    store.timings[user.uid] = VerificationTiming(now - 60_000, now - 15_000)
    network.online = false
    val vm = entered()
    vm.leave(VerificationLeave.Back)
    runCurrent()
    val failure = VerificationLeaveError(VerificationLeave.Back, VerificationMessage.NetworkError)
    assertEquals(failure, vm.uiState.value.leaveError)
    vm.checkVerified(manual = false)
    runCurrent()
    assertEquals(failure, vm.uiState.value.leaveError)
    vm.checkVerified()
    runCurrent()
    assertNull(vm.uiState.value.leaveError)
    assertEquals(VerificationMessage.StillUnverified, vm.uiState.value.banner)

    vm.leave(VerificationLeave.Back)
    runCurrent()
    assertEquals(failure, vm.uiState.value.leaveError)
    vm.resend()
    runCurrent()
    assertNull(vm.uiState.value.leaveError)
    assertEquals(VerificationMessage.Sent, vm.uiState.value.banner)
    dispose(vm)
  }

  @Test
  fun aNewAccountForAnAddressLeftAMomentAgoKeepsItsCooldown() = runTest {
    // Signed up again with the same address after deleting the first account (#99, #225).
    store.addressTimings[user.email] = VerificationTiming(now - 10_000, now + 35_000)
    repository.user = user.copy(uid = "new-uid", email = " Student.Test@EPFL.ch ")
    val vm = entered()
    val state = vm.uiState.value
    assertEquals(35L, state.remainingSeconds)
    assertFalse(state.canResend)
    // The new account itself was never sent a link, so nothing claims one was.
    assertFalse(state.hasSentEmail)
    assertNull(state.snackbar)
    assertNull(state.banner)
    assertEquals(0, repository.sendCalls)
    dispose(vm)
  }

  @Test
  fun aThrottledAddressShowsTheThrottleBannerForANewAccount() = runTest {
    store.addressTimings[user.email] = VerificationTiming(now - 10_000, now + 170_000)
    repository.user = user.copy(uid = "new-uid")
    val vm = entered()
    assertEquals(VerificationMessage.Throttled, vm.uiState.value.banner)
    assertEquals(170L, vm.uiState.value.remainingSeconds)
    dispose(vm)
  }

  @Test
  fun theAccountsOwnLongerDeadlineWinsOverItsAddress() = runTest {
    store.timings[user.uid] = VerificationTiming(now - 10_000, now + 170_000)
    store.addressTimings[user.email] = VerificationTiming(now - 40_000, now + 5_000)
    val vm = entered()
    assertEquals(170L, vm.uiState.value.remainingSeconds)
    assertEquals(VerificationMessage.Throttled, vm.uiState.value.banner)
    assertTrue(vm.uiState.value.hasSentEmail)
    dispose(vm)
  }

  @Test
  fun sendsRecordTheAddressAndVerificationClearsIt() = runTest {
    val vm = entered()
    assertEquals(VerificationTiming(now, now + 45_000), store.addressTimings[user.email])
    now += 45_000
    advanceTimeBy(46_000)
    runCurrent()
    repository.sendResult = SendVerificationResult.Throttled
    vm.resend()
    runCurrent()
    assertEquals(
        VerificationTiming(now - 45_000, now + VerifyEmailViewModel.THROTTLE_DELAY_MILLIS),
        store.addressTimings[user.email],
    )
    repository.verificationResult = VerificationResult.Verified
    vm.checkVerified()
    runCurrent()
    assertTrue(vm.uiState.value.verified)
    assertNull(store.timings[user.uid])
    assertNull(store.addressTimings[user.email])
    dispose(vm)
  }
}
