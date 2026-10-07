// Contributors: Claude (screen tests for the tab root screen, #43).
package com.polysocial.ui.navigation

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Renders one tab's root screen with a given ViewModel, outside the app shell. */
@RunWith(AndroidJUnit4::class)
class TabRootScreenTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun rendersTheLoadingStateThenThePlaceholderOfItsTab() {
    val viewModel = PlaceholderTabViewModel()
    composeTestRule.setContent {
      PolySocialTheme { TabRootScreen(tab = Tab.CHATS, viewModel = viewModel) }
    }

    composeTestRule.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.loading_chats)).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(Tab.CHATS.screenTag).assertCountEquals(0)

    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    composeTestRule.onNodeWithTag(Tab.CHATS.screenTag).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(
            context.getString(
                R.string.placeholder_coming_soon,
                context.getString(R.string.tab_chats),
            )
        )
        .assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(C.Tag.loading_state).assertCountEquals(0)
  }
}
