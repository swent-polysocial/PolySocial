// Contributors: OpenAI Codex (GPT-6.1 Sol, medium; tested welcome actions, placeholders and
// scrolling in a short window).
package com.polysocial.ui.auth

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WelcomeScreenTest {
  @get:Rule val compose = createComposeRule()
  private var signUps = 0
  private var logins = 0

  private fun launch(shortWindow: Boolean = false) {
    compose.setContent {
      PolySocialTheme {
        Box(if (shortWindow) Modifier.size(360.dp, 420.dp) else Modifier) {
          WelcomeScreen(onSignUp = { signUps++ }, onLogIn = { logins++ })
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
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().assertIsDisplayed().performClick()
    assertEquals(1, signUps)
    assertEquals(0, logins)
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().assertIsDisplayed().performClick()
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
  fun officialGoogleButtonShowsUnavailableWithoutNavigating() {
    launch()
    compose
        .onNodeWithTag(WelcomeTags.Google)
        .performScrollTo()
        .assertContentDescriptionEquals(message(R.string.google_sign_in))
        .performClick()
    compose.onNodeWithText(message(R.string.not_available_yet)).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(0, signUps)
    assertEquals(0, logins)
  }
}
