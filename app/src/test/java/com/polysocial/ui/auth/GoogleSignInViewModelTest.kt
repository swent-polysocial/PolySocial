// Contributors: OpenAI Codex (Google state, cancellation and submission-race tests for #107).
package com.polysocial.ui.auth

import android.content.Context
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.GoogleAccount
import com.polysocial.model.auth.GoogleSignInResult
import com.polysocial.model.auth.LogInResult
import com.polysocial.ui.login.LoginStatus
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.utils.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleSignInViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val auth = FakeAuthRepository()
  private val context = mockk<Context>()
  private val user = AuthUser("student", "student@epfl.ch", true, "Demo Student")

  @Test
  fun welcomeLocksThroughSuccessAndResets() = runTest {
    val vm = WelcomeViewModel(auth)
    auth.googleGate = CompletableDeferred()
    auth.googleResult = GoogleSignInResult.Success(user)
    vm.onGoogle(context)
    vm.onGoogle(context)
    runCurrent()
    assertTrue(vm.uiState.value.loading)
    assertEquals(1, auth.googleCalls)
    auth.googleGate!!.complete(Unit)
    advanceUntilIdle()
    assertTrue(vm.uiState.value.busy)
    assertNull(vm.uiState.value.error)
    assertEquals(user, auth.currentUser())
    vm.reset()
    assertEquals(GoogleSignInState(), vm.uiState.value)
  }

  @Test
  fun bothViewModelsTreatCancellationAsIdle() = runTest {
    auth.googleResult = GoogleSignInResult.Cancelled
    val welcome = WelcomeViewModel(auth)
    val login = LoginViewModel(auth)
    welcome.onGoogle(context)
    login.onGoogle(context)
    advanceUntilIdle()
    assertEquals(GoogleSignInState(), welcome.uiState.value)
    assertEquals(GoogleSignInState(), login.uiState.value.google)
    assertNull(auth.currentUser())
  }

  @Test
  fun bothViewModelsKeepTypedErrorsAndDropIdentityOnBack() = runTest {
    val account = GoogleAccount("student@example.org", "Demo Student")
    for (result in
        listOf(
            GoogleSignInResult.NotEpfl(account),
            GoogleSignInResult.OtherSignInMethod(account),
            GoogleSignInResult.NetworkError,
            GoogleSignInResult.UnexpectedError,
        )) {
      auth.googleResult = result
      val welcome = WelcomeViewModel(auth)
      val login = LoginViewModel(auth)
      welcome.onGoogle(context)
      login.onGoogle(context)
      advanceUntilIdle()
      assertEquals(result, welcome.uiState.value.error)
      assertEquals(result, login.uiState.value.google.error)
      assertFalse(welcome.uiState.value.busy)
      assertNull(auth.currentUser())
      welcome.reset()
      login.dismissGoogleError()
      assertNull(welcome.uiState.value.error)
      assertNull(login.uiState.value.google.error)
    }
  }

  @Test
  fun googleBlocksPasswordAndDuplicateGoogleUntilHandoff() = runTest {
    val login = LoginViewModel(auth)
    login.onEmailChange("student@epfl.ch")
    login.onPasswordChange("test-password")
    auth.googleGate = CompletableDeferred()
    auth.googleResult = GoogleSignInResult.Success(user)
    login.onGoogle(context)
    login.onGoogle(context)
    login.onLogIn()
    runCurrent()
    assertEquals(1, auth.googleCalls)
    assertEquals(0, auth.logInCalls)
    assertFalse(login.uiState.value.canSubmit)
    auth.googleGate!!.complete(Unit)
    advanceUntilIdle()
    login.onLogIn()
    login.onGoogle(context)
    runCurrent()
    assertEquals(0, auth.logInCalls)
    assertEquals(1, auth.googleCalls)
  }

  @Test
  fun passwordAttemptBlocksGoogleUntilFailure() = runTest {
    val login = LoginViewModel(auth)
    login.onEmailChange("student@epfl.ch")
    login.onPasswordChange("test-password")
    auth.logInGate = CompletableDeferred()
    auth.logInResult = LogInResult.NetworkError
    login.onLogIn()
    login.onGoogle(context)
    runCurrent()
    assertEquals(0, auth.googleCalls)
    auth.logInGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(LoginStatus.CantConnect, login.uiState.value.status)
    auth.googleResult = GoogleSignInResult.Cancelled
    login.onGoogle(context)
    advanceUntilIdle()
    assertEquals(1, auth.googleCalls)
    assertEquals(LoginStatus.Idle, login.uiState.value.status)
  }

  @Test
  fun unexpectedRepositoryFailureRestoresRecoverableUiOnBothScreens() = runTest {
    val failing = mockk<AuthRepository>()
    coEvery { failing.signInWithGoogle(context) } throws IllegalStateException("test")
    val welcome = WelcomeViewModel(failing)
    val login = LoginViewModel(failing)
    welcome.onGoogle(context)
    login.onGoogle(context)
    advanceUntilIdle()
    assertEquals(GoogleSignInResult.UnexpectedError, welcome.uiState.value.error)
    assertEquals(GoogleSignInResult.UnexpectedError, login.uiState.value.google.error)
    assertFalse(welcome.uiState.value.busy)
    assertFalse(login.uiState.value.google.busy)
  }

  @Test
  fun cancelledCoroutineDoesNotTurnIntoUiError() = runTest {
    auth.googleGate = CompletableDeferred()
    val attempt = async { googleSignIn(auth, context) }
    runCurrent()
    attempt.cancel()
    attempt.join()
    assertTrue(attempt.isCancelled)
    assertNull(auth.currentUser())
  }

  @Test
  fun passwordSuccessAndUnverifiedHandoffBlockGoogle() = runTest {
    for (verified in listOf(true, false)) {
      val login = LoginViewModel(auth)
      login.onEmailChange("student@epfl.ch")
      login.onPasswordChange("test-password")
      auth.logInResult = LogInResult.Success(user.copy(isEmailVerified = verified))
      login.onLogIn()
      advanceUntilIdle()
      login.onGoogle(context)
      advanceUntilIdle()
      assertEquals(0, auth.googleCalls)
    }
  }
}
