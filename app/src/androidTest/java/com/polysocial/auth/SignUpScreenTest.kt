// Contributors: OpenAI Codex (GPT-6.1 Sol, medium; tested tagged validation, backend errors and
// sign-up handoff with MockK; backend failure messages, recovery actions, login navigation and
// official Google placeholder behavior, welcome navigation and startup routing injection).
package com.polysocial.auth

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.polysocial.R
import com.polysocial.model.auth.*
import com.polysocial.model.user.*
import com.polysocial.resources.C
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginScreenTestTags
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignUpScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val repository = mockk<AuthRepository>()
  private val user = AuthUser("test-uid", "student.test@epfl.ch", false, "Test Student")
  private val viewModel = SignUpViewModel(repository)
  private val loginViewModel = LoginViewModel(repository)
  private val profiles = mockk<UserProfileRepository>()
  private lateinit var startViewModel: AppStartViewModel
  private var signedUp = 0
  private var logIn = 0

  @Before
  fun setup() {
    every { repository.currentUser() } returns null
    coEvery { profiles.getProfile(any()) } returns
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    startViewModel = AppStartViewModel(repository, profiles)
    coEvery { repository.signUp(any(), any(), any()) } returns SignUpResult.Success(user)
  }

  private fun launch() {
    compose.setContent {
      PolySocialTheme {
        SignUpRoute(viewModel, onSignedUp = { signedUp++ }, onLogIn = { logIn++ }, onBack = {})
      }
    }
  }

  @Test
  fun officialGoogleButtonShowsUnavailableWithoutSubmittingOrLeavingTheForm() {
    launch()
    input(SignUpField.FullName, "Test Student")
    compose
        .onNodeWithTag(SignUpTags.Google)
        .performScrollTo()
        .assertContentDescriptionEquals(message(R.string.google_sign_in))
        .performClick()
    compose.onNodeWithText(message(R.string.not_available_yet)).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertTextContains("Test Student")
    assertEquals(0, signedUp)
    assertEquals(0, logIn)
    coVerify(exactly = 0) { repository.signUp(any(), any(), any()) }
    coVerify(exactly = 0) { repository.logIn(any(), any()) }
  }

  private fun input(field: SignUpField, value: String) {
    compose.onNodeWithTag(SignUpTags.input(field)).performScrollTo().performTextInput(value)
  }

  private fun fill(email: String = "student.test@epfl.ch", confirmPassword: String = "password1") {
    input(SignUpField.FullName, "Test Student")
    input(SignUpField.Email, email)
    input(SignUpField.Password, "password1")
    input(SignUpField.ConfirmPassword, confirmPassword)
  }

  private fun message(id: Int) =
      InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

  private fun assertPasswordDisplay(field: SignUpField, visible: Boolean) {
    val layouts = mutableListOf<TextLayoutResult>()
    compose.onNodeWithTag(SignUpTags.input(field)).performSemanticsAction(
        SemanticsActions.GetTextLayoutResult
    ) {
      it(layouts)
    }
    assertEquals(
        if (visible) "password1" else "•".repeat(9),
        layouts.single().layoutInput.text.text,
    )
  }

  @Test
  fun nonEpflEmailShowsTaggedInlineErrorAndNeverSubmits() {
    launch()
    fill(email = "student@example.org")
    compose
        .onNodeWithTag(SignUpTags.error(SignUpField.Email))
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextContains(message(R.string.signup_invalid_domain))
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsNotEnabled()
    coVerify(exactly = 0) { repository.signUp(any(), any(), any()) }
    assertEquals(0, signedUp)
  }

  @Test
  fun duplicateEmailShowsDistinctTaggedMessageAndLoginLink() {
    coEvery { repository.signUp(any(), any(), any()) } returns SignUpResult.AlreadyInUse
    launch()
    fill()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose
        .onNodeWithTag(SignUpTags.BackendError)
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextContains(message(R.string.signup_duplicate_email))
    compose
        .onNodeWithTag(SignUpTags.LogInInstead)
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    assertEquals(1, logIn)
    assertEquals(0, signedUp)
    coVerify(exactly = 1) { repository.signUp(any(), any(), any()) }
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.Email))
        .performScrollTo()
        .performTextReplacement("another.test@epfl.ch")
    compose.onNodeWithTag(SignUpTags.BackendError).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.LogInInstead).assertDoesNotExist()
  }

  @Test
  fun passwordVisibilityTogglesIndependentlyAndUpdatesTheAction() {
    launch()
    input(SignUpField.Password, "password1")
    input(SignUpField.ConfirmPassword, "password1")
    val fields = listOf(SignUpField.Password, SignUpField.ConfirmPassword)
    fields.forEach { field ->
      val toggle = compose.onNodeWithTag(SignUpTags.visibility(field))
      assertPasswordDisplay(field, visible = false)
      toggle
          .performScrollTo()
          .assertContentDescriptionEquals(message(R.string.signup_show_password))
          .performClick()
      assertPasswordDisplay(field, visible = true)
      toggle.assertContentDescriptionEquals(message(R.string.signup_hide_password))
      val other = fields.first { it != field }
      assertPasswordDisplay(other, visible = false)
      toggle.performClick()
      assertPasswordDisplay(field, visible = false)
      toggle.assertContentDescriptionEquals(message(R.string.signup_show_password))
    }
  }

  @Test
  fun passwordMismatchShowsTaggedErrorAndDisablesSubmit() {
    launch()
    fill(confirmPassword = "different1")
    compose
        .onNodeWithTag(SignUpTags.error(SignUpField.ConfirmPassword))
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextContains(message(R.string.signup_password_mismatch))
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsNotEnabled()
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.ConfirmPassword))
        .performScrollTo()
        .performTextReplacement("password1")
    compose.onNodeWithTag(SignUpTags.error(SignUpField.ConfirmPassword)).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsEnabled()
    coVerify(exactly = 0) { repository.signUp(any(), any(), any()) }
  }

  @Test
  fun emptyFieldShowsRequiredMessageAfterLosingFocus() {
    launch()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).performClick()
    compose
        .onNodeWithTag(SignUpTags.error(SignUpField.FullName))
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextContains(message(R.string.signup_required))
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsNotEnabled()
  }

  @Test
  fun passwordRequirementsUpdateAndBlockWeakPassword() {
    launch()
    fill()
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.Password))
        .performScrollTo()
        .performTextReplacement("abcdefgh")
    compose
        .onNodeWithTag(SignUpTags.NumberRule)
        .performScrollTo()
        .assert(
            SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                message(R.string.signup_rule_unmet),
            )
        )
    compose
        .onNodeWithTag(SignUpTags.error(SignUpField.Password))
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextContains(message(R.string.signup_password_rules))
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsNotEnabled()
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.Password))
        .performScrollTo()
        .performTextReplacement("password1")
    compose
        .onNodeWithTag(SignUpTags.NumberRule)
        .performScrollTo()
        .assert(
            SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                message(R.string.signup_rule_met),
            )
        )
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsEnabled()
  }

  @Test
  fun networkErrorIsVisibleAndSuccessfulRetryHandsOffOnce() {
    coEvery { repository.signUp(any(), any(), any()) } returns SignUpResult.NetworkError
    launch()
    fill()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose
        .onNodeWithTag(SignUpTags.BackendError)
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextContains(message(R.string.signup_network_error))
    compose.onNodeWithTag(SignUpTags.LogInInstead).assertDoesNotExist()
    coEvery { repository.signUp(any(), any(), any()) } returns SignUpResult.Success(user)
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose.waitUntil { signedUp == 1 }
    compose.onNodeWithTag(SignUpTags.Submit).assertIsNotEnabled()
    assertEquals(1, signedUp)
    coVerify(exactly = 2) { repository.signUp(any(), any(), any()) }
    val signedUpState = viewModel.uiState.value.status as SignUpStatus.SignedUp
    assertEquals(user, signedUpState.user)
  }

  @Test
  fun backendFailuresShowDistinctMessagesAndAppropriateRecovery() {
    launch()
    fill()
    val failures =
        listOf(
            SignUpResult.InvalidPassword to R.string.signup_password_rules,
            SignUpResult.TooManyRequests to R.string.signup_too_many_requests,
            SignUpResult.UnexpectedError to R.string.signup_unknown_error,
            SignUpResult.DisplayNameError to R.string.signup_display_name_error,
        )
    failures.forEachIndexed { index, (failure, messageId) ->
      coEvery { repository.signUp(any(), any(), any()) } returns failure
      compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
      compose
          .onNodeWithTag(SignUpTags.BackendError)
          .performScrollTo()
          .assertIsDisplayed()
          .assertTextContains(message(messageId))
      compose.onNodeWithTag(SignUpTags.LogInInstead).assertDoesNotExist()
      val submit = compose.onNodeWithTag(SignUpTags.Submit).performScrollTo()
      if (failure == SignUpResult.DisplayNameError) submit.assertIsNotEnabled()
      else submit.assertIsEnabled()
      assertEquals("A failed attempt must not navigate to Verify Email", 0, signedUp)
      coVerify(exactly = index + 1) {
        repository.signUp("Test Student", "student.test@epfl.ch", "password1")
      }
    }

    // Editing a partially created account must not enable another account-creation attempt.
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.FullName))
        .performScrollTo()
        .performTextReplacement("Changed Name")
    compose
        .onNodeWithTag(SignUpTags.BackendError)
        .performScrollTo()
        .assertTextContains(message(R.string.signup_display_name_error))
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag(SignUpTags.LogIn).performScrollTo().performClick()
    assertEquals("The existing account must have a login recovery action", 1, logIn)
    assertEquals(0, signedUp)
    coVerify(exactly = failures.size) { repository.signUp(any(), any(), any()) }
  }

  @Test
  fun loadingDisablesFormAndSpinsUntilCompletion() {
    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    coEvery { repository.signUp(any(), any(), any()) } coAnswers
        {
          gate.await()
          SignUpResult.Success(user)
        }
    launch()
    val spinner = compose.onNodeWithTag(SignUpTags.LoadingSpinner, useUnmergedTree = true)
    spinner.assertDoesNotExist()
    fill()
    val submit = compose.onNodeWithTag(SignUpTags.Submit).performScrollTo()
    // Infinite animations need a manual clock before they enter composition.
    compose.mainClock.autoAdvance = false
    try {
      submit.performClick()
      compose.mainClock.advanceTimeByFrame()
      compose.mainClock.advanceTimeByFrame()
      submit.assertIsNotEnabled().assertTextContains(message(R.string.signup_creating))
      spinner.assertIsDisplayed()
      fun pixels(): IntArray {
        val image = spinner.captureToImage()
        return IntArray(image.width * image.height).also { image.readPixels(it) }
      }
      val before = pixels()
      compose.mainClock.advanceTimeBy(250)
      assertFalse("The loading icon must visibly rotate", before.contentEquals(pixels()))
    } finally {
      compose.mainClock.autoAdvance = true
    }
    SignUpField.entries.forEach { compose.onNodeWithTag(SignUpTags.input(it)).assertIsNotEnabled() }
    coVerify(exactly = 1) { repository.signUp(any(), any(), any()) }
    gate.complete(Unit)
    compose.waitUntil { signedUp == 1 }
    spinner.assertDoesNotExist()
  }

  @Test
  fun welcomeIsInitialDestinationAndBothSignUpBackActionsReturnToIt() {
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            viewModel,
            onExit = {},
            loginViewModel = loginViewModel,
            startViewModel = startViewModel,
        )
      }
    }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.Screen).assertDoesNotExist()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.Back).performScrollTo().performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    coVerify(exactly = 0) { repository.signUp(any(), any(), any()) }
    coVerify(exactly = 0) { repository.logIn(any(), any()) }
  }

  @Test
  fun welcomeLoginBackReturnsToWelcomeAndCreateAccountOpensSignUp() {
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            viewModel,
            onExit = {},
            loginViewModel = loginViewModel,
            startViewModel = startViewModel,
        )
      }
    }
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.BACK).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.CREATE_ACCOUNT).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    coVerify(exactly = 0) { repository.signUp(any(), any(), any()) }
    coVerify(exactly = 0) { repository.logIn(any(), any()) }
  }

  @Test
  fun successfulSignUpNavigatesOnlyToVerifyEmailAndBackExits() {
    var exits = 0
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            viewModel,
            onExit = { exits++ },
            loginViewModel = loginViewModel,
            startViewModel = startViewModel,
        )
      }
    }
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    fill()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose
        .onNodeWithTag(SignUpTags.VerifyEmailDestination)
        .assertIsDisplayed()
        .assertTextContains(message(R.string.verify_email_title))
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.Screen).assertDoesNotExist()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.runOnIdle { assertEquals(1, exits) }
  }

  @Test
  fun loginLinkOpensLoginPageAndCreateAccountRestoresTheSignUpForm() {
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            viewModel,
            onExit = {},
            loginViewModel = loginViewModel,
            startViewModel = startViewModel,
        )
      }
    }
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    input(SignUpField.FullName, "Test Student")
    compose.onNodeWithTag(SignUpTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.Screen).assertDoesNotExist()
    compose.onNodeWithTag(LoginScreenTestTags.CREATE_ACCOUNT).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertTextContains("Test Student")
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.BACK).performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    coVerify(exactly = 0) { repository.signUp(any(), any(), any()) }
    coVerify(exactly = 0) { repository.logIn(any(), any()) }
  }

  @Test
  fun loginFailureStaysOnLoginPageAndVerifiedRetryOpensAppShell() {
    coEvery { repository.logIn(any(), any()) } returns LogInResult.WrongCredentials
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            viewModel,
            onExit = {},
            loginViewModel = loginViewModel,
            startViewModel = startViewModel,
        )
      }
    }
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput("student.test@epfl.ch")
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.ERROR).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertDoesNotExist()

    every { repository.currentUser() } returns user.copy(isEmailVerified = true)
    coEvery { repository.logIn(any(), any()) } returns
        LogInResult.Success(user.copy(isEmailVerified = true))
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.Screen).assertDoesNotExist()
    coVerify(exactly = 2) { repository.logIn("student.test@epfl.ch", "password1") }
    coVerify(exactly = 0) { repository.signUp(any(), any(), any()) }
  }

  @Test
  fun unverifiedLoginOpensVerificationInsteadOfAppShell() {
    coEvery { repository.logIn(any(), any()) } returns LogInResult.Success(user)
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            viewModel,
            onExit = {},
            loginViewModel = loginViewModel,
            startViewModel = startViewModel,
        )
      }
    }
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput("student.test@epfl.ch")
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
    compose.mainClock.autoAdvance = false
    try {
      compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
      compose.mainClock.advanceTimeByFrame()
      compose.onNodeWithTag(LoginScreenTestTags.UNVERIFIED_BANNER).assertIsDisplayed()
      compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertDoesNotExist()
      compose.mainClock.advanceTimeBy(1_700, ignoreFrameDuration = true)
      compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
      compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
      compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
    } finally {
      compose.mainClock.autoAdvance = true
    }
    coVerify(exactly = 1) { repository.logIn("student.test@epfl.ch", "password1") }
    coVerify(exactly = 0) { repository.signUp(any(), any(), any()) }
  }

  @Test
  fun duplicateEmailLinkOpensLoginPageAndBackReturnsToForm() {
    coEvery { repository.signUp(any(), any(), any()) } returns SignUpResult.AlreadyInUse
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            viewModel,
            onExit = {},
            loginViewModel = loginViewModel,
            startViewModel = startViewModel,
        )
      }
    }
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    fill()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.LogInInstead).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertDoesNotExist()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.BackendError).performScrollTo().assertIsDisplayed()
  }
}
