// Contributors: OpenAI Codex (confirmation, duplicate actions, cancellation and logout failure
// proof, #109).
// Contributors: Claude (reset for the next session, #127).
package com.polysocial.ui.profile

import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.*
import com.polysocial.model.firebase.*
import com.polysocial.utils.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val auth = FakeAuthRepository(AuthUser("student-one", "student.one@epfl.ch", true))
  private var result = CacheClearResult.Cleared
  private var error: Exception? = null
  private var gate: CompletableDeferred<Unit>? = null
  private var clears = 0
  private var clearJob: Job? = null
  private val cache =
      object : SessionCache {
        override suspend fun clear(): CacheClearResult {
          clears++
          clearJob = currentCoroutineContext().job
          gate?.await()
          error?.let { throw it }
          return result
        }
      }
  private val vm = ProfileViewModel(auth, cache, FakeAssociationRepository())

  @Test
  fun cancelKeepsTheAccountAndNeverClearsCache() {
    vm.confirmLogout()
    vm.cancelLogout()
    vm.requestLogout()
    vm.requestLogout()
    assertEquals(LogoutState.Confirming, vm.uiState.value)
    vm.cancelLogout()
    assertEquals(LogoutState.Idle, vm.uiState.value)
    assertNotNull(auth.user)
    assertEquals(0, clears)
    assertEquals(0, auth.logOutCalls)
  }

  @Test
  fun signsOutOnlyAfterCacheClearedAndIgnoresDuplicateConfirmation() =
      runTest(main.dispatcher) {
        gate = CompletableDeferred()
        vm.requestLogout()
        vm.confirmLogout()
        vm.confirmLogout()
        vm.requestLogout()
        vm.cancelLogout()
        runCurrent()
        assertEquals(LogoutState.Clearing, vm.uiState.value)
        assertNotNull(auth.user)
        assertEquals(0, auth.logOutCalls)
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(LogoutState.LoggedOut, vm.uiState.value)
        assertNull(auth.user)
        assertEquals(1, auth.logOutCalls)
        assertEquals(1, clears)
        vm.confirmLogout()
        assertEquals(1, auth.logOutCalls)
      }

  @Test
  fun cacheFailureBlocksDismissalAndRetryFinishesLogout() =
      runTest(main.dispatcher) {
        result = CacheClearResult.UnexpectedError
        vm.requestLogout()
        vm.confirmLogout()
        runCurrent()
        vm.cancelLogout()
        assertEquals(LogoutState.Error, vm.uiState.value)
        assertNotNull(auth.user)
        assertEquals(0, auth.logOutCalls)
        result = CacheClearResult.Cleared
        vm.confirmLogout()
        runCurrent()
        assertEquals(LogoutState.LoggedOut, vm.uiState.value)
        assertEquals(2, clears)
      }

  @Test
  fun unexpectedExceptionHasARetryPath() =
      runTest(main.dispatcher) {
        error = IllegalStateException("test failure")
        vm.requestLogout()
        vm.confirmLogout()
        runCurrent()
        assertEquals(LogoutState.Error, vm.uiState.value)
        error = null
        vm.confirmLogout()
        runCurrent()
        assertEquals(LogoutState.LoggedOut, vm.uiState.value)
      }

  @Test
  fun cancelledCleanupKeepsAccountAndCanBeRetried() =
      runTest(main.dispatcher) {
        error = CancellationException("cleanup cancelled")
        vm.requestLogout()
        vm.confirmLogout()
        runCurrent()
        assertEquals(LogoutState.Error, vm.uiState.value)
        assertTrue("cleanup cancellation must propagate to its coroutine", clearJob!!.isCancelled)
        assertEquals(AuthUser("student-one", "student.one@epfl.ch", true), auth.user)
        assertEquals(0, auth.logOutCalls)
        vm.cancelLogout()
        assertEquals(LogoutState.Error, vm.uiState.value)
        error = null
        vm.confirmLogout()
        runCurrent()
        assertEquals(LogoutState.LoggedOut, vm.uiState.value)
        assertNull(auth.user)
        assertEquals(1, auth.logOutCalls)
        assertEquals(2, clears)
      }

  @Test
  fun cancellingViewModelScopeStopsCleanupWithoutSigningOut() =
      runTest(main.dispatcher) {
        gate = CompletableDeferred()
        vm.requestLogout()
        vm.confirmLogout()
        runCurrent()
        vm.viewModelScope.cancel()
        runCurrent()
        assertEquals(LogoutState.Error, vm.uiState.value)
        assertNotNull(auth.user)
        assertEquals(0, auth.logOutCalls)
        gate!!.complete(Unit)
        runCurrent()
        assertNotNull(auth.user)
        assertEquals(0, auth.logOutCalls)
      }

  @Test
  fun signOutFailureIsRetryableAfterCleanup() =
      runTest(main.dispatcher) {
        val failing = mockk<AuthRepository>()
        every { failing.logOut() } throws IllegalStateException("test failure")
        val profile = ProfileViewModel(failing, cache, FakeAssociationRepository())
        profile.requestLogout()
        profile.confirmLogout()
        runCurrent()
        assertEquals(LogoutState.Error, profile.uiState.value)
        every { failing.logOut() } just Runs
        profile.confirmLogout()
        runCurrent()
        assertEquals(LogoutState.LoggedOut, profile.uiState.value)
        verify(exactly = 2) { failing.logOut() }
      }

  @Test
  fun reset_afterLoggingOut_startsTheNextSessionIdle_andIsIgnoredOtherwise() =
      runTest(main.dispatcher) {
        vm.reset()
        assertEquals(LogoutState.Idle, vm.uiState.value)
        vm.requestLogout()
        vm.reset()
        assertEquals(LogoutState.Confirming, vm.uiState.value)
        gate = CompletableDeferred()
        vm.confirmLogout()
        runCurrent()
        vm.reset()
        assertEquals(LogoutState.Clearing, vm.uiState.value)
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(LogoutState.LoggedOut, vm.uiState.value)

        vm.reset()

        assertEquals(LogoutState.Idle, vm.uiState.value)
        // The next session can log out again.
        vm.requestLogout()
        assertEquals(LogoutState.Confirming, vm.uiState.value)
      }
}
