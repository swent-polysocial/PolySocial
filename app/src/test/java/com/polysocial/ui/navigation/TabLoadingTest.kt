// Contributors: Claude (UI tests for the tab loading state, #43);
// OpenAI Codex (injected map placeholder to keep shared-loading tests offline after #50).
package com.polysocial.ui.navigation

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tab ViewModels run on a test dispatcher, so each tab stays in its loading state until the
 * test advances it. This shows the loading state deterministically, without real time.
 */
@RunWith(AndroidJUnit4::class)
class TabLoadingTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Before
  fun setUp() {
    composeTestRule.setContent {
      PolySocialTheme {
        // Exercise the shared tab loader; MapScreen's own states have separate map tests.
        AppShell(mapContent = { TabRootScreen(Tab.MAP) })
      }
    }
  }

  /** Lets Compose show the current tab (creating its ViewModel), then runs its loading. */
  private fun finishLoading() {
    composeTestRule.waitForIdle()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeTestRule.waitForIdle()
  }

  private fun assertLoading(tab: Tab) {
    composeTestRule.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(tab.loadingMessage)).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(tab.screenTag).assertCountEquals(0)
  }

  private fun assertPlaceholder(tab: Tab) {
    composeTestRule.onNodeWithTag(tab.screenTag).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(C.Tag.loading_state).assertCountEquals(0)
  }

  @Test
  fun eachTabShowsTheLoadingStateBeforeItsPlaceholder() {
    Tab.entries.forEach { tab ->
      if (tab != Tab.EVENTS) composeTestRule.onNodeWithTag(tab.navItemTag).performClick()
      assertLoading(tab)

      finishLoading()

      assertPlaceholder(tab)
    }
  }

  @Test
  fun returningToATabDoesNotShowTheLoadingStateAgain() {
    finishLoading()
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()
    finishLoading()
    composeTestRule.onNodeWithTag(Tab.EVENTS.navItemTag).performClick()

    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()

    assertPlaceholder(Tab.MAP)
  }
}
