// Contributors: OpenAI Codex (offline Google auth device tests, generic failure recovery and
// synthetic screenshots for #107).
package com.polysocial.auth

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.auth.GoogleAccount
import com.polysocial.model.auth.GoogleSignInResult
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginScreenTestTags
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.profile.ProfileSetupTestTags
import com.polysocial.ui.profile.ProfileSetupViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GoogleSignInScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val auth = mockk<AuthRepository>()
  private val profiles = mockk<UserProfileRepository>()
  private val demo = AuthUser("demo", "student@epfl.ch", true, "Demo Student")
  private var user: AuthUser? = null
  private var result: GoogleSignInResult = GoogleSignInResult.Cancelled

  @Before
  fun setup() {
    every { auth.currentUser() } answers { user }
    every { auth.logOut() } answers { user = null }
    coEvery { auth.signInWithGoogle(any()) } coAnswers
        {
          user = (result as? GoogleSignInResult.Success)?.user
          result
        }
    coEvery { profiles.getProfile(any()) } returns ProfileResult.NotFound
  }

  private fun launch(existing: Boolean = false) {
    if (existing)
        coEvery { profiles.getProfile(demo.uid) } returns
            ProfileResult.Found(UserProfile(demo.uid, demo.email, "Demo Student", "IN", "BA1"))
    val start = AppStartViewModel(auth, profiles)
    val signup = SignUpViewModel(auth, EmptyAssociationSignupStore)
    val login = LoginViewModel(auth)
    val welcome = WelcomeViewModel(auth)
    val profile = ProfileSetupViewModel(auth, profiles)
    val verification =
        com.polysocial.ui.auth.VerifyEmailViewModel(
            auth,
            mockk(),
            com.polysocial.model.auth.VerificationClock { 1000 },
        )
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            signup,
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

  private fun clickGoogle(login: Boolean) {
    if (login) compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose
        .onNodeWithTag(if (login) LoginScreenTestTags.GOOGLE else WelcomeTags.Google)
        .performScrollTo()
        .performClick()
  }

  @Test
  fun welcomeNewStudentHasGoogleNameAndEmail() {
    result = GoogleSignInResult.Success(demo)
    launch()
    clickGoogle(false)
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).assertTextContains("Demo Student")
    compose.onNodeWithTag(ProfileSetupTestTags.EMAIL).assert(hasAnyDescendant(hasText(demo.email)))
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertDoesNotExist()
  }

  @Test
  fun loginNewStudentHasGoogleName() {
    result = GoogleSignInResult.Success(demo)
    launch()
    clickGoogle(true)
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).assertTextContains("Demo Student")
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertDoesNotExist()
  }

  @Test
  fun welcomeExistingStudentOpensMain() {
    result = GoogleSignInResult.Success(demo)
    launch(existing = true)
    clickGoogle(false)
    compose.onNodeWithTag("google_main").assertIsDisplayed()
  }

  @Test
  fun loginExistingStudentOpensMain() {
    result = GoogleSignInResult.Success(demo)
    launch(existing = true)
    clickGoogle(true)
    compose.onNodeWithTag("google_main").assertIsDisplayed()
  }

  @Test
  fun rejectedAccountStaysSignedOutAndBackRestoresWelcome() {
    result = GoogleSignInResult.NotEpfl(GoogleAccount("student@example.org", "Demo Student"))
    launch()
    clickGoogle(false)
    compose.onNodeWithText("That's not an EPFL account").assertIsDisplayed()
    assertNull(user)
    capture("google-not-epfl")
    compose.onNodeWithTag(GoogleAuthTags.Back).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    coVerify(exactly = 0) { profiles.getProfile(any()) }
  }

  @Test
  fun unexpectedFailureOffersRetryAndRoutesAfterSuccess() {
    result = GoogleSignInResult.UnexpectedError
    launch()
    clickGoogle(true)
    compose.onNodeWithTag(GoogleAuthTags.Retry).assertIsDisplayed()
    compose.onNodeWithTag(GoogleAuthTags.Account).assertDoesNotExist()
    assertNull(user)
    capture("google-unexpected-error")
    coVerify(exactly = 0) { profiles.getProfile(any()) }
    result = GoogleSignInResult.Success(demo)
    compose.onNodeWithTag(GoogleAuthTags.Retry).performClick()
    compose.onNodeWithTag(ProfileSetupTestTags.FULL_NAME).assertTextContains("Demo Student")
    assertEquals(demo.uid, user?.uid)
  }

  @Test
  fun bothPickerCancellationsHaveNoError() {
    launch()
    clickGoogle(false)
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    clickGoogle(true)
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(GoogleAuthTags.Error).assertDoesNotExist()
    assertNull(user)
  }

  private fun capture(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val image = compose.onNodeWithTag(GoogleAuthTags.Error).captureToImage().asAndroidBitmap()
    val directory =
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let {
          File(it)
        } ?: File(context.getExternalFilesDir(null), "screenshots")
    directory.mkdirs()
    File(directory, "$name.png").outputStream().use {
      image.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
