// Contributors: OpenAI Codex (offline Google navigation, generic failure recovery and layout tests
// for #107).
package com.polysocial.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.*
import com.polysocial.model.user.*
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginScreenTestTags
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.profile.ProfileSetupTestTags
import com.polysocial.ui.profile.ProfileSetupViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class GoogleAuthFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val auth = FakeAuthRepository()
  private val profiles = FakeUserProfileRepository()
  private val user = AuthUser("student", "student@epfl.ch", true, "Demo Student")
  private val welcome = WelcomeViewModel(auth)
  private val login = LoginViewModel(auth)
  private val signUp = SignUpViewModel(auth)
  private val profile = ProfileSetupViewModel(auth, profiles)
  private val verification =
      VerifyEmailViewModel(auth, MemoryVerificationStore(), VerificationClock { 1000 })
  private val restore = StateRestorationTester(compose)

  private fun launch(existing: Boolean = false) {
    profiles.getProfileResult =
        if (existing)
            ProfileResult.Found(
                UserProfile(
                    uid = user.uid,
                    email = user.email,
                    displayName = user.displayName!!,
                    section = "IN",
                    year = "BA1",
                )
            )
        else ProfileResult.NotFound
    val start = AppStartViewModel(auth, profiles)
    restore.setContent {
      PolySocialTheme {
        AuthFlow(
            signUp,
            {},
            login,
            start,
            verificationViewModel = verification,
            welcomeViewModel = welcome,
            profileSetupViewModel = profile,
            signedInContent = {
              androidx.compose.material3.Text("Main app", Modifier.testTag("google_main"))
            },
        )
      }
    }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
  }

  private fun google(fromLogin: Boolean) {
    if (fromLogin) compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose
        .onNodeWithTag(if (fromLogin) LoginScreenTestTags.GOOGLE else WelcomeTags.Google)
        .performScrollTo()
        .performClick()
  }

  @Test
  fun welcomeNewStudentPrefillsRealProfileAndSkipsVerification() {
    auth.googleResult = GoogleSignInResult.Success(user)
    launch()
    google(false)
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).assertTextContains("Demo Student")
    compose.onNodeWithTag(ProfileSetupTestTags.EMAIL).assert(hasAnyDescendant(hasText(user.email)))
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertDoesNotExist()
    assertEquals(1, auth.googleCalls)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun loginNewStudentPrefillsRealProfileAndSkipsVerification() {
    auth.googleResult = GoogleSignInResult.Success(user)
    launch()
    google(true)
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).assertTextContains("Demo Student")
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertDoesNotExist()
    assertEquals(0, auth.logInCalls)
  }

  @Test
  fun bothButtonsRouteExistingStudentToMain() {
    auth.googleResult = GoogleSignInResult.Success(user)
    launch(existing = true)
    google(false)
    compose.onNodeWithTag("google_main").assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertDoesNotExist()
    assertEquals(user.uid, auth.currentUser()?.uid)
  }

  @Test
  fun loginExistingStudentRoutesToMain() {
    auth.googleResult = GoogleSignInResult.Success(user)
    launch(existing = true)
    google(true)
    compose.onNodeWithTag("google_main").assertIsDisplayed()
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun welcomeRejectedAccountStaysSignedOutAndBackRestoresWelcome() {
    auth.googleResult =
        GoogleSignInResult.NotEpfl(GoogleAccount("student@example.org", "Demo Student"))
    launch()
    google(false)
    compose.onNodeWithTag(GoogleAuthTags.Account).assertIsDisplayed()
    compose.onNodeWithText("That's not an EPFL account").assertIsDisplayed()
    assertNull(auth.currentUser())
    assertEquals(0, profiles.getProfileCalls)
    compose.onNodeWithTag(GoogleAuthTags.Back).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(welcome.uiState.value.result)
  }

  @Test
  fun loginRejectedAccountUsesSharedErrorAndReturnsToLogin() {
    auth.googleResult = GoogleSignInResult.NotEpfl(GoogleAccount("student@example.org", null))
    launch()
    google(true)
    compose.onNodeWithTag(GoogleAuthTags.Title).assertTextContains("That's not an EPFL account")
    compose.onNodeWithText("student@example.org").assertIsDisplayed()
    assertNull(auth.currentUser())
    assertEquals(0, profiles.getProfileCalls)
    compose.onNodeWithTag(GoogleAuthTags.Back).performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
  }

  @Test
  fun loginUnexpectedFailurePreservesPasswordFormAndDoesNotReadProfiles() {
    auth.googleResult = GoogleSignInResult.UnexpectedError
    launch()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(user.email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("test-password")
    compose.onNodeWithTag(LoginScreenTestTags.GOOGLE).performClick()
    compose.onNodeWithTag(GoogleAuthTags.Retry).assertIsDisplayed()
    compose.onNodeWithTag(GoogleAuthTags.Account).assertDoesNotExist()
    assertNull(auth.currentUser())
    assertEquals(0, profiles.getProfileCalls)
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).assertTextContains(user.email)
    assertEquals("test-password", login.uiState.value.password)
    assertNull(login.uiState.value.google.result)
  }

  @Test
  fun bothCancelledPickersHaveNoError() {
    auth.googleResult = GoogleSignInResult.Cancelled
    launch()
    google(false)
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    google(true)
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(GoogleAuthTags.Error).assertDoesNotExist()
    assertEquals(2, auth.googleCalls)
  }

  @Test
  fun welcomeLoadingLocksNavigationAndBackUntilSuccessfulHandoff() {
    auth.googleGate = CompletableDeferred()
    auth.googleResult = GoogleSignInResult.Success(user)
    launch()
    google(false)
    compose.onNodeWithTag(WelcomeTags.Google).assertIsNotEnabled()
    compose.onNodeWithTag(WelcomeTags.SignUp).assertIsNotEnabled()
    compose.onNodeWithTag(WelcomeTags.LogIn).assertIsNotEnabled()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.runOnIdle { auth.googleGate!!.complete(Unit) }
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).assertTextContains("Demo Student")
    assertEquals(1, auth.googleCalls)
  }

  @Test
  fun loginLoadingLocksBothAuthMethodsAndBack() {
    auth.googleGate = CompletableDeferred()
    auth.googleResult = GoogleSignInResult.Success(user)
    launch(existing = true)
    google(true)
    compose.onNodeWithTag(LoginScreenTestTags.GOOGLE).assertIsNotEnabled()
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).assertIsNotEnabled()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.runOnIdle { auth.googleGate!!.complete(Unit) }
    compose.onNodeWithTag("google_main").assertIsDisplayed()
  }

  @Test
  fun networkFailureRetriesTheSameGoogleFlow() {
    auth.googleResult = GoogleSignInResult.NetworkError
    launch()
    google(false)
    compose.onNodeWithTag(GoogleAuthTags.Retry).assertIsDisplayed()
    compose.runOnIdle { auth.googleResult = GoogleSignInResult.Success(user) }
    compose.onNodeWithTag(GoogleAuthTags.Retry).performClick()
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).assertTextContains("Demo Student")
    assertEquals(2, auth.googleCalls)
  }

  @Test
  fun welcomeErrorSurvivesRecreationAndRepeatedFailureCanBeDismissed() {
    auth.googleResult = GoogleSignInResult.NetworkError
    launch()
    google(false)
    restore.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(GoogleAuthTags.Retry).assertIsEnabled()
    assertEquals(1, auth.googleCalls)
    compose.runOnIdle { auth.googleResult = GoogleSignInResult.UnexpectedError }
    compose.onNodeWithTag(GoogleAuthTags.Retry).performClick()
    compose
        .onNodeWithTag(GoogleAuthTags.Message)
        .assertTextEquals(
            compose.activity.getString(com.polysocial.R.string.google_unexpected_error)
        )
    compose.onNodeWithTag(GoogleAuthTags.Account).assertDoesNotExist()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(welcome.uiState.value.result)
    assertNull(auth.currentUser())
    assertEquals(0, profiles.getProfileCalls)
    assertEquals(2, auth.googleCalls)
  }

  @Test
  fun loginErrorSurvivesRecreationAndRetryCancellationPreservesTheForm() {
    auth.googleResult = GoogleSignInResult.NetworkError
    launch()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(user.email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("test-password")
    compose.onNodeWithTag(LoginScreenTestTags.GOOGLE).performClick()
    restore.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(GoogleAuthTags.Retry).assertIsEnabled()
    assertEquals(1, auth.googleCalls)
    compose.runOnIdle { auth.googleResult = GoogleSignInResult.UnexpectedError }
    compose.onNodeWithTag(GoogleAuthTags.Retry).performClick()
    compose
        .onNodeWithTag(GoogleAuthTags.Message)
        .assertTextEquals(
            compose.activity.getString(com.polysocial.R.string.google_unexpected_error)
        )
    assertFalse(login.uiState.value.canSubmit)
    compose.onNodeWithTag(GoogleAuthTags.Back).performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).assertTextContains(user.email)
    assertTrue(login.uiState.value.canSubmit)
    assertEquals("test-password", login.uiState.value.password)
    compose.onNodeWithTag(LoginScreenTestTags.GOOGLE).performClick()
    compose.runOnIdle { auth.googleResult = GoogleSignInResult.Cancelled }
    compose.onNodeWithTag(GoogleAuthTags.Retry).performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    assertEquals("test-password", login.uiState.value.password)
    assertNull(login.uiState.value.google.result)
    assertEquals(0, profiles.getProfileCalls)
    assertEquals(4, auth.googleCalls)
  }

  @Test
  fun profileStateSurvivesRecreationAndBackSignsOut() {
    auth.googleResult = GoogleSignInResult.Success(user)
    launch()
    google(false)
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).performTextReplacement("Updated Demo")
    restore.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).assertTextContains("Updated Demo")
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(auth.currentUser())
    assertEquals(1, auth.logOutCalls)
    auth.googleResult = GoogleSignInResult.Success(user.copy(displayName = null))
    google(false)
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).assertTextEquals("")
    compose.onNodeWithTag(ProfileSetupTestTags.BACK).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(auth.currentUser())
    assertEquals(2, auth.logOutCalls)
  }

  @Test
  fun profileSaveLocksBackAndOpensMainWithoutAnotherRead() {
    auth.googleResult = GoogleSignInResult.Success(user)
    profiles.createGate = CompletableDeferred()
    launch()
    google(false)
    compose.runOnIdle {
      profile.onSectionChange("IN")
      profile.onYearChange("BA1")
    }
    compose.onNodeWithTag(ProfileSetupTestTags.CONTINUE).performScrollTo().performClick()
    compose.onNodeWithTag(ProfileSetupTestTags.BACK).assertIsNotEnabled()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    assertEquals(user, auth.currentUser())
    compose.runOnIdle { profiles.createGate!!.complete(Unit) }
    compose.onNodeWithTag("google_main").assertIsDisplayed()
    assertEquals(1, profiles.getProfileCalls)
    assertEquals("Demo Student", profiles.createdProfiles.single().displayName)
  }

  @Test
  @Config(qualifiers = "w800dp-h360dp-land")
  fun rejectedIdentityHasTwoColumnsAndBackIsReachable() {
    auth.googleResult =
        GoogleSignInResult.NotEpfl(GoogleAccount("student@example.org", "Demo Student"))
    launch()
    google(false)
    compose.onNodeWithTag(GoogleAuthTags.TwoColumns).assertIsDisplayed()
    val hero = compose.onNodeWithTag(GoogleAuthTags.Hero).fetchSemanticsNode().boundsInRoot
    val guidance = compose.onNodeWithTag(GoogleAuthTags.Guidance).fetchSemanticsNode().boundsInRoot
    assertTrue(hero.right < guidance.left)
    val account = compose.onNodeWithTag(GoogleAuthTags.Account).getUnclippedBoundsInRoot()
    val screen = compose.onNodeWithTag(GoogleAuthTags.Error).getUnclippedBoundsInRoot()
    assertTrue(account.bottom <= screen.bottom)
    compose.onNodeWithTag(GoogleAuthTags.Back).assertIsDisplayed().performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w800dp-h360dp-land")
  fun shortLandscapeKeepsEntireAccountCardVisibleWithoutScrolling() {
    compose.setContent {
      PolySocialTheme {
        // The post-inset viewport captured on the API 37 device at 800 x 360dp.
        Box(Modifier.requiredSize(800.dp, 284.dp).consumeWindowInsets(WindowInsets.safeDrawing)) {
          GoogleAuthErrorScreen(
              GoogleSignInResult.NotEpfl(GoogleAccount("student@example.org", "Demo Student")),
              {},
              {},
          )
        }
      }
    }
    compose.onNodeWithTag(GoogleAuthTags.TwoColumns).assertIsDisplayed()
    val account = compose.onNodeWithTag(GoogleAuthTags.Account).getUnclippedBoundsInRoot()
    val screen = compose.onNodeWithTag(GoogleAuthTags.Error).getUnclippedBoundsInRoot()
    assertTrue(account.bottom <= screen.bottom)
    compose.onNodeWithTag(GoogleAuthTags.Back).assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w360dp-h420dp")
  fun portraitErrorScrollsWithGuidanceAndBack() {
    auth.googleResult =
        GoogleSignInResult.NotEpfl(GoogleAccount("student@example.org", "Demo Student"))
    launch()
    google(false)
    compose.onNodeWithTag(GoogleAuthTags.TwoColumns).assertDoesNotExist()
    compose.onNodeWithTag(GoogleAuthTags.Guidance).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(GoogleAuthTags.Back).performScrollTo().assertIsDisplayed().performClick()
  }
}
