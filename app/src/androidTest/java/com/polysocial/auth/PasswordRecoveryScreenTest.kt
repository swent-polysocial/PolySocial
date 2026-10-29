// Contributors: OpenAI Codex (inject Google entry state after #107 rebase).
// Contributors: OpenAI Codex (offline device recovery navigation and synthetic visual checks,
// #164); Claude (the profile step's ViewModel, #74).
// Contributors: Claude (Opus 5.5, Anthropic) passed a fake session guard to app start, #198;
// Mohamed Khellaf (reviewed and revised).
package com.polysocial.auth

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.*
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.firebase.FakeSessionGuard
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginScreenTestTags
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.profile.ProfileSetupViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import io.mockk.*
import java.io.File
import org.junit.Rule
import org.junit.Test

class PasswordRecoveryScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun validateRecoverRetryAndReturnWithoutSigningIn() {
    val auth = mockk<AuthRepository>()
    every { auth.currentUser() } returns null
    coEvery { auth.sendPasswordResetEmail(any()) } returnsMany
        listOf(
            PasswordResetResult.NetworkError,
            PasswordResetResult.Sent,
        )
    val profiles = mockk<UserProfileRepository>()
    val start =
        AppStartViewModel(
            auth,
            profiles,
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    val profileStep = ProfileSetupViewModel(auth, profiles)
    val signup = SignUpViewModel(auth, EmptyAssociationSignupStore)
    val login = LoginViewModel(auth)
    val recovery = PasswordRecoveryViewModel(auth)
    val verification =
        VerifyEmailViewModel(
            auth,
            mockk<VerificationStore>(relaxed = true),
            VerificationClock { 1000 },
        )
    val welcome = com.polysocial.ui.auth.WelcomeViewModel(auth)
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            signup,
            {},
            login,
            start,
            verification,
            profileStep,
            recoveryViewModel = recovery,
            welcomeViewModel = welcome,
        )
      }
    }
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput("student@epfl.ch")
    compose.onNodeWithTag(LoginScreenTestTags.FORGOT_PASSWORD).performScrollTo().performClick()
    compose.onNodeWithTag(PasswordRecoveryTags.Email).assertTextContains("student@epfl.ch")
    capture("recovery-ready")
    compose.onNodeWithTag(PasswordRecoveryTags.Email).performTextReplacement("invalid")
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).performScrollTo().performClick()
    compose.onNodeWithTag(PasswordRecoveryTags.Error).assertIsDisplayed()
    coVerify(exactly = 0) { auth.sendPasswordResetEmail(any()) }
    compose.onNodeWithTag(PasswordRecoveryTags.Email).performTextReplacement("student@epfl.ch")
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).performClick()
    compose
        .onNodeWithTag(PasswordRecoveryTags.Error)
        .assertTextEquals("Check your connection and try again.")
    capture("recovery-network")
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).performClick()
    compose
        .onNodeWithTag(PasswordRecoveryTags.Confirmation)
        .assertTextEquals(
            compose.activity.getString(
                com.polysocial.R.string.recovery_submitted_message,
                "student@epfl.ch",
            )
        )
    capture("recovery-confirmation")
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).assertTextContains("student@epfl.ch")
    coVerify(exactly = 2) { auth.sendPasswordResetEmail("student@epfl.ch") }
    coVerify(exactly = 0) { auth.logIn(any(), any()) }
    verify(exactly = 0) { auth.logOut() }
  }

  private fun capture(name: String) {
    val path = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
    val image =
        compose.onNodeWithTag(PasswordRecoveryTags.Screen).captureToImage().asAndroidBitmap()
    File(path).mkdirs()
    File(path, "$name.png").outputStream().use {
      image.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
