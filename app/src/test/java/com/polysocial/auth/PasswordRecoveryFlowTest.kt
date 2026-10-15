// Contributors: OpenAI Codex (offline recovery navigation, privacy and responsive UI for #164);
// Claude (the profile step's ViewModel, #74).
package com.polysocial.auth

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.*
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginScreenTestTags
import com.polysocial.ui.login.LoginViewModel
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
class PasswordRecoveryFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val auth = FakeAuthRepository()
  private val recovery = PasswordRecoveryViewModel(auth)
  private val login = LoginViewModel(auth)
  private val signup = SignUpViewModel(auth)
  private val restore = StateRestorationTester(compose)

  private fun launch() {
    val start = AppStartViewModel(auth, FakeUserProfileRepository())
    val verification =
        VerifyEmailViewModel(auth, MemoryVerificationStore(), VerificationClock { 1000 })
    val profileStep = ProfileSetupViewModel(auth, FakeUserProfileRepository())
    restore.setContent {
      PolySocialTheme {
        AuthFlow(signup, {}, login, start, verification, profileStep, recoveryViewModel = recovery)
      }
    }
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput("association@example.org")
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("preserved-password")
    compose.onNodeWithTag(LoginScreenTestTags.FORGOT_PASSWORD).performScrollTo().performClick()
    compose.onNodeWithTag(PasswordRecoveryTags.Email).assertTextContains("association@example.org")
  }

  @Test
  fun recoveryValidatesThenReturnsToUnchangedLoginAfterPrivateConfirmation() {
    launch()
    compose.onNodeWithTag(PasswordRecoveryTags.Email).performTextReplacement("bad")
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).performScrollTo().performClick()
    compose
        .onNodeWithTag(PasswordRecoveryTags.Error)
        .assertTextEquals(compose.activity.getString(R.string.recovery_invalid_email))
    assertEquals(0, auth.resetCalls)
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).assertIsNotEnabled()
    compose
        .onNodeWithTag(PasswordRecoveryTags.Email)
        .performTextReplacement("association@example.org")
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).performClick()
    compose
        .onNodeWithTag(PasswordRecoveryTags.Confirmation)
        .assertTextEquals(
            compose.activity.getString(
                R.string.recovery_submitted_message,
                "association@example.org",
            )
        )
    compose.onNodeWithTag(PasswordRecoveryTags.Email).assertDoesNotExist()
    restore.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(PasswordRecoveryTags.Confirmation).assertIsDisplayed()
    assertEquals(1, auth.resetCalls)
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).assertTextContains("association@example.org")
    assertEquals("preserved-password", login.uiState.value.password)
    assertNull(auth.currentUser())
    assertEquals(0, auth.logInCalls)
    compose.onNodeWithTag(LoginScreenTestTags.FORGOT_PASSWORD).performScrollTo().performClick()
    compose.onNodeWithTag(PasswordRecoveryTags.Email).assertIsDisplayed()
    assertNull(recovery.uiState.value.result)
  }

  @Test
  fun pendingRecoveryLocksAllBackPathsAndSurvivesRecreation() {
    auth.resetGate = CompletableDeferred()
    launch()
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).performScrollTo().performClick()
    compose
        .onNodeWithTag(PasswordRecoveryTags.Loading, useUnmergedTree = true)
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithTag(PasswordRecoveryTags.Email).assertIsNotEnabled()
    compose.onNodeWithTag(PasswordRecoveryTags.Back).assertIsNotEnabled()
    compose.onNodeWithTag(PasswordRecoveryTags.BackLink).assertIsNotEnabled()
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).assertIsNotEnabled()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    restore.emulateSavedInstanceStateRestore()
    compose
        .onNodeWithTag(PasswordRecoveryTags.Loading, useUnmergedTree = true)
        .performScrollTo()
        .assertIsDisplayed()
    assertEquals(1, auth.resetCalls)
    compose.runOnIdle { auth.resetGate!!.complete(Unit) }
    compose.onNodeWithTag(PasswordRecoveryTags.Confirmation).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
  }

  @Test
  fun serviceFailuresKeepEmailAndCanRetryOrReturnByEitherBackAction() {
    launch()
    for ((result, text) in
        listOf(
            PasswordResetResult.NetworkError to R.string.recovery_network,
            PasswordResetResult.TooManyRequests to R.string.recovery_too_many,
            PasswordResetResult.UnexpectedError to R.string.recovery_unexpected,
        )) {
      compose.runOnIdle { auth.resetResult = result }
      compose.onNodeWithTag(PasswordRecoveryTags.Submit).performScrollTo().performClick()
      compose
          .onNodeWithTag(PasswordRecoveryTags.Error)
          .assertTextEquals(compose.activity.getString(text))
      compose
          .onNodeWithTag(PasswordRecoveryTags.Email)
          .assertTextContains("association@example.org")
      compose.onNodeWithTag(PasswordRecoveryTags.Submit).assertIsEnabled()
    }
    compose.onNodeWithTag(PasswordRecoveryTags.BackLink).performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.FORGOT_PASSWORD).performScrollTo().performClick()
    compose.onNodeWithTag(PasswordRecoveryTags.Back).performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    assertEquals(3, auth.resetCalls)
  }

  @Test
  @Config(qualifiers = "w800dp-h360dp-land")
  fun shortLandscapeKeepsRequestAndBackReachable() {
    launch()
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(PasswordRecoveryTags.BackLink).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
  }
}
