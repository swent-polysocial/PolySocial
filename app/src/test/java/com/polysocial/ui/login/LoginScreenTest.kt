// Contributors: Claude Opus 5.5 (wrote these tests).
package com.polysocial.ui.login

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.LogInResult
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
  @get:Rule val composeTestRule = createComposeRule()

  private val auth = FakeAuthRepository()
  private val viewModel = LoginViewModel(auth)
  private val verifiedUser = AuthUser(uid = "u1", email = "a@epfl.ch", isEmailVerified = true)

  private var backCalls = 0
  private var loggedInCalls = 0
  private var needsVerificationCalls = 0
  private var createAccountCalls = 0

  private fun string(id: Int) =
      ApplicationProvider.getApplicationContext<Application>().getString(id)

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  /** The node displays [text] in one of its children (the texts sit in child nodes). */
  private fun shows(text: String) = hasAnyDescendant(hasText(text))

  /** The text the password field draws (bullets when masked), not the typed value. */
  private fun displaysText(text: String) =
      SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

  private fun setScreen() {
    composeTestRule.setContent {
      PolySocialTheme {
        LoginScreen(
            onBack = { backCalls++ },
            onLoggedIn = { loggedInCalls++ },
            onNeedsVerification = { needsVerificationCalls++ },
            onCreateAccount = { createAccountCalls++ },
            viewModel = viewModel,
        )
      }
    }
  }

  private fun setContent(state: LoginUiState) {
    composeTestRule.setContent {
      PolySocialTheme {
        LoginContent(
            state = state,
            onBack = {},
            onEmailChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onLogIn = {},
            onCreateAccount = {},
        )
      }
    }
  }

  private fun typeCredentials() {
    node(LoginScreenTestTags.EMAIL).performTextInput("a@epfl.ch")
    node(LoginScreenTestTags.PASSWORD).performTextInput("secret")
  }

  private val filled = LoginUiState(email = "a@epfl.ch", password = "secret")

  // ---- Through the real ViewModel ----

  @Test
  fun wrongCredentials_showsTheErrorMessage() {
    auth.logInResult = LogInResult.WrongCredentials
    setScreen()

    typeCredentials()
    node(LoginScreenTestTags.LOG_IN).performClick()

    node(LoginScreenTestTags.ERROR)
        .assertIsDisplayed()
        .assert(shows(string(R.string.login_wrong_credentials)))
    node(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    assertEquals(1, auth.logInCalls)
    assertEquals(0, loggedInCalls)
    assertEquals(0, needsVerificationCalls)
  }

  @Test
  fun unverifiedLogin_showsTheBannerThenRoutesToVerifyEmailAfterTheDelay() {
    auth.logInResult = LogInResult.Success(verifiedUser.copy(isEmailVerified = false))
    viewModel.onEmailChange("a@epfl.ch")
    viewModel.onPasswordChange("secret")
    setScreen()
    composeTestRule.mainClock.autoAdvance = false

    node(LoginScreenTestTags.LOG_IN).performClick()
    composeTestRule.mainClock.advanceTimeByFrame()

    node(LoginScreenTestTags.UNVERIFIED_BANNER)
        .assertIsDisplayed()
        .assert(shows(string(R.string.login_unverified)))
    node(LoginScreenTestTags.LOG_IN).assert(shows(string(R.string.login_redirecting)))

    composeTestRule.mainClock.advanceTimeBy(UNVERIFIED_REDIRECT_DELAY_MS - 200, true)
    assertEquals(0, needsVerificationCalls)

    composeTestRule.mainClock.advanceTimeBy(400, true)
    assertEquals(1, needsVerificationCalls)
    assertEquals(0, loggedInCalls)
  }

  @Test
  fun verifiedLogin_routesToTheApp() {
    auth.logInResult = LogInResult.Success(verifiedUser)
    setScreen()

    typeCredentials()
    node(LoginScreenTestTags.LOG_IN).performClick()
    composeTestRule.waitForIdle()

    assertEquals(1, loggedInCalls)
    assertEquals(0, needsVerificationCalls)
  }

  @Test
  fun submit_showsLoadingWhileTheAttemptIsInProgress() {
    auth.logInResult = LogInResult.Success(verifiedUser)
    val gate = CompletableDeferred<Unit>()
    auth.logInGate = gate
    setScreen()
    typeCredentials()

    node(LoginScreenTestTags.LOG_IN).performClick()

    node(LoginScreenTestTags.LOG_IN)
        .assert(shows(string(R.string.login_logging_in)))
        .assertIsNotEnabled()
    node(LoginScreenTestTags.EMAIL).assertIsNotEnabled()
    node(LoginScreenTestTags.PASSWORD).assertIsNotEnabled()
    assertEquals(1, auth.logInCalls)
    assertEquals(0, loggedInCalls)

    gate.complete(Unit)
    composeTestRule.waitForIdle()

    assertEquals(1, loggedInCalls)
  }

  @Test
  fun inputsAndSubmitButton_haveTestTags() {
    setScreen()

    listOf(
            LoginScreenTestTags.SCREEN,
            LoginScreenTestTags.BACK,
            LoginScreenTestTags.GOOGLE,
            LoginScreenTestTags.EMAIL,
            LoginScreenTestTags.PASSWORD,
            LoginScreenTestTags.PASSWORD_VISIBILITY,
            LoginScreenTestTags.FORGOT_PASSWORD,
            LoginScreenTestTags.LOG_IN,
            LoginScreenTestTags.CREATE_ACCOUNT,
        )
        .forEach { node(it).assertExists() }
  }

  @Test
  fun emptyFields_disableLogInAndNeverCallTheRepository() {
    setScreen()

    node(LoginScreenTestTags.LOG_IN).assertIsNotEnabled().performClick()
    composeTestRule.waitForIdle()

    assertEquals(0, auth.logInCalls)
  }

  @Test
  fun typedFields_enableLogIn() {
    setScreen()

    typeCredentials()

    node(LoginScreenTestTags.LOG_IN).assertIsEnabled()
  }

  @Test
  fun typing_updatesTheViewModel() {
    setScreen()

    typeCredentials()

    assertEquals("a@epfl.ch", viewModel.uiState.value.email)
    assertEquals("secret", viewModel.uiState.value.password)
  }

  @Test
  fun google_showsNotAvailableAndDoesNotLogIn() {
    setScreen()
    typeCredentials()

    node(LoginScreenTestTags.GOOGLE).performClick()

    composeTestRule.onNodeWithText(string(R.string.not_available_yet)).assertIsDisplayed()
    assertEquals(0, auth.logInCalls)
  }

  @Test
  fun forgotPassword_showsNotAvailableAndDoesNotLogIn() {
    setScreen()
    typeCredentials()

    node(LoginScreenTestTags.FORGOT_PASSWORD).performClick()

    composeTestRule.onNodeWithText(string(R.string.not_available_yet)).assertIsDisplayed()
    assertEquals(0, auth.logInCalls)
  }

  @Test
  fun back_callsOnBack() {
    setScreen()

    node(LoginScreenTestTags.BACK).performClick()

    assertEquals(1, backCalls)
    assertEquals(0, createAccountCalls)
  }

  @Test
  fun createAccount_callsOnCreateAccount() {
    setScreen()

    node(LoginScreenTestTags.CREATE_ACCOUNT).performClick()

    assertEquals(1, createAccountCalls)
    assertEquals(0, backCalls)
  }

  @Test
  fun eyeToggle_revealsAndHidesThePassword() {
    setScreen()
    node(LoginScreenTestTags.PASSWORD).performTextInput("secret")
    val password = node(LoginScreenTestTags.PASSWORD)

    password.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    password.assert(displaysText("••••••"))

    node(LoginScreenTestTags.PASSWORD_VISIBILITY).performClick()
    password.assert(displaysText("secret"))

    node(LoginScreenTestTags.PASSWORD_VISIBILITY).performClick()
    password.assert(displaysText("••••••"))
  }

  // ---- LoginContent per status ----

  @Test
  fun idle_showsLogInLabelAndNoMessages() {
    setContent(filled)

    node(LoginScreenTestTags.LOG_IN).assert(shows(string(R.string.login_log_in)))
    node(LoginScreenTestTags.ERROR).assertDoesNotExist()
    node(LoginScreenTestTags.UNVERIFIED_BANNER).assertDoesNotExist()
  }

  @Test
  fun loading_showsLoggingInAndDisablesEverything() {
    setContent(filled.copy(status = LoginStatus.Loading))

    node(LoginScreenTestTags.LOG_IN)
        .assert(shows(string(R.string.login_logging_in)))
        .assertIsNotEnabled()
    node(LoginScreenTestTags.EMAIL).assertIsNotEnabled()
    node(LoginScreenTestTags.PASSWORD).assertIsNotEnabled()
    node(LoginScreenTestTags.ERROR).assertDoesNotExist()
  }

  @Test
  fun loggedIn_showsLoggingInAndDisablesTheFields() {
    setContent(filled.copy(status = LoginStatus.LoggedIn))

    node(LoginScreenTestTags.LOG_IN)
        .assert(shows(string(R.string.login_logging_in)))
        .assertIsNotEnabled()
    node(LoginScreenTestTags.EMAIL).assertIsNotEnabled()
    node(LoginScreenTestTags.PASSWORD).assertIsNotEnabled()
  }

  @Test
  fun idle_keepsTheFieldsEditable() {
    setContent(filled)

    node(LoginScreenTestTags.EMAIL).assertIsEnabled()
    node(LoginScreenTestTags.PASSWORD).assertIsEnabled()
  }

  @Test
  fun wrongCredentials_showsOnlyTheWrongCredentialsMessage() {
    setContent(filled.copy(status = LoginStatus.WrongCredentials))

    node(LoginScreenTestTags.ERROR).assert(shows(string(R.string.login_wrong_credentials)))
    node(LoginScreenTestTags.ERROR).assert(shows(string(R.string.login_cant_connect)).not())
    node(LoginScreenTestTags.UNVERIFIED_BANNER).assertDoesNotExist()
  }

  @Test
  fun cantConnect_showsTheConnectionMessage() {
    setContent(filled.copy(status = LoginStatus.CantConnect))

    node(LoginScreenTestTags.ERROR).assert(shows(string(R.string.login_cant_connect)))
    node(LoginScreenTestTags.ERROR).assert(shows(string(R.string.login_wrong_credentials)).not())
    node(LoginScreenTestTags.LOG_IN).assert(shows(string(R.string.login_log_in)))
  }

  @Test
  fun unverified_showsTheBannerAndRedirectingLabel() {
    setContent(filled.copy(status = LoginStatus.Unverified))

    node(LoginScreenTestTags.UNVERIFIED_BANNER).assert(shows(string(R.string.login_unverified)))
    node(LoginScreenTestTags.LOG_IN)
        .assert(shows(string(R.string.login_redirecting)))
        .assertIsNotEnabled()
    node(LoginScreenTestTags.ERROR).assertDoesNotExist()
  }

  @Test
  fun emptyEmail_showsThePlaceholder() {
    setContent(LoginUiState())

    composeTestRule.onNodeWithText(string(R.string.login_email_placeholder)).assertIsDisplayed()
  }

  @Test
  fun filledEmail_hidesThePlaceholder() {
    setContent(filled)

    composeTestRule.onNodeWithText(string(R.string.login_email_placeholder)).assertDoesNotExist()
  }
}
