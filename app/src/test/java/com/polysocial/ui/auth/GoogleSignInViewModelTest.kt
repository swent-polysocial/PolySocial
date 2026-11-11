// Contributors: OpenAI Codex (removed obsolete collision outcome coverage for #107).
// Contributors: OpenAI Codex (Google state, cancellation and submission-race tests for #107).
// Contributors: Claude (Opus 5.5, Anthropic) tested both screens on the real repository with a fake
// Google sheet, #319.
package com.polysocial.ui.auth

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.firebase.auth.FirebaseAuth
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.FirebaseAuthRepository
import com.polysocial.model.auth.GoogleAccount
import com.polysocial.model.auth.GoogleCredential
import com.polysocial.model.auth.GoogleCredentialSource
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
import kotlinx.coroutines.test.TestScope
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

  /** Welcome's and Log in's Google state after one attempt each, through [repository]. */
  private fun TestScope.bothScreensAfterGoogle(
      repository: AuthRepository
  ): List<GoogleSignInState> {
    val welcome = WelcomeViewModel(repository)
    val login = LoginViewModel(repository)
    welcome.onGoogle(context)
    login.onGoogle(context)
    advanceUntilIdle()
    return listOf(welcome.uiState.value, login.uiState.value.google)
  }

  /** The real repository, with Google's sheet replaced by [sheet] (#319). */
  private fun repositoryWithSheet(sheet: GoogleCredentialSource) =
      FirebaseAuthRepository(mockk<FirebaseAuth>(relaxUnitFun = true), sheet)

  @Test
  fun nonEpflGoogleAccountShowsNotEpflOnBothScreens() = runTest {
    val account = GoogleAccount("someone@example.org", "Demo Person")
    val states =
        bothScreensAfterGoogle(repositoryWithSheet { GoogleCredential(account, "test-token") })
    states.forEach { assertEquals(GoogleSignInResult.NotEpfl(account), it.error) }
  }

  @Test
  fun googleFailureReportedAsCancellationShowsTheErrorOnBothScreens() = runTest {
    val states =
        bothScreensAfterGoogle(
            repositoryWithSheet {
              throw GetCredentialCancellationException("[16] Account reauth failed.")
            }
        )
    states.forEach {
      assertEquals(GoogleSignInResult.UnexpectedError, it.error)
      assertFalse(it.busy)
    }
  }

  @Test
  fun closingGoogleSheetLeavesBothScreensIdle() = runTest {
    val states =
        bothScreensAfterGoogle(
            repositoryWithSheet {
              throw GetCredentialCancellationException("[16] Cancelled by user.")
            }
        )
    states.forEach { assertEquals(GoogleSignInState(), it) }
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
