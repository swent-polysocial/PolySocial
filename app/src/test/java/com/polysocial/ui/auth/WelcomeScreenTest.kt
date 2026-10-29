// Contributors: OpenAI Codex (Google action regression for #107).
// Contributors: OpenAI Codex (GPT-6.1 Sol, medium; tested welcome actions, placeholders and
// scrolling in a short window, landscape action visibility and privacy-copy regression).
// Contributors: Claude (Opus 5.5, Anthropic) tested the one-off "Account deleted" message, #207;
// Mohamed Khellaf (reviewed and revised).
package com.polysocial.ui.auth

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class WelcomeScreenTest {
  @get:Rule val compose = createComposeRule()
  private var signUps = 0
  private var logins = 0
  private var googleAttempts = 0

  private fun launch(shortWindow: Boolean = false) {
    compose.setContent {
      PolySocialTheme {
        Box(if (shortWindow) Modifier.size(360.dp, 420.dp) else Modifier) {
          WelcomeScreen(
              onSignUp = { signUps++ },
              onLogIn = { logins++ },
              onGoogle = { googleAttempts++ },
          )
        }
      }
    }
  }

  private fun message(id: Int) =
      ApplicationProvider.getApplicationContext<Application>().getString(id)

  @Test
  fun emailAndLoginActionsRemainReachableInShortWindow() {
    launch(shortWindow = true)
    compose.onNodeWithText(message(R.string.welcome_title)).assertExists()
    compose.onNodeWithText("By continuing you agree", substring = true).assertDoesNotExist()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().assertIsDisplayed().performClick()
    assertEquals(1, signUps)
    assertEquals(0, logins)
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().assertIsDisplayed().performClick()
    assertEquals(1, signUps)
    assertEquals(1, logins)
  }

  @Test
  @Config(qualifiers = "w800dp-h360dp-land")
  fun landscapeShowsAccountActionsWithoutScrolling() {
    launch()
    compose.onNodeWithText(message(R.string.welcome_title)).assertIsDisplayed()
    compose.onNodeWithText(message(R.string.welcome_subtitle)).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.Google).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.SignUp).assertIsDisplayed().performClick()
    compose.onNodeWithTag(WelcomeTags.LogIn).assertIsDisplayed().performClick()
    compose.onNodeWithTag(WelcomeTags.Association).assertIsDisplayed()
    assertEquals(1, signUps)
    assertEquals(1, logins)
  }

  @Test
  fun associationShowsUnavailableWithoutNavigating() {
    launch(shortWindow = true)
    compose.onNodeWithTag(WelcomeTags.Association).performScrollTo().performClick()
    compose.onNodeWithText(message(R.string.not_available_yet)).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(0, signUps)
    assertEquals(0, logins)
  }

  @Test
  fun officialGoogleButtonStartsSharedActionWithoutEmailNavigation() {
    launch()
    compose
        .onNodeWithTag(WelcomeTags.Google)
        .performScrollTo()
        .assertContentDescriptionEquals(message(R.string.google_sign_in))
        .performClick()
    assertEquals(1, googleAttempts)
    compose.onNodeWithText(message(R.string.not_available_yet)).assertDoesNotExist()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(0, signUps)
    assertEquals(0, logins)
  }

  @Test
  fun accountDeletedIsShownOnceAndNotAfterReturning() {
    val welcome = WelcomeViewModel(FakeAuthRepository())
    val showWelcome = mutableStateOf(true)
    welcome.onAccountDeleted()
    assertTrue(welcome.accountDeleted.value)
    val deletedText = message(R.string.delete_account_done)
    compose.setContent {
      PolySocialTheme {
        val deleted by welcome.accountDeleted.collectAsStateWithLifecycle()
        if (showWelcome.value) {
          WelcomeScreen(
              onSignUp = {},
              onLogIn = {},
              message = if (deleted) deletedText else null,
              onMessageShown = welcome::onAccountDeletedShown,
          )
        }
      }
    }
    compose.onNodeWithText(deletedText).assertIsDisplayed()

    compose.runOnIdle { showWelcome.value = false }
    compose.waitForIdle()
    assertFalse(welcome.accountDeleted.value)
    compose.runOnIdle { showWelcome.value = true }

    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithText(deletedText).assertDoesNotExist()
  }
}
