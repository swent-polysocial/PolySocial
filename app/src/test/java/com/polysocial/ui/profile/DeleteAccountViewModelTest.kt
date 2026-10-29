// Contributors: Claude (Opus 5.5, Anthropic) drafted the account deletion flow tests, #207; Mohamed
// Khellaf (reviewed and revised).
package com.polysocial.ui.profile

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.polysocial.auth.MemoryVerificationStore
import com.polysocial.model.account.AccountDeletionResult
import com.polysocial.model.account.FakeAccountDeletionRepository
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.ReauthResult
import com.polysocial.model.auth.SignInMethod
import com.polysocial.model.auth.VerificationStore
import com.polysocial.model.auth.VerificationTiming
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.SessionCache
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.utils.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val student = AuthUser("fictional-uid", "fictional.student@epfl.ch", true)
  private val fakeAuth = FakeAuthRepository(student)
  private val deletion = FakeAccountDeletionRepository()

  /** What touched the account or this device, in order. */
  private val events = mutableListOf<String>()

  private val auth: AuthRepository =
      object : AuthRepository by fakeAuth {
        override fun logOut() {
          events += "logOut"
          fakeAuth.logOut()
        }
      }

  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private var cacheResult = CacheClearResult.Cleared
  private var clears = 0
  private val cache =
      object : SessionCache {
        override suspend fun clear(): CacheClearResult {
          clears++
          events += "cache"
          return cacheResult
        }
      }

  private val memory = MemoryVerificationStore()
  private val verification: VerificationStore =
      object : VerificationStore by memory {
        override suspend fun clear(uid: String) {
          events += "verification:$uid"
          memory.clear(uid)
        }
      }

  private val context = mockk<Context>()
  private val vm = DeleteAccountViewModel(auth, deletion, network, cache, verification)

  private val step
    get() = vm.uiState.value.step

  private val error
    get() = vm.uiState.value.error

  private fun openReauth() {
    vm.request()
    vm.confirm()
    assertEquals(DeleteAccountStep.Reauthenticate, step)
  }

  private fun assertAccountUntouched() {
    assertEquals(student, fakeAuth.user)
    assertEquals(0, fakeAuth.logOutCalls)
    assertEquals(0, clears)
    assertTrue(events.isEmpty())
  }

  @Test
  fun cancellingTheConfirmationCallsNothing() {
    vm.cancel()
    vm.confirm()
    assertEquals(DeleteAccountStep.Hidden, step)
    vm.request()
    vm.request()
    assertEquals(DeleteAccountStep.Confirm, step)
    vm.cancel()
    assertEquals(DeleteAccountUiState(), vm.uiState.value)
    assertEquals(0, deletion.calls)
    assertEquals(0, fakeAuth.reauthCalls)
    assertAccountUntouched()
  }

  @Test
  fun cancellingTheReauthStepKeepsTheAccountAndForgetsThePassword() {
    openReauth()
    vm.onPasswordChange("typed-secret")
    vm.cancel()
    assertEquals(DeleteAccountUiState(), vm.uiState.value)
    assertEquals(0, fakeAuth.reauthCalls)
    assertEquals(0, deletion.calls)
    assertAccountUntouched()
  }

  @Test
  fun offlineIsRefusedBeforeAnyCallAndConfirmWorksOnceBackOnline() {
    online = false
    vm.request()
    vm.confirm()
    assertEquals(DeleteAccountStep.Confirm, step)
    assertEquals(DeleteAccountError.Offline, error)
    assertEquals(0, fakeAuth.reauthCalls)
    assertEquals(0, deletion.calls)
    assertAccountUntouched()
    online = true
    vm.confirm()
    assertEquals(DeleteAccountStep.Reauthenticate, step)
    assertNull(error)
  }

  @Test
  fun passwordReauthDeletesThenClearsCacheAndCooldownsBeforeSigningOut() =
      runTest(main.dispatcher) {
        memory.timings[student.uid] = VerificationTiming(1, 2)
        memory.timings["someone-else"] = VerificationTiming(3, 4)
        openReauth()
        assertEquals(setOf(SignInMethod.PASSWORD), vm.uiState.value.methods)
        assertEquals(student.email, vm.uiState.value.email)
        vm.submitPassword()
        assertEquals("an empty password is not sent", 0, fakeAuth.reauthCalls)
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals("fictional-password", fakeAuth.lastReauthPassword)
        assertEquals(1, deletion.calls)
        assertEquals(DeleteAccountStep.Done, step)
        assertEquals(true, vm.uiState.value.deleted)
        assertEquals(listOf("cache", "verification:${student.uid}", "logOut"), events)
        assertNull(fakeAuth.user)
        assertNull(memory.timings[student.uid])
        assertEquals(VerificationTiming(3, 4), memory.timings["someone-else"])
        assertEquals("", vm.uiState.value.password)
      }

  @Test
  fun googleReauthDeletesTheAccount() =
      runTest(main.dispatcher) {
        fakeAuth.signInMethods = setOf(SignInMethod.GOOGLE)
        openReauth()
        vm.submitPassword()
        assertEquals("no password step for a Google account", 0, fakeAuth.reauthCalls)
        vm.continueWithGoogle(context)
        runCurrent()
        assertEquals(1, fakeAuth.googleReauthCalls)
        assertEquals(1, deletion.calls)
        assertEquals(DeleteAccountStep.Done, step)
      }

  @Test
  fun bothMethodsAreOfferedAndGoogleIsIgnoredForAPasswordOnlyAccount() =
      runTest(main.dispatcher) {
        openReauth()
        vm.continueWithGoogle(context)
        assertEquals(0, fakeAuth.googleReauthCalls)
        vm.cancel()
        fakeAuth.signInMethods = setOf(SignInMethod.PASSWORD, SignInMethod.GOOGLE)
        openReauth()
        assertEquals(
            setOf(SignInMethod.PASSWORD, SignInMethod.GOOGLE),
            vm.uiState.value.methods,
        )
      }

  @Test
  fun passwordVisibilityTogglesOnlyOnTheStep() {
    vm.togglePasswordVisibility()
    assertEquals(false, vm.uiState.value.passwordVisible)
    openReauth()
    vm.togglePasswordVisibility()
    assertEquals(true, vm.uiState.value.passwordVisible)
    vm.togglePasswordVisibility()
    assertEquals(false, vm.uiState.value.passwordVisible)
  }

  @Test
  fun noSignInMethodIsAnErrorWithRetry() {
    fakeAuth.signInMethods = emptySet()
    vm.request()
    vm.confirm()
    assertEquals(DeleteAccountStep.Confirm, step)
    assertEquals(DeleteAccountError.Unexpected, error)
    fakeAuth.signInMethods = setOf(SignInMethod.PASSWORD)
    vm.confirm()
    assertEquals(DeleteAccountStep.Reauthenticate, step)
  }

  @Test
  fun dismissedGooglePickerStaysOnTheStepWithoutDeleting() =
      runTest(main.dispatcher) {
        fakeAuth.signInMethods = setOf(SignInMethod.GOOGLE)
        fakeAuth.reauthResult = ReauthResult.Cancelled
        openReauth()
        vm.continueWithGoogle(context)
        runCurrent()
        assertEquals(DeleteAccountStep.Reauthenticate, step)
        assertEquals(DeleteAccountError.GoogleCancelled, error)
        assertEquals(0, deletion.calls)
        assertAccountUntouched()
      }

  @Test
  fun wrongPasswordStaysOnTheStepAndTypingClearsTheError() =
      runTest(main.dispatcher) {
        fakeAuth.reauthResult = ReauthResult.WrongPassword
        openReauth()
        vm.onPasswordChange("wrong-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.Reauthenticate, step)
        assertEquals(DeleteAccountError.WrongPassword, error)
        assertEquals("", vm.uiState.value.password)
        assertEquals(0, deletion.calls)
        assertAccountUntouched()
        vm.onPasswordChange("p")
        assertNull(error)
        fakeAuth.reauthResult = ReauthResult.Success
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.Done, step)
      }

  @Test
  fun lostSessionDuringReauthRunsTheCleanupWithoutClaimingDeletion() =
      runTest(main.dispatcher) {
        memory.timings[student.uid] = VerificationTiming(1, 2)
        fakeAuth.reauthResult = ReauthResult.NotSignedIn
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.Done, step)
        assertEquals(false, vm.uiState.value.deleted)
        assertEquals(0, deletion.calls)
        assertEquals(listOf("cache", "verification:${student.uid}", "logOut"), events)
        assertNull(memory.timings[student.uid])
      }

  @Test
  fun uidReadAtTheStartStillClearsCooldownsAfterFirebaseSignsOutLocally() =
      runTest(main.dispatcher) {
        memory.timings[student.uid] = VerificationTiming(1, 2)
        // Firebase signs a deleted user out locally when it learns the account is gone.
        val signingOut =
            object : AuthRepository by auth {
              override suspend fun reauthenticateWithPassword(password: String): ReauthResult {
                fakeAuth.user = null
                return ReauthResult.NotSignedIn
              }
            }
        val model = DeleteAccountViewModel(signingOut, deletion, network, cache, verification)
        model.request()
        model.confirm()
        model.onPasswordChange("fictional-password")
        model.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.Done, model.uiState.value.step)
        assertEquals(listOf("cache", "verification:${student.uid}", "logOut"), events)
        assertNull(memory.timings[student.uid])
      }

  @Test
  fun lostSessionCleanupFailureBlocksWithoutClaimingDeletion() =
      runTest(main.dispatcher) {
        cacheResult = CacheClearResult.UnexpectedError
        fakeAuth.reauthResult = ReauthResult.NotSignedIn
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.CleanupFailed, step)
        assertEquals(false, vm.uiState.value.deleted)
        cacheResult = CacheClearResult.Cleared
        vm.retry()
        runCurrent()
        assertEquals(DeleteAccountStep.Done, step)
        assertEquals(false, vm.uiState.value.deleted)
        assertEquals(0, deletion.calls)
      }

  @Test
  fun signedOutAtConfirmTakesTheLogoutPath() =
      runTest(main.dispatcher) {
        fakeAuth.user = null
        vm.request()
        vm.confirm()
        runCurrent()
        assertEquals(DeleteAccountStep.Done, step)
        assertEquals(listOf("cache", "logOut"), events)
        assertEquals(0, fakeAuth.reauthCalls)
      }

  @Test
  fun lostSessionDuringDeletionSignsOut() =
      runTest(main.dispatcher) {
        deletion.result = AccountDeletionResult.NotSignedIn
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.Done, step)
        assertEquals(false, vm.uiState.value.deleted)
        assertEquals(listOf("cache", "verification:${student.uid}", "logOut"), events)
      }

  @Test
  fun reauthNetworkAndUnexpectedErrorsCanBeRetriedOnTheStep() =
      runTest(main.dispatcher) {
        fakeAuth.reauthResult = ReauthResult.NetworkError
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.Reauthenticate, step)
        assertEquals(DeleteAccountError.Network, error)
        fakeAuth.reauthResult = ReauthResult.UnexpectedError
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountError.Unexpected, error)
        assertEquals(0, deletion.calls)
        assertAccountUntouched()
      }

  @Test
  fun offlineAtReauthIsRefusedWithoutACall() {
    openReauth()
    vm.onPasswordChange("fictional-password")
    online = false
    vm.submitPassword()
    assertEquals(DeleteAccountStep.Reauthenticate, step)
    assertEquals(DeleteAccountError.Offline, error)
    assertEquals(0, fakeAuth.reauthCalls)
  }

  @Test
  fun recentLoginRequiredReturnsToReauthAndTheNextAttemptDeletes() =
      runTest(main.dispatcher) {
        deletion.result = AccountDeletionResult.RecentLoginRequired
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.Reauthenticate, step)
        assertEquals(DeleteAccountError.SignInAgain, error)
        assertEquals("", vm.uiState.value.password)
        assertAccountUntouched()
        deletion.result = AccountDeletionResult.Deleted
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(2, fakeAuth.reauthCalls)
        assertEquals(2, deletion.calls)
        assertEquals(DeleteAccountStep.Done, step)
      }

  @Test
  fun associationIsRefusedAndCanCloseTheMessage() =
      runTest(main.dispatcher) {
        deletion.result = AccountDeletionResult.NotAllowed
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.NotAllowed, step)
        assertNull(error)
        vm.retry()
        assertEquals(1, deletion.calls)
        assertAccountUntouched()
        vm.cancel()
        assertEquals(DeleteAccountStep.Hidden, step)
      }

  @Test
  fun networkErrorThenRetrySucceedsWithoutAnotherReauth() =
      runTest(main.dispatcher) {
        deletion.result = AccountDeletionResult.NetworkError
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.DeleteFailed, step)
        assertEquals(DeleteAccountError.Network, error)
        assertAccountUntouched()
        online = false
        vm.retry()
        assertEquals(DeleteAccountError.Offline, error)
        assertEquals(1, deletion.calls)
        online = true
        deletion.result = AccountDeletionResult.Deleted
        vm.retry()
        assertEquals(DeleteAccountStep.Deleting, step)
        runCurrent()
        assertEquals(1, fakeAuth.reauthCalls)
        assertEquals(2, deletion.calls)
        assertEquals(DeleteAccountStep.Done, step)
        assertEquals(listOf("cache", "verification:${student.uid}", "logOut"), events)
      }

  @Test
  fun unexpectedErrorAndThrownFailuresOfferRetryAndCanBeCancelled() =
      runTest(main.dispatcher) {
        deletion.result = AccountDeletionResult.UnexpectedError
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.DeleteFailed, step)
        assertEquals(DeleteAccountError.Unexpected, error)
        vm.cancel()
        assertEquals(DeleteAccountStep.Hidden, step)
        assertAccountUntouched()

        val throwing =
            DeleteAccountViewModel(
                auth,
                object : com.polysocial.model.account.AccountDeletionRepository {
                  override suspend fun deleteAccount(): AccountDeletionResult =
                      throw IllegalStateException("fictional failure")
                },
                network,
                cache,
                verification,
            )
        throwing.request()
        throwing.confirm()
        throwing.onPasswordChange("fictional-password")
        throwing.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.DeleteFailed, throwing.uiState.value.step)
        assertEquals(DeleteAccountError.Unexpected, throwing.uiState.value.error)
        assertAccountUntouched()
      }

  @Test
  fun thrownReauthFailureIsAnUnexpectedErrorOnTheStep() =
      runTest(main.dispatcher) {
        val throwingAuth =
            object : AuthRepository by fakeAuth {
              override suspend fun reauthenticateWithPassword(password: String): ReauthResult =
                  throw IllegalStateException("fictional failure")
            }
        val model = DeleteAccountViewModel(throwingAuth, deletion, network, cache, verification)
        model.request()
        model.confirm()
        model.onPasswordChange("fictional-password")
        model.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.Reauthenticate, model.uiState.value.step)
        assertEquals(DeleteAccountError.Unexpected, model.uiState.value.error)
        assertEquals(0, deletion.calls)
      }

  @Test
  fun duplicateTapsWhileDeletingAreIgnoredAndTheSheetStaysOpen() =
      runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        deletion.gate = gate
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.Deleting, step)
        assertTrue(vm.uiState.value.busy)
        vm.submitPassword()
        vm.continueWithGoogle(context)
        vm.retry()
        vm.confirm()
        vm.request()
        vm.cancel()
        vm.onPasswordChange("typed-while-busy")
        runCurrent()
        assertEquals(DeleteAccountStep.Deleting, step)
        assertEquals(1, fakeAuth.reauthCalls)
        assertEquals(1, deletion.calls)
        assertEquals(student, fakeAuth.user)
        gate.complete(Unit)
        runCurrent()
        assertEquals(DeleteAccountStep.Done, step)
        assertEquals(1, deletion.calls)
        assertEquals(1, fakeAuth.logOutCalls)
      }

  @Test
  fun duplicateTapsWhileReauthenticatingAreIgnored() =
      runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        fakeAuth.reauthGate = gate
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.Reauthenticating, step)
        vm.submitPassword()
        vm.cancel()
        runCurrent()
        assertEquals(1, fakeAuth.reauthCalls)
        assertEquals(DeleteAccountStep.Reauthenticating, step)
        gate.complete(Unit)
        runCurrent()
        assertEquals(DeleteAccountStep.Done, step)
      }

  @Test
  fun failedCacheClearBlocksWithRetryUntilCleared() =
      runTest(main.dispatcher) {
        cacheResult = CacheClearResult.UnexpectedError
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.CleanupFailed, step)
        assertEquals(true, vm.uiState.value.deleted)
        assertTrue(!vm.uiState.value.dismissible)
        assertNotNull("not signed out while the cache is dirty", fakeAuth.user)
        assertEquals(0, fakeAuth.logOutCalls)
        vm.cancel()
        assertEquals(DeleteAccountStep.CleanupFailed, step)
        cacheResult = CacheClearResult.Cleared
        vm.retry()
        runCurrent()
        assertEquals(1, deletion.calls)
        assertEquals(DeleteAccountStep.Done, step)
        assertEquals(
            listOf("cache", "cache", "verification:${student.uid}", "logOut"),
            events,
        )
      }

  @Test
  fun failedCooldownClearAlsoBlocksWithRetry() =
      runTest(main.dispatcher) {
        memory.clearFailure = IllegalStateException("fictional failure")
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.CleanupFailed, step)
        assertEquals(0, fakeAuth.logOutCalls)
        memory.clearFailure = null
        vm.retry()
        runCurrent()
        assertEquals(DeleteAccountStep.Done, step)
        assertEquals(1, fakeAuth.logOutCalls)
      }

  @Test
  fun callerCancellationDuringDeletionStopsWithoutCleanup() =
      runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        deletion.gate = gate
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        assertEquals(DeleteAccountStep.Deleting, step)
        vm.viewModelScope.cancel()
        runCurrent()
        assertEquals(DeleteAccountStep.DeleteFailed, step)
        gate.complete(Unit)
        runCurrent()
        assertAccountUntouched()
      }

  @Test
  fun callerCancellationDuringReauthStopsBeforeDeleting() =
      runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        fakeAuth.reauthGate = gate
        openReauth()
        vm.onPasswordChange("fictional-password")
        vm.submitPassword()
        runCurrent()
        vm.viewModelScope.cancel()
        runCurrent()
        assertEquals(DeleteAccountStep.Reauthenticate, step)
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, deletion.calls)
        assertAccountUntouched()
      }
}
