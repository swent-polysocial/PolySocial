// Contributors: OpenAI Codex (confirmation, duplicate actions and logout failure proof, #109).
package com.polysocial.ui.profile

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
  private val cache =
      object : SessionCache {
        override suspend fun clear(): CacheClearResult {
          clears++
          gate?.await()
          error?.let { throw it }
          return result
        }
      }
  private val vm = ProfileViewModel(auth, cache)

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
  fun signOutFailureIsRetryableAfterCleanup() =
      runTest(main.dispatcher) {
        val failing = mockk<AuthRepository>()
        every { failing.logOut() } throws IllegalStateException("test failure")
        val profile = ProfileViewModel(failing, cache)
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
}
