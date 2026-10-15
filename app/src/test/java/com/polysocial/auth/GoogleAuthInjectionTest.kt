// Contributors: OpenAI Codex (offline real-activity Google recovery and profile routing for #107).
package com.polysocial.auth

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.R
import com.polysocial.model.auth.*
import com.polysocial.model.user.*
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginScreenTestTags
import com.polysocial.ui.profile.ProfileSetupTestTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Exercises the production Hilt entry path; only external repositories are replaced. */
@HiltAndroidTest
@UninstallModules(AuthModule::class, UserProfileModule::class)
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class GoogleAuthInjectionTest {
  private val auth = FakeAuthRepository()
  private val profiles = FakeUserProfileRepository()
  @BindValue @JvmField val authBinding: AuthRepository = auth
  @BindValue @JvmField val profileBinding: UserProfileRepository = profiles
  @BindValue @JvmField val verificationStore: VerificationStore = MemoryVerificationStore()
  @BindValue @JvmField val verificationClock: VerificationClock = VerificationClock { 1000 }
  @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun injectedEntryScreensRecoverAndShareProfileRouting() {
    auth.googleResult = GoogleSignInResult.NetworkError
    compose.onNodeWithTag(WelcomeTags.Google).performScrollTo().performClick()
    compose
        .onNodeWithTag(GoogleAuthTags.Message)
        .assertTextEquals(compose.activity.getString(R.string.google_network_error))
    compose.runOnIdle { auth.googleResult = GoogleSignInResult.UnexpectedError }
    compose.onNodeWithTag(GoogleAuthTags.Retry).performClick()
    compose
        .onNodeWithTag(GoogleAuthTags.Message)
        .assertTextEquals(compose.activity.getString(R.string.google_unexpected_error))
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()

    val user = AuthUser("student", "student@epfl.ch", true, "Demo Student")
    compose.runOnIdle { auth.googleResult = GoogleSignInResult.Success(user) }
    compose.onNodeWithTag(WelcomeTags.Google).performClick()
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).assertTextContains("Demo Student")
    compose.onNodeWithTag(ProfileSetupTestTags.BACK).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(auth.currentUser())

    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(user.email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("test-password")
    compose.runOnIdle { auth.googleResult = GoogleSignInResult.NetworkError }
    compose.onNodeWithTag(LoginScreenTestTags.GOOGLE).performClick()
    compose.runOnIdle { auth.googleResult = GoogleSignInResult.UnexpectedError }
    compose.onNodeWithTag(GoogleAuthTags.Retry).performClick()
    compose
        .onNodeWithTag(GoogleAuthTags.Message)
        .assertTextEquals(compose.activity.getString(R.string.google_unexpected_error))
    compose.onNodeWithTag(GoogleAuthTags.Back).performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).assertTextContains(user.email)
    compose.runOnIdle { auth.googleResult = GoogleSignInResult.NetworkError }
    compose.onNodeWithTag(LoginScreenTestTags.GOOGLE).performClick()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    assertEquals(0, auth.logInCalls)

    compose.runOnIdle { auth.googleResult = GoogleSignInResult.Success(user) }
    compose.onNodeWithTag(LoginScreenTestTags.GOOGLE).performClick()
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).assertTextContains("Demo Student")
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertDoesNotExist()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(auth.currentUser())
    assertEquals(7, auth.googleCalls)
    assertEquals(2, auth.logOutCalls)
    assertEquals(2, profiles.getProfileCalls)
  }
}
