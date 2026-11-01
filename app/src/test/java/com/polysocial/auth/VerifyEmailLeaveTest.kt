// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests for leaving Verify Email through the
// account deletion and for the address-keyed cooldown, #225; Mohamed Khellaf (reviewed and
// revised).
// Contributors: Claude (Opus 5.5, Anthropic) tested that leaving never deletes a verified account
// and finishes a deletion whose answer was lost, #225 review.
package com.polysocial.auth

import androidx.lifecycle.ViewModelStore
import com.polysocial.model.account.AccountDeletionRepository
import com.polysocial.model.account.AccountDeletionResult
import com.polysocial.model.account.FakeAccountDeletionRepository
import com.polysocial.model.auth.AuthRepository
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
      auth: AuthRepository = repository,
  ) = VerifyEmailViewModel(auth, store, VerificationClock { now }, deletion, network)

  private fun dispose(vm: VerifyEmailViewModel) =
      ViewModelStore().apply {
        put("verification", vm)
        clear()
      }

  /** A view model that has entered Verify Email for [user]. */
  private fun TestScope.entered(
      deletion: AccountDeletionRepository = this@VerifyEmailLeaveTest.deletion,
      network: NetworkMonitor = this@VerifyEmailLeaveTest.network,
      auth: AuthRepository = repository,
  ): VerifyEmailViewModel =
      viewModel(deletion, network, auth).also {
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
    assertEquals(0, repository.checkCalls)
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
    // Only the reload that leaving makes itself, before deleting: no check starts meanwhile.
    assertEquals(1, repository.checkCalls)
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
  fun backDuringACheckThatFindsTheAccountVerifiedSignsOutWithoutDeleting() = runTest {
    // Verified in the browser, then Back while the resume check is still running.
    store.timings[user.uid] = VerificationTiming(now, now + 45_000)
    store.addressTimings[user.email] = VerificationTiming(now, now + 45_000)
    val vm = entered()
    repository.checkGate = CompletableDeferred()
    repository.verificationResult = VerificationResult.Verified
    vm.checkVerified(manual = false)
    runCurrent()
    assertTrue(vm.uiState.value.checking)
    vm.leave(VerificationLeave.Back)
    runCurrent()
    assertFalse(vm.uiState.value.checking)
    assertTrue(vm.uiState.value.leaving)
    repository.checkGate!!.complete(Unit)
    runCurrent()
    assertEquals(0, deletion.calls)
    assertEquals(1, repository.logOutCalls)
    assertNull(repository.currentUser())
    assertEquals(VerifyEmailUiState(exit = VerificationExit.Welcome), vm.uiState.value)
    // As after a successful check, a verified account's deadlines are cleared.
    assertNull(store.timings[user.uid])
    assertNull(store.addressTimings[user.email])
    dispose(vm)
  }

  @Test
  fun aReloadThatFindsTheAccountVerifiedSignsOutWithoutDeleting() = runTest {
    // Verified from another device: no check ran here, the reload before deleting finds it.
    for ((action, exit) in
        listOf(
            VerificationLeave.Back to VerificationExit.Welcome,
            VerificationLeave.ChangeAddress to VerificationExit.SignUp(user.email),
        )) {
      repository.user = user
      repository.verificationResult = VerificationResult.Verified
      val vm = entered()
      vm.leave(action)
      runCurrent()
      assertEquals(action.name, exit, vm.uiState.value.exit)
      assertNull(action.name, repository.currentUser())
      // Entry sent the first link, so both deadlines existed; verification clears them.
      assertNull(action.name, store.timings[user.uid])
      assertNull(action.name, store.addressTimings[user.email])
      dispose(vm)
    }
    assertEquals(2, repository.checkCalls)
    assertEquals(0, deletion.calls)
    assertEquals(2, repository.logOutCalls)
  }

  @Test
  fun aReloadThatNoLongerFindsTheAccountFinishesAsAfterADeletion() = runTest {
    repository.verificationResult = VerificationResult.NotSignedIn
    val vm = entered()
    vm.leave(VerificationLeave.ChangeAddress)
    runCurrent()
    assertEquals(0, deletion.calls)
    assertEquals(1, repository.logOutCalls)
    assertEquals(VerifyEmailUiState(exit = VerificationExit.SignUp(user.email)), vm.uiState.value)
    assertNull(store.timings[user.uid])
    assertEquals(VerificationTiming(now, now + 45_000), store.addressTimings[user.email])
    dispose(vm)
  }

  @Test
  fun retryAfterADeletionWhoseAnswerWasLostExitsWithoutAFailure() = runTest {
    deletion.result = AccountDeletionResult.NetworkError
    val vm = entered()
    vm.leave(VerificationLeave.Back)
    runCurrent()
    assertEquals(
        VerificationLeaveError(VerificationLeave.Back, VerificationMessage.DeleteNetworkError),
        vm.uiState.value.leaveError,
    )
    // The function did delete the account; only its answer was lost. The reload now finds no
    // account, which the real repository reports as NotSignedIn.
    repository.verificationResult = VerificationResult.NotSignedIn
    vm.retryLeave()
    runCurrent()
    assertEquals(VerifyEmailUiState(exit = VerificationExit.Welcome), vm.uiState.value)
    assertEquals(1, deletion.calls)
    assertEquals(1, repository.logOutCalls)
    assertNull(store.timings[user.uid])
    assertEquals(VerificationTiming(now, now + 45_000), store.addressTimings[user.email])
    dispose(vm)
  }

  @Test
  fun aFailedReloadNeverDeletesAndRetryDeletesOnceTheReloadWorks() = runTest {
    var thrown = false
    val throwingAuth =
        object : AuthRepository by repository {
          override suspend fun reloadAndCheckVerified(): VerificationResult =
              if (thrown) throw IllegalStateException("test reload failure")
              else repository.reloadAndCheckVerified()
        }
    for ((failure, message) in
        listOf(
            VerificationResult.NetworkError to VerificationMessage.DeleteNetworkError,
            VerificationResult.UnexpectedError to VerificationMessage.DeleteError,
            null to VerificationMessage.DeleteError,
        )) {
      val name = failure.toString()
      repository.user = user
      thrown = failure == null
      repository.verificationResult = failure ?: VerificationResult.Unverified
      val deletions = deletion.calls
      val logOuts = repository.logOutCalls
      val vm = entered(auth = throwingAuth)
      vm.leave(VerificationLeave.Back)
      runCurrent()
      assertEquals(
          name,
          VerificationLeaveError(VerificationLeave.Back, message),
          vm.uiState.value.leaveError,
      )
      assertFalse(name, vm.uiState.value.leaving)
      assertNull(name, vm.uiState.value.exit)
      assertEquals(name, deletions, deletion.calls)
      assertEquals(name, logOuts, repository.logOutCalls)
      assertEquals(name, user, repository.currentUser())

      // Retry on the same screen once the reload works deletes the still unverified account.
      thrown = false
      repository.verificationResult = VerificationResult.Unverified
      vm.retryLeave()
      runCurrent()
      assertEquals(name, deletions + 1, deletion.calls)
      assertEquals(name, VerifyEmailUiState(exit = VerificationExit.Welcome), vm.uiState.value)
      dispose(vm)
    }
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

    // The same for a verified account found on leave.
    repository.user = user
    repository.verificationResult = VerificationResult.Verified
    val verified = entered()
    verified.leave(VerificationLeave.Back)
    runCurrent()
    assertEquals(VerificationExit.Welcome, verified.uiState.value.exit)
    assertEquals(2, repository.logOutCalls)
    assertEquals(1, deletion.calls)
    dispose(verified)
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
