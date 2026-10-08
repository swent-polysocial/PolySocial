// Contributors: OpenAI Codex (tested startup/session routing and profile failure recovery).
package com.polysocial.auth

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthFlowTest {
  @get:Rule val compose = createComposeRule()
  private val user = AuthUser("test-uid", "test.student@epfl.ch", true, "Test Student")
  private val auth = FakeAuthRepository()
  private val profiles = FakeUserProfileRepository()
  private val signUp = SignUpViewModel(auth)
  private val login = LoginViewModel(auth)
  private val restoration = StateRestorationTester(compose)

  private fun launch() {
    val start = AppStartViewModel(auth, profiles)
    restoration.setContent { PolySocialTheme { AuthFlow(signUp, {}, login, start) } }
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
}
