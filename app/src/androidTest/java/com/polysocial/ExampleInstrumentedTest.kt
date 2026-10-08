// Contributors: Claude (app-shell smoke test, #41); OpenAI Codex
// (GPT-6.1 Sol, medium; welcome entry/navigation test and preserved shell smoke test).
package com.polysocial

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import com.polysocial.screen.MainScreen
import com.polysocial.ui.auth.SignUpTags
import com.polysocial.ui.auth.WelcomeTags
import com.polysocial.ui.navigation.AppShell
import com.polysocial.ui.theme.PolySocialTheme
import io.github.kakaocup.compose.node.element.ComposeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the authentication entry and the signed-in shell independently of future routing. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest : TestCase() {
  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun appStartsOnWelcomeAndEmailActionOpensSignUp() {
    composeTestRule.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().assertIsEnabled()
    composeTestRule.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(SignUpTags.Submit).assertIsNotEnabled()
  }

  @Test
  fun appShellStartsOnEventsTab() = run {
    // Session routing remains for #74; exercise the signed-in shell directly here.
    composeTestRule.activity.setContent { PolySocialTheme { AppShell() } }
    step("Show signed-in app shell") {
      ComposeScreen.onComposeScreen<MainScreen>(composeTestRule) {
        appBarTitle {
          assertIsDisplayed()
          assertTextEquals("Events")
        }
        bottomNav { assertIsDisplayed() }
        eventsScreen { assertIsDisplayed() }
      }
    }
  }
}
