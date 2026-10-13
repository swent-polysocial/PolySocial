// Contributors: OpenAI Codex (offline Hilt recovery routing and real activity recreation, #164).
package com.polysocial.auth

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.model.auth.*
import com.polysocial.model.user.*
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginScreenTestTags
import dagger.hilt.android.testing.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@UninstallModules(AuthModule::class, UserProfileModule::class)
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class PasswordRecoveryInjectionTest {
  private val auth = FakeAuthRepository()
  @BindValue @JvmField val authBinding: AuthRepository = auth
  @BindValue @JvmField val profiles: UserProfileRepository = FakeUserProfileRepository()
  @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun injectedRecoveryRetainsEditsAndConfirmationAcrossActivityRecreation() {
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput("student@epfl.ch")
    compose.onNodeWithTag(LoginScreenTestTags.FORGOT_PASSWORD).performScrollTo().performClick()
    compose
        .onNodeWithTag(PasswordRecoveryTags.Email)
        .performTextReplacement("association@example.org")
    compose.activityRule.scenario.recreate()
    compose.onNodeWithTag(PasswordRecoveryTags.Email).assertTextContains("association@example.org")
    assertEquals(0, auth.resetCalls)
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).performScrollTo().performClick()
    compose.onNodeWithTag(PasswordRecoveryTags.Confirmation).assertIsDisplayed()
    compose.activityRule.scenario.recreate()
    compose.onNodeWithTag(PasswordRecoveryTags.Confirmation).assertIsDisplayed()
    assertEquals(1, auth.resetCalls)
    compose.onNodeWithTag(PasswordRecoveryTags.Submit).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).assertTextContains("student@epfl.ch")
    assertNull(auth.currentUser())
    assertEquals(0, auth.logInCalls)
  }
}
