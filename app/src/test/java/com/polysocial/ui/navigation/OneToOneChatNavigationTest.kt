// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #431).
package com.polysocial.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.ui.chat.OneToOneChatViewModel
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one-to-one chat (#431) is a route in a tab's graph, full screen, by the pair's ID, so Back
 * returns to where it was opened. AssociationAppShellTest checks that associations have none.
 */
@RunWith(AndroidJUnit4::class)
class OneToOneChatNavigationTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var navController: NavHostController

  private fun show() {
    composeTestRule.setContent {
      navController = rememberNavController()
      PolySocialTheme {
        AppShell(
            navController = navController,
            eventsContent = { TabRootScreen(Tab.EVENTS) },
            profileContent = { TabRootScreen(Tab.PROFILE) },
            mapContent = { _, _ -> TabRootScreen(Tab.MAP) },
            chatsContent = { TabRootScreen(Tab.CHATS) },
            oneToOneChatContent = { onBack ->
              TextButton(onBack, Modifier.testTag(CHAT)) { Text("Chat with Léa") }
            },
        )
      }
    }
  }

  @Test
  fun aChatOpensInTheProfileTab_fullScreen_byItsPairId_andBackReturns() {
    show()
    composeTestRule.onNodeWithTag(Tab.PROFILE.navItemTag).performClick()

    composeTestRule.runOnIdle { navController.navigate(Tab.PROFILE.oneToOneChatRoute(PAIR)) }

    composeTestRule.onNodeWithTag(CHAT).assertIsDisplayed()
    composeTestRule.runOnIdle {
      val entry = navController.currentBackStackEntry!!
      assertEquals(Tab.PROFILE.oneToOneChatRoute, entry.destination.route)
      assertEquals(PAIR, entry.arguments?.getString(OneToOneChatViewModel.PAIR_ID_ARG))
    }
    // Full screen, like the group chat.
    composeTestRule.onNodeWithTag(Tab.PROFILE.navItemTag).assertDoesNotExist()

    composeTestRule.onNodeWithTag(CHAT).performClick()

    composeTestRule.onNodeWithTag(Tab.PROFILE.navItemTag).assertIsSelected()
  }

  @Test
  fun theChatsTab_hasTheRouteToo() {
    show()
    composeTestRule.onNodeWithTag(Tab.CHATS.navItemTag).performClick()

    composeTestRule.runOnIdle { navController.navigate(Tab.CHATS.oneToOneChatRoute(PAIR)) }

    composeTestRule.onNodeWithTag(CHAT).assertIsDisplayed()
    composeTestRule.onNodeWithTag(CHAT).performClick()
    composeTestRule.onNodeWithTag(Tab.CHATS.navItemTag).assertIsSelected()
  }

  private companion object {
    const val CHAT = "one_to_one_chat"
    const val PAIR = "lea-uid~me-uid"
  }
}
