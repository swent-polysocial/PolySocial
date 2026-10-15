// Contributors: OpenAI Codex (inject Google entry state after #107 rebase).
// Contributors: OpenAI Codex (device logout proof with fakes and verification wiring, #109).
// Contributors: OpenAI Codex (Google sign-in contract compatibility during #106 rebase).
package com.polysocial.ui.profile

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.*
import com.polysocial.model.firebase.*
import com.polysocial.model.user.*
import com.polysocial.resources.C
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.navigation.*
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogoutDeviceTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private var user: AuthUser? = AuthUser("student-one", "student.one@epfl.ch", true)
  private var logouts = 0
  private var clears = 0
  private var exits = 0
  private val auth =
      object : AuthRepository {
        override suspend fun signInWithGoogle(context: Context): GoogleSignInResult =
            error("Unexpected Google sign-in in this test")

        override fun currentUser() = user

        override fun logOut() {
          logouts++
          user = null
        }

        override suspend fun logIn(email: String, password: String): LogInResult =
            error("Unexpected login in this test")

        override suspend fun sendPasswordResetEmail(email: String): PasswordResetResult =
            error("Unexpected password reset in this test")

        override suspend fun sendVerificationEmail(): SendVerificationResult =
            error("Unexpected verification email in this test")

        override suspend fun reloadAndCheckVerified(): VerificationResult =
            if (user?.isEmailVerified == true) VerificationResult.Verified
            else VerificationResult.NotSignedIn

        override suspend fun signUp(
            fullName: String,
            email: String,
            password: String,
        ): SignUpResult = error("Unexpected signup in this test")
      }
  private val profiles =
      object : UserProfileRepository {
        override suspend fun getProfile(uid: String) =
            ProfileResult.Found(
                UserProfile(uid, "student.one@epfl.ch", "Test Student", "IN", "BA1")
            )

        override suspend fun createProfile(profile: UserProfile): CreateProfileResult =
            error("Unexpected profile write in this test")

        override suspend fun updateProfile(profile: UserProfile): UpdateProfileResult =
            error("Unexpected profile write in this test")
      }
  private val cache =
      object : SessionCache {
        override suspend fun clear(): CacheClearResult {
          clears++
          return CacheClearResult.Cleared
        }
      }

  private fun launch() {
    val signup = SignUpViewModel(auth)
    val login = LoginViewModel(auth)
    val start = AppStartViewModel(auth, profiles)
    val profileSetup = ProfileSetupViewModel(auth, profiles)
    val welcome = com.polysocial.ui.auth.WelcomeViewModel(auth)
    compose.setContent {
      PolySocialTheme {
        val verification: VerifyEmailViewModel = viewModel {
          VerifyEmailViewModel(
              auth,
              PreferencesVerificationStore(ApplicationProvider.getApplicationContext()),
              VerificationClock { 1000 },
          )
        }
        AuthFlow(
            signup,
            { exits++ },
            login,
            start,
            verification,
            profileSetup,
            welcomeViewModel = welcome,
        ) { done ->
          AppShell(
              eventsContent = { TabRootScreen(Tab.EVENTS) },
              onLoggedOut = done,
              profileContent = { callback ->
                val vm: ProfileViewModel = viewModel { ProfileViewModel(auth, cache) }
                ProfileRoute(callback, vm)
              },
          )
        }
      }
    }
    compose.onNodeWithTag(C.Tag.nav_item_profile).performClick()
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    compose.onNodeWithTag(ProfileTags.Dialog).assertIsDisplayed()
  }

  @Test
  fun cancelKeepsProfileSignedIn() {
    launch()
    compose.onNodeWithTag(ProfileTags.Cancel).performClick()
    compose.onNodeWithTag(C.Tag.screen_profile).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    assertNotNull(user)
    assertEquals(0, logouts)
    assertEquals(0, clears)
  }

  @Test
  fun confirmLogsOutAndBackCannotReturnToApp() {
    launch()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(user)
    assertEquals(1, logouts)
    assertEquals(1, clears)
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    assertEquals(1, exits)
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
  }
}
