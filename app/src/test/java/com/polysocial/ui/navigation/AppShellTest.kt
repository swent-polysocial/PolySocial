// Contributors: Claude (UI tests for the bottom navigation and app bar, #41).
package com.polysocial.ui.navigation

import android.content.Context
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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun label(tab: Tab) = context.getString(tab.label)

  @Before
  fun setUp() {
    composeTestRule.setContent { PolySocialTheme { AppShell() } }
  }

  private fun assertOnTab(tab: Tab) {
    composeTestRule.onNodeWithTag(tab.screenTag).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.app_bar_title).assertTextEquals(label(tab))
    composeTestRule
        .onNodeWithText(context.getString(R.string.placeholder_coming_soon, label(tab)))
        .assertIsDisplayed()
    Tab.entries.forEach { other ->
      val item = composeTestRule.onNodeWithTag(other.navItemTag)
      if (other == tab) item.assertIsSelected() else item.assertIsNotSelected()
      if (other != tab) composeTestRule.onAllNodesWithTag(other.screenTag).assertCountEquals(0)
    }
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
  fun tappingTheCurrentTabAgainKeepsASingleScreen() {
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()

    composeTestRule.onAllNodesWithTag(Tab.MAP.screenTag).assertCountEquals(1)
    assertOnTab(Tab.MAP)
  }
}
