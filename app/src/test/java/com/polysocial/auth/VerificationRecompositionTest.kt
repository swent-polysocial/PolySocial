// Contributors: OpenAI Codex (replacement-session callback and lifecycle-observer regression
// test for verification navigation, #31).
// Contributors: OpenAI Codex (preserved per-session injection after PR #168 rebase).
// Contributors: Claude (Opus 5.5, Anthropic) passed a fake session guard to app start, #198;
// Mohamed Khellaf (reviewed and revised).
package com.polysocial.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.SignUpField
import com.polysocial.model.auth.VerificationClock
import com.polysocial.model.auth.VerificationTiming
import com.polysocial.model.firebase.FakeSessionGuard
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.ui.auth.AuthFlow
import com.polysocial.ui.auth.SignUpTags
import com.polysocial.ui.auth.SignUpUiState
import com.polysocial.ui.auth.SignUpViewModel
import com.polysocial.ui.auth.VerifyEmailTags
import com.polysocial.ui.auth.VerifyEmailViewModel
import com.polysocial.ui.auth.WelcomeTags
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.profile.ProfileSetupViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VerificationRecompositionTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val models = mutableListOf<ViewModel>()

  private class Session(
      val auth: FakeAuthRepository,
      val signup: SignUpViewModel,
      val login: LoginViewModel,
      val start: AppStartViewModel,
      val verification: VerifyEmailViewModel,
      val welcome: com.polysocial.ui.auth.WelcomeViewModel,
      val profile: com.polysocial.ui.profile.ProfileSetupViewModel,
  )

  private fun session(uid: String): Session {
    val user = AuthUser(uid, "$uid@epfl.ch", false)
    val auth = FakeAuthRepository(user)
    val store = MemoryVerificationStore().apply { timings[uid] = VerificationTiming(1000, 1000) }
    return Session(
            auth,
            SignUpViewModel(auth, EmptyAssociationSignupStore),
            LoginViewModel(auth),
            AppStartViewModel(
                auth,
                FakeUserProfileRepository(),
                associationSignup = EmptyAssociationSignupStore,
                associations = FakeAssociationRepository(),
                sessions = FakeSessionGuard(),
            ),
            VerifyEmailViewModel(auth, store, VerificationClock { 100_000 }),
            com.polysocial.ui.auth.WelcomeViewModel(auth),
            com.polysocial.ui.profile.ProfileSetupViewModel(auth, FakeUserProfileRepository()),
        )
        .also {
          models.addAll(
              listOf(it.signup, it.login, it.start, it.verification, it.welcome, it.profile)
          )
          it.signup.update(SignUpField.FullName, "Test Student")
          it.login.onEmailChange(user.email)
        }
  }

  @After
  fun clearModels() {
    ViewModelStore().apply {
      models.forEachIndexed { index, model -> put("model$index", model) }
      clear()
    }
  }

  @Test
  fun replacementSessionOwnsResumeAndActionsWithoutResettingPreviousForms() {
    val original = session("original.test")
    val active = mutableStateOf(original)
    compose.setContent {
      val current = active.value
      PolySocialTheme {
        AuthFlow(
            current.signup,
            {},
            current.login,
            current.start,
            current.verification,
            welcomeViewModel = current.welcome,
            profileSetupViewModel = current.profile,
        )
      }
    }
    compose.onNodeWithTag(VerifyEmailTags.Email).assertTextEquals("original.test@epfl.ch")
    val originalChecks = original.auth.checkCalls
    val replacement = session("replacement.test")
    compose.runOnIdle { active.value = replacement }
    compose.onNodeWithTag(VerifyEmailTags.Email).assertTextEquals("replacement.test@epfl.ch")

    val replacementChecks = replacement.auth.checkCalls
    compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    compose.waitForIdle()
    assertEquals(originalChecks, original.auth.checkCalls)
    assertEquals(replacementChecks + 1, replacement.auth.checkCalls)

    compose.onNodeWithTag(VerifyEmailTags.Continue).performScrollTo().performClick()
    assertEquals(replacementChecks + 2, replacement.auth.checkCalls)
    compose.waitUntil { replacement.verification.uiState.value.canResend }
    compose
        .onNodeWithTag(VerifyEmailTags.Resend)
        .performScrollTo()
        .assertIsEnabled()
        .performSemanticsAction(SemanticsActions.OnClick) { it() }
    assertEquals(1, replacement.auth.sendCalls)
    assertEquals(0, original.auth.sendCalls)
    compose.waitUntil(timeoutMillis = 10_000) {
      replacement.verification.uiState.value.snackbar == null
    }

    compose.onNodeWithTag(VerifyEmailTags.Back).performScrollTo().performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(replacement.auth.currentUser())
    assertEquals(1, replacement.auth.logOutCalls)
    assertEquals(SignUpUiState(), replacement.signup.uiState.value)
    assertEquals("", replacement.login.uiState.value.email)
    assertEquals(0, original.auth.logOutCalls)
    assertEquals("Test Student", original.signup.uiState.value.form.fullName)
    assertEquals("original.test@epfl.ch", original.login.uiState.value.email)

    val correction = session("correction.test")
    compose.runOnIdle { active.value = correction }
    compose.onNodeWithTag(VerifyEmailTags.Email).assertTextEquals("correction.test@epfl.ch")
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    assertNull(correction.auth.currentUser())
    assertEquals(1, correction.auth.logOutCalls)
    assertEquals(SignUpUiState(), correction.signup.uiState.value)
    assertEquals("", correction.login.uiState.value.email)
    assertEquals(1, replacement.auth.logOutCalls)
    assertEquals("Test Student", original.signup.uiState.value.form.fullName)
  }
}
