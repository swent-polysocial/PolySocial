// Contributors: Claude (check the app opens on the Events tab, #41).
package com.polysocial

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import com.polysocial.screen.MainScreen
import io.github.kakaocup.compose.node.element.ComposeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real app on a device and checks it opens on the Events tab of the app shell. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest : TestCase() {

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun appStartsOnEventsTab() = run {
    step("Start Main Activity") {
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
