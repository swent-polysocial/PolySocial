// Contributors: Claude (UI tests for the per-tab back stacks, #42).
// Contributors: OpenAI Codex (inject an offline Map placeholder while testing shared navigation).
package com.polysocial.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each tab keeps its own back stack, including the Map detail's required event id. */
@RunWith(AndroidJUnit4::class)
class TabBackStackTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var navController: NavHostController

  @Before
  fun setUp() {
    composeTestRule.setContent {
      navController = rememberNavController()
      PolySocialTheme {
        AppShell(navController = navController, mapContent = { TabRootScreen(Tab.MAP) })
      }
    }
  }

  private fun select(tab: Tab) = composeTestRule.onNodeWithTag(tab.navItemTag).performClick()

  private fun openDetail(tab: Tab) {
    composeTestRule.runOnIdle {
      navController.navigate(
          if (tab == Tab.MAP) "${tab.detailRoute}/synthetic-event" else tab.detailRoute
      )
    }
    composeTestRule.onNodeWithTag(detailTag(tab)).assertIsDisplayed()
  }

  private fun detailTag(tab: Tab) =
      if (tab == Tab.MAP) C.Tag.event_detail_placeholder else tab.detailTag

  private fun pressBack() {
    composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeTestRule.waitForIdle()
  }

  private fun assertOnRootOf(tab: Tab) {
    composeTestRule.onNodeWithTag(tab.screenTag).assertIsDisplayed()
    composeTestRule.onNodeWithTag(tab.navItemTag).assertIsSelected()
  }

  @Test
  fun aTabKeepsItsDeepScreenWhenYouSwitchAwayAndBack() {
    Tab.entries.forEach { tab ->
      select(tab)
      openDetail(tab)
      val other = if (tab == Tab.MAP) Tab.CHATS else Tab.MAP
      select(other)
      // The other tab shows its own stack, which earlier rounds may have left on a detail screen
      composeTestRule.onNodeWithTag(other.navItemTag).assertIsSelected()
      composeTestRule.onAllNodesWithTag(detailTag(tab)).assertCountEquals(0)

      select(tab)

      composeTestRule.onNodeWithTag(detailTag(tab)).assertIsDisplayed()
      composeTestRule.onNodeWithTag(tab.navItemTag).assertIsSelected()
    }
  }

  @Test
  fun backPopsTheCurrentTabsOwnStackFirst() {
    Tab.entries.forEach { tab ->
      select(tab)
      openDetail(tab)

      pressBack()

      assertOnRootOf(tab)
      composeTestRule.onAllNodesWithTag(detailTag(tab)).assertCountEquals(0)
    }
  }

  @Test
  fun backFromTheRootOfMapChatsOrProfileGoesToEvents() {
    listOf(Tab.MAP, Tab.CHATS, Tab.PROFILE).forEach { tab ->
      select(tab)

      pressBack()

      assertOnRootOf(Tab.EVENTS)
      assertFalse(composeTestRule.activity.isFinishing)
    }
  }

  @Test
  fun switchingTabsIsNotABackStep() {
    select(Tab.MAP)
    select(Tab.CHATS)

    pressBack()

    assertOnRootOf(Tab.EVENTS)
  }

  @Test
  fun backFromTheEventsRootExitsTheApp() {
    select(Tab.MAP)
    select(Tab.EVENTS)

    pressBack()

    assertTrue(composeTestRule.activity.isFinishing)
  }
}
