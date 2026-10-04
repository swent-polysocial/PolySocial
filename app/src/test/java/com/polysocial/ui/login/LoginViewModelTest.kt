// Contributors: Claude Opus 5.5 (wrote these tests).
package com.polysocial.ui.login

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.LogInResult
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoginViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val auth = FakeAuthRepository()
  private val viewModel = LoginViewModel(auth)

  private val verifiedUser = AuthUser(uid = "u1", email = "a@epfl.ch", isEmailVerified = true)

  private fun fillForm(email: String = "a@epfl.ch", password: String = "secret") {
    viewModel.onEmailChange(email)
    viewModel.onPasswordChange(password)
  }

  private fun status() = viewModel.uiState.value.status

  private fun logInWith(result: LogInResult): LoginStatus {
    auth.logInResult = result
    fillForm()
    viewModel.onLogIn()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    return status()
  }

  @Test
  fun initialState_isEmptyIdleAndCannotSubmit() {
    val state = viewModel.uiState.value

    assertEquals(LoginUiState(), state)
    assertEquals(LoginStatus.Idle, state.status)
    assertFalse(state.passwordVisible)
    assertFalse(state.canSubmit)
  }

  @Test
  fun wrongCredentials_emitsWrongCredentialsState() {
    assertEquals(LoginStatus.WrongCredentials, logInWith(LogInResult.WrongCredentials))
  }

  @Test
  fun unverifiedLogin_emitsUnverifiedNotLoggedIn() {
    val status = logInWith(LogInResult.Success(verifiedUser.copy(isEmailVerified = false)))

    assertEquals(LoginStatus.Unverified, status)
  }

  @Test
  fun verifiedLogin_emitsLoggedIn() {
    assertEquals(LoginStatus.LoggedIn, logInWith(LogInResult.Success(verifiedUser)))
  }

  @Test
  fun networkError_emitsCantConnect() {
    assertEquals(LoginStatus.CantConnect, logInWith(LogInResult.NetworkError))
  }

  @Test
  fun unexpectedError_emitsCantConnect() {
    assertEquals(LoginStatus.CantConnect, logInWith(LogInResult.UnexpectedError))
  }

  @Test
  fun logIn_isLoadingWhileTheRepositoryCallIsInProgress() = runTest {
    auth.logInResult = LogInResult.Success(verifiedUser)
    fillForm()

    viewModel.onLogIn()

    assertEquals(LoginStatus.Loading, status())
    assertFalse(viewModel.uiState.value.canSubmit)
    assertEquals(0, auth.logInCalls)

    advanceUntilIdle()

    assertEquals(1, auth.logInCalls)
    assertEquals(LoginStatus.LoggedIn, status())
  }

  @Test
  fun logInTwiceWhileLoading_callsTheRepositoryOnce() = runTest {
    fillForm()

    viewModel.onLogIn()
    viewModel.onLogIn()
    advanceUntilIdle()

    assertEquals(1, auth.logInCalls)
  }

  @Test
  fun emptyFields_doNotCallTheRepository() = runTest {
    viewModel.onLogIn()
    advanceUntilIdle()

    assertFalse(viewModel.uiState.value.canSubmit)
    assertEquals(0, auth.logInCalls)
    assertEquals(LoginStatus.Idle, status())
  }

  @Test
  fun blankEmail_doesNotCallTheRepository() = runTest {
    fillForm(email = "   ")

    viewModel.onLogIn()
    advanceUntilIdle()

    assertFalse(viewModel.uiState.value.canSubmit)
    assertEquals(0, auth.logInCalls)
    assertEquals(LoginStatus.Idle, status())
  }

  @Test
  fun blankPassword_doesNotCallTheRepository() = runTest {
    fillForm(password = " ")

    viewModel.onLogIn()
    advanceUntilIdle()

    assertFalse(viewModel.uiState.value.canSubmit)
    assertEquals(0, auth.logInCalls)
  }

  @Test
  fun filledFields_canSubmit() {
    fillForm()

    assertTrue(viewModel.uiState.value.canSubmit)
  }

  @Test
  fun logIn_trimsTheEmailButNotThePassword() = runTest {
    fillForm(email = "  a@epfl.ch \n", password = " pass word ")

    viewModel.onLogIn()
    advanceUntilIdle()

    assertEquals("a@epfl.ch", auth.lastLogInEmail)
    assertEquals(" pass word ", auth.lastLogInPassword)
  }

  @Test
  fun fieldChanges_updateTheState() {
    fillForm(email = "b@epfl.ch", password = "pw")

    assertEquals("b@epfl.ch", viewModel.uiState.value.email)
    assertEquals("pw", viewModel.uiState.value.password)
  }

  @Test
  fun typingAfterWrongCredentials_clearsTheError() {
    logInWith(LogInResult.WrongCredentials)

    viewModel.onEmailChange("c@epfl.ch")

    assertEquals(LoginStatus.Idle, status())
  }

  @Test
  fun typingPasswordAfterWrongCredentials_clearsTheError() {
    logInWith(LogInResult.WrongCredentials)

    viewModel.onPasswordChange("other")

    assertEquals(LoginStatus.Idle, status())
  }

  @Test
  fun typingAfterCantConnect_clearsTheError() {
    logInWith(LogInResult.NetworkError)

    viewModel.onPasswordChange("other")

    assertEquals(LoginStatus.Idle, status())
  }

  @Test
  fun typingAfterUnverified_keepsTheStatus() {
    logInWith(LogInResult.Success(verifiedUser.copy(isEmailVerified = false)))

    viewModel.onEmailChange("c@epfl.ch")
    viewModel.onPasswordChange("other")

    assertEquals(LoginStatus.Unverified, status())
  }

  @Test
  fun typingWhileLoading_keepsLoading() {
    fillForm()
    viewModel.onLogIn()

    viewModel.onEmailChange("c@epfl.ch")

    assertEquals(LoginStatus.Loading, status())
  }

  @Test
  fun togglePasswordVisibility_flipsBothWays() {
    viewModel.onTogglePasswordVisibility()
    assertTrue(viewModel.uiState.value.passwordVisible)

    viewModel.onTogglePasswordVisibility()
    assertFalse(viewModel.uiState.value.passwordVisible)
  }
}
