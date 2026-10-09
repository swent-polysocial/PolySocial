// Contributors: Claude (UI tests for the bottom navigation and app bar, #41; nested tab routes,
// #42).
// Contributors: OpenAI Codex (map tab chrome and detail-route regression tests, #50).
package com.polysocial.ui.navigation

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var navController: NavHostController

  private fun label(tab: Tab) = context.getString(tab.label)

  @Before
  fun setUp() {
    composeTestRule.setContent {
      navController = rememberNavController()
      PolySocialTheme {
        AppShell(
            navController = navController,
            mapContent = { details ->
              Box(Modifier.testTag(C.Tag.screen_map)) {
                TextButton({ details("event/one") }, Modifier.testTag("fake_map_details")) {
                  Text("Open test event")
                }
              }
            },
        )
      }
    }
  }

  private fun assertOnTab(tab: Tab) {
    composeTestRule.onNodeWithTag(tab.screenTag).assertIsDisplayed()
    if (tab == Tab.MAP) {
      composeTestRule.onNodeWithTag(C.Tag.app_bar_title).assertDoesNotExist()
    } else {
      composeTestRule.onNodeWithTag(C.Tag.app_bar_title).assertTextEquals(label(tab))
      composeTestRule
          .onNodeWithText(context.getString(R.string.placeholder_coming_soon, label(tab)))
          .assertIsDisplayed()
    }
    Tab.entries.forEach { other ->
      val item = composeTestRule.onNodeWithTag(other.navItemTag)
      if (other == tab) item.assertIsSelected() else item.assertIsNotSelected()
      if (other != tab) composeTestRule.onAllNodesWithTag(other.screenTag).assertCountEquals(0)
    }
  }

  @Test
  fun mapDetailsPlaceholderKeepsMapSelectedAndReturnsToMap() {
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()
    composeTestRule.onNodeWithTag("fake_map_details").performClick()
    composeTestRule.onNodeWithTag("event_detail_placeholder").assertIsDisplayed()
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.app_bar_title).assertTextEquals(label(Tab.MAP))
    composeTestRule.runOnIdle {
      assertEquals(
          "event/one",
          navController.currentBackStackEntry?.arguments?.getString("eventId"),
      )
    }
    composeTestRule.onNodeWithTag("event_detail_back").performClick()
    assertOnTab(Tab.MAP)
  }

  @Test
  fun selectedMapEventDetailSurvivesSwitchingTabsAndRetappingMap() {
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()
    composeTestRule.onNodeWithTag("fake_map_details").performClick()
    val entryId = composeTestRule.runOnIdle { navController.currentBackStackEntry?.id }

    composeTestRule.onNodeWithTag(Tab.CHATS.navItemTag).performClick()
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()

    composeTestRule.onNodeWithTag("event_detail_placeholder").assertIsDisplayed()
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.app_bar_title).assertTextEquals(label(Tab.MAP))
    composeTestRule.runOnIdle {
      assertEquals(entryId, navController.currentBackStackEntry?.id)
      assertEquals(
          "event/one",
          navController.currentBackStackEntry?.arguments?.getString("eventId"),
      )
    }
    composeTestRule.onNodeWithTag("event_detail_back").performClick()
    assertOnTab(Tab.MAP)
  }

  @Test
  fun startsOnEventsTab() {
    assertOnTab(Tab.EVENTS)
  }

  @Test
  fun bottomBarShowsEveryTabWithItsLabel() {
    composeTestRule.onNodeWithTag(C.Tag.bottom_nav).assertIsDisplayed()
    Tab.entries.forEach { tab ->
      composeTestRule.onNodeWithTag(tab.navItemTag).assertIsDisplayed().assert(hasText(label(tab)))
    }
  }

  @Test
  fun tappingEachTabShowsItsScreenAndTitle() {
    listOf(Tab.MAP, Tab.CHATS, Tab.PROFILE, Tab.EVENTS).forEach { tab ->
      composeTestRule.onNodeWithTag(tab.navItemTag).performClick()
      assertOnTab(tab)
    }
  }

  @Test
  fun tappingTheCurrentTabAgainKeepsTheSameScreen() {
    Tab.entries.forEach { tab ->
      composeTestRule.onNodeWithTag(tab.navItemTag).performClick()
      val entryId = composeTestRule.runOnIdle { navController.currentBackStackEntry?.id }

      composeTestRule.onNodeWithTag(tab.navItemTag).performClick()

      composeTestRule.runOnIdle {
        // Same back stack entry id: the screen is not recreated and keeps its state
        assertEquals(entryId, navController.currentBackStackEntry?.id)
        // Only the Events tab can sit below a tab, so back never shows a duplicate
        val below = navController.previousBackStackEntry?.destination?.route
        assertEquals(if (tab == Tab.EVENTS) null else Tab.EVENTS.rootRoute, below)
      }
      assertOnTab(tab)
    }
  }
}
