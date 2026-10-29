// Contributors: Claude Opus 5.5 (wrote these tests); Claude (reset after a sign-out, #74;
// association emails, #208).
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
  fun logInWhileRedirectingToVerifyEmail_doesNotCallTheRepositoryAgain() = runTest {
    logInWith(LogInResult.Success(verifiedUser.copy(isEmailVerified = false)))

    assertFalse(viewModel.uiState.value.canSubmit)
    viewModel.onLogIn()
    advanceUntilIdle()

    assertEquals(1, auth.logInCalls)
    assertEquals(LoginStatus.Unverified, status())
  }

  @Test
  fun logInAfterLoggedIn_doesNotCallTheRepositoryAgain() = runTest {
    logInWith(LogInResult.Success(verifiedUser))

    assertFalse(viewModel.uiState.value.canSubmit)
    viewModel.onLogIn()
    advanceUntilIdle()

    assertEquals(1, auth.logInCalls)
    assertEquals(LoginStatus.LoggedIn, status())
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

  @Test
  fun reset_afterALogIn_startsAnEmptyIdleForm() {
    assertEquals(LoginStatus.LoggedIn, logInWith(LogInResult.Success(verifiedUser)))

    viewModel.reset()

    // A kept LoggedIn status would make the next visit navigate away at once.
    assertEquals(LoginUiState(), viewModel.uiState.value)
  }

  private val association =
      AuthUser(uid = "club-uid", email = "club@example.org", isEmailVerified = true)

  private fun logInAsAssociation(result: LogInResult): LoginStatus {
    auth.logInResult = result
    fillForm(email = "  club@example.org ")
    viewModel.onLogIn()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    return status()
  }

  @Test
  fun aNonEpflAssociationEmail_logsIn_withTheTrimmedEmail() {
    // Log in has no EPFL-domain check: routing decides by account type afterwards (#208).
    assertEquals(LoginStatus.LoggedIn, logInAsAssociation(LogInResult.Success(association)))
    assertEquals(1, auth.logInCalls)
    assertEquals("club@example.org", auth.lastLogInEmail)
  }

  @Test
  fun aNonEpflEmail_withWrongCredentials_keepsTheRecoverableState() {
    assertEquals(LoginStatus.WrongCredentials, logInAsAssociation(LogInResult.WrongCredentials))
    assertTrue(viewModel.uiState.value.canSubmit)
  }

  @Test
  fun aNonEpflEmail_offline_showsCantConnect_andCanRetry() {
    assertEquals(LoginStatus.CantConnect, logInAsAssociation(LogInResult.NetworkError))
    assertTrue(viewModel.uiState.value.canSubmit)

    auth.logInResult = LogInResult.Success(association)
    viewModel.onLogIn()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    assertEquals(LoginStatus.LoggedIn, status())
    assertEquals(2, auth.logInCalls)
  }

  @Test
  fun anAssociationWithAnUnverifiedEmail_goesToVerification() {
    assertEquals(
        LoginStatus.Unverified,
        logInAsAssociation(LogInResult.Success(association.copy(isEmailVerified = false))),
    )
  }
}
