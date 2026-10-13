// Contributors: Claude (UI tests for the app bar actions, #84).
package com.polysocial.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppBarActionTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private fun string(id: Int, vararg args: Any) = composeTestRule.activity.getString(id, *args)

  @Before
  fun setUp() {
    composeTestRule.setContent {
      PolySocialTheme {
        AppShell(
            eventsContent = { TabRootScreen(Tab.EVENTS) },
            mapContent = { _, _ -> TabRootScreen(Tab.MAP) },
        )
      }
    }
  }

  private fun select(tab: Tab) = composeTestRule.onNodeWithTag(tab.navItemTag).performClick()

  private fun pressBack() {
    composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeTestRule.waitForIdle()
  }

  private fun assertNoActionShown() {
    TabAction.entries.forEach { composeTestRule.onAllNodesWithTag(it.tag).assertCountEquals(0) }
  }

  private val tabsWithActions = Tab.entries.filter { it.action != null }

  @Test
  fun eventsChatsAndProfile_showTheirFigmaAction() {
    val expected =
        mapOf(
            Tab.EVENTS to TabAction.NOTIFICATIONS,
            Tab.CHATS to TabAction.SEARCH_CHATS,
            Tab.PROFILE to TabAction.EDIT_PROFILE,
        )
    expected.forEach { (tab, action) ->
      select(tab)
      composeTestRule
          .onNodeWithTag(action.tag)
          .assertIsDisplayed()
          .assertContentDescriptionEquals(string(action.label))
      TabAction.entries
          .filter { it != action }
          .forEach { composeTestRule.onAllNodesWithTag(it.tag).assertCountEquals(0) }
    }
  }

  @Test
  fun theMapTab_hasNoAction() {
    select(Tab.MAP)

    composeTestRule.onNodeWithTag(Tab.MAP.screenTag).assertIsDisplayed()
    assertNoActionShown()
  }

  @Test
  fun anAction_opensItsPlaceholderInsideTheTab_andBackReturnsToTheTabRoot() {
    tabsWithActions.forEach { tab ->
      val action = tab.action!!
      select(tab)

      composeTestRule.onNodeWithTag(action.tag).performClick()

      composeTestRule.onNodeWithTag(action.screenTag).assertIsDisplayed()
      composeTestRule
          .onNodeWithText(string(R.string.placeholder_coming_soon, string(action.label)))
          .assertIsDisplayed()
      composeTestRule.onNodeWithTag(C.Tag.app_bar_title).assertTextEquals(string(tab.label))
      composeTestRule.onNodeWithTag(tab.navItemTag).assertIsSelected()
      // The screen an action opens doesn't offer the action again.
      assertNoActionShown()

      pressBack()

      composeTestRule.onNodeWithTag(tab.screenTag).assertIsDisplayed()
      composeTestRule.onAllNodesWithTag(action.screenTag).assertCountEquals(0)
      composeTestRule.onNodeWithTag(action.tag).assertIsDisplayed()
    }
  }

  @Test
  fun anOpenAction_staysOpenWhenSwitchingTabsAndBack() {
    composeTestRule.onNodeWithTag(TabAction.NOTIFICATIONS.tag).performClick()

    select(Tab.CHATS)
    composeTestRule.onNodeWithTag(Tab.CHATS.screenTag).assertIsDisplayed()
    select(Tab.EVENTS)

    composeTestRule.onNodeWithTag(TabAction.NOTIFICATIONS.screenTag).assertIsDisplayed()
  }
}
