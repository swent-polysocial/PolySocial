// Contributors: OpenAI Codex (tested startup/session routing, profile failure recovery and system
// Back handoffs).
package com.polysocial.auth

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.*
import com.polysocial.model.user.*
import com.polysocial.resources.C
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginScreenTestTags
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val user = AuthUser("test-uid", "test.student@epfl.ch", true, "Test Student")
  private val auth = FakeAuthRepository()
  private val profiles = FakeUserProfileRepository()
  private val signUp = SignUpViewModel(auth)
  private val login = LoginViewModel(auth)
  private val restoration = StateRestorationTester(compose)

  private var exits = 0
  private val verificationModels = mutableListOf<VerifyEmailViewModel>()

  @After
  fun clearVerification() {
    ViewModelStore().apply {
      verificationModels.forEachIndexed { index, model -> put("verification$index", model) }
      clear()
    }
  }

  private fun launch() {
    val start = AppStartViewModel(auth, profiles)
    val verification =
        VerifyEmailViewModel(auth, MemoryVerificationStore(), VerificationClock { 1000 }).also {
          verificationModels.add(it)
        }
    restoration.setContent {
      PolySocialTheme { AuthFlow(signUp, { exits++ }, login, start, verification) }
    }
  }

  @Test
  fun signedOutStartsAtWelcomeAndRestoresTheActiveForm() {
    launch()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performTextInput("Test Student")
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertTextContains("Test Student")
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun unverifiedSessionStartsAtVerificationWithoutReadingProfile() {
    auth.user = user.copy(isEmailVerified = false)
    launch()
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.Screen).assertDoesNotExist()
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun verifiedSessionWithoutProfileStartsAtProfileSetup() {
    auth.user = user
    launch()
    compose.onNodeWithTag("auth_profile_setup").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    assertEquals(user.uid, profiles.lastRequestedUid)
  }

  @Test
  fun verifiedSessionWithProfileStartsAtAppShell() {
    auth.user = user
    profiles.getProfileResult =
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    launch()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun pendingProfileShowsLoadingThenErrorAndRetryResolvesAgain() {
    auth.user = user
    profiles.gate = CompletableDeferred()
    profiles.getProfileResult = ProfileResult.NetworkError
    launch()
    compose.onNodeWithTag("auth_start_loading").assertIsDisplayed()
    compose.runOnIdle { profiles.gate!!.complete(Unit) }
    compose.onNodeWithTag("auth_start_error").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    profiles.getProfileResult = ProfileResult.NotFound
    compose.onNodeWithTag("auth_start_retry").performClick()
    compose.onNodeWithTag("auth_profile_setup").assertIsDisplayed()
    assertEquals(2, profiles.getProfileCalls)
  }

  @Test
  fun verifiedLoginRechecksProfileInsteadOfBypassingSetup() {
    auth.logInResult = LogInResult.Success(user)
    launch()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(user.email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
    compose.onNodeWithTag("auth_profile_setup").assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag("auth_profile_setup").assertIsDisplayed()
    assertEquals(1, auth.logInCalls)
    assertEquals(1, profiles.getProfileCalls)
  }

  private fun pressSystemBack() {
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
  }

  private fun openSignUpAndSubmit() {
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performTextInput("Test Student")
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).performTextInput(user.email)
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Password)).performTextInput("password1")
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.ConfirmPassword))
        .performTextInput("password1")
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
  }

  private fun openLoginAndSubmit() {
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(user.email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
  }

  @Test
  fun systemBackDuringSignUpPreservesVerificationHandoff() {
    auth.signUpGate = CompletableDeferred()
    auth.signUpResult = SignUpResult.Success(user.copy(isEmailVerified = false))
    launch()
    openSignUpAndSubmit()
    compose.onNodeWithTag(SignUpTags.LoadingSpinner, useUnmergedTree = true).assertIsDisplayed()
    pressSystemBack()
    compose.runOnIdle { auth.signUpGate!!.complete(Unit) }
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.Screen).assertDoesNotExist()
    assertEquals(1, auth.signUpCalls)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun systemBackDuringLoginPreservesProfileHandoff() {
    auth.logInGate = CompletableDeferred()
    auth.logInResult = LogInResult.Success(user)
    launch()
    openLoginAndSubmit()
    compose.onNodeWithTag(LoginScreenTestTags.BACK).assertIsNotEnabled()
    compose.onNodeWithTag(LoginScreenTestTags.CREATE_ACCOUNT).performScrollTo().assertIsNotEnabled()
    pressSystemBack()
    compose.runOnIdle { auth.logInGate!!.complete(Unit) }
    compose.onNodeWithTag("auth_profile_setup").assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.Screen).assertDoesNotExist()
    assertEquals(1, auth.logInCalls)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun signUpFailureUnlocksBackAndKeepsTheFormForRetry() {
    auth.signUpGate = CompletableDeferred()
    auth.signUpResult = SignUpResult.NetworkError
    launch()
    openSignUpAndSubmit()
    pressSystemBack()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.runOnIdle { auth.signUpGate!!.complete(Unit) }
    compose.onNodeWithTag(SignUpTags.BackendError).performScrollTo().assertIsDisplayed()
    pressSystemBack()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertTextContains("Test Student")
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsEnabled()
    assertEquals(1, auth.signUpCalls)
  }

  @Test
  fun loginFailureUnlocksBackToTheOriginalSignUpForm() {
    auth.logInGate = CompletableDeferred()
    auth.logInResult = LogInResult.WrongCredentials
    launch()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performTextInput("Test Student")
    compose.onNodeWithTag(SignUpTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(user.email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
    pressSystemBack()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.runOnIdle { auth.logInGate!!.complete(Unit) }
    compose.onNodeWithTag(LoginScreenTestTags.ERROR).performScrollTo().assertIsDisplayed()
    pressSystemBack()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertTextContains("Test Student")
    assertEquals(1, auth.logInCalls)
  }

  @Test
  fun unverifiedLoginCannotLeaveDuringDelayedVerificationHandoff() {
    auth.logInGate = CompletableDeferred()
    auth.logInResult = LogInResult.Success(user.copy(isEmailVerified = false))
    launch()
    openLoginAndSubmit()
    compose.mainClock.autoAdvance = false
    try {
      compose.runOnIdle { auth.logInGate!!.complete(Unit) }
      compose.mainClock.advanceTimeBy(100)
      compose.onNodeWithTag(LoginScreenTestTags.UNVERIFIED_BANNER).assertIsDisplayed()
      compose.onNodeWithTag(LoginScreenTestTags.BACK).assertIsNotEnabled()
      compose
          .onNodeWithTag(LoginScreenTestTags.CREATE_ACCOUNT)
          .performScrollTo()
          .assertIsNotEnabled()
      pressSystemBack()
      compose.mainClock.advanceTimeBy(com.polysocial.ui.login.UNVERIFIED_REDIRECT_DELAY_MS)
      compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    } finally {
      compose.mainClock.autoAdvance = true
    }
    assertEquals(1, auth.logInCalls)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun systemBackFromProfileSetupExitsWithoutChangingTheSession() {
    auth.user = user
    launch()
    compose.onNodeWithTag("auth_profile_setup").assertIsDisplayed()
    pressSystemBack()
    assertEquals(1, exits)
    assertEquals(user, auth.currentUser())
  }

  @Test
  fun systemBackFromProfileErrorExitsWithoutRetrying() {
    auth.user = user
    profiles.getProfileResult = ProfileResult.NetworkError
    launch()
    compose.onNodeWithTag("auth_start_error").assertIsDisplayed()
    pressSystemBack()
    assertEquals(1, exits)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun systemBackDuringProfileLoadingExitsWithoutStartingAnotherRead() {
    auth.user = user
    profiles.gate = CompletableDeferred()
    launch()
    compose.onNodeWithTag("auth_start_loading").assertIsDisplayed()
    pressSystemBack()
    assertEquals(1, exits)
    assertEquals(1, profiles.getProfileCalls)
    compose.runOnIdle { profiles.gate!!.complete(Unit) }
  }

  @Test
  fun backBetweenSignUpCompletionAndItsEffectStillReachesVerification() {
    auth.signUpGate = CompletableDeferred()
    auth.signUpResult = SignUpResult.Success(user.copy(isEmailVerified = false))
    launch()
    openSignUpAndSubmit()
    compose.mainClock.autoAdvance = false
    try {
      compose.runOnIdle { auth.signUpGate!!.complete(Unit) }
      compose.waitUntil { signUp.uiState.value.status is SignUpStatus.SignedUp }
      pressSystemBack()
      compose.mainClock.advanceTimeBy(100)
      compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    } finally {
      compose.mainClock.autoAdvance = true
    }
    assertEquals(1, auth.signUpCalls)
  }
}
