// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #364; a one-to-one row, #432).
package com.polysocial.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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
import com.polysocial.ui.chat.GroupChatViewModel
import com.polysocial.ui.chat.OneToOneChatViewModel
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Chats tab opens a group chat (#364) or a one-to-one chat (#432) inside the tab, and Back
 * returns to the list.
 */
@RunWith(AndroidJUnit4::class)
class ChatsNavigationTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var navController: NavHostController

  @Before
  fun setUp() {
    composeTestRule.setContent {
      navController = rememberNavController()
      PolySocialTheme {
        AppShell(
            navController = navController,
            eventsContent = { TabRootScreen(Tab.EVENTS) },
            profileContent = { TabRootScreen(Tab.PROFILE) },
            mapContent = { _, _ -> TabRootScreen(Tab.MAP) },
            chatsContent = { openChat, openOneToOneChat ->
              Column {
                TextButton({ openChat("party", "group-1") }, Modifier.testTag(OPEN_CHAT)) {
                  Text("Balélec warm-up")
                }
                TextButton({ openOneToOneChat(PAIR) }, Modifier.testTag(OPEN_ONE_TO_ONE)) {
                  Text("Léa Martin")
                }
              }
            },
            groupChatContent = { onBack ->
              TextButton(onBack, Modifier.testTag(CHAT)) { Text("Group chat") }
            },
            oneToOneChatContent = { onBack ->
              TextButton(onBack, Modifier.testTag(ONE_TO_ONE_CHAT)) { Text("Chat with Léa") }
            },
        )
      }
    }
  }

  @Test
  fun aChatRow_opensItsGroupChatInTheChatsTab_andBackReturnsToTheList() {
    composeTestRule.onNodeWithTag(Tab.CHATS.navItemTag).performClick()
    composeTestRule.onNodeWithTag(OPEN_CHAT).performClick()

    composeTestRule.onNodeWithTag(CHAT).assertIsDisplayed()
    composeTestRule.runOnIdle {
      val entry = navController.currentBackStackEntry!!
      assertEquals(Tab.CHATS.groupChatRoute, entry.destination.route)
      assertEquals("party", entry.arguments?.getString(EventDetailViewModel.EVENT_ID_ARG))
      assertEquals("group-1", entry.arguments?.getString(GroupChatViewModel.GROUP_ID_ARG))
    }
    // The chat is full screen, like from the group roster.
    composeTestRule.onNodeWithTag(Tab.CHATS.navItemTag).assertDoesNotExist()

    composeTestRule.onNodeWithTag(CHAT).performClick()

    composeTestRule.onNodeWithTag(OPEN_CHAT).assertIsDisplayed()
    composeTestRule.onNodeWithTag(Tab.CHATS.navItemTag).assertIsSelected()
  }

  @Test
  fun aOneToOneRow_opensItsChatInTheChatsTab_andBackReturnsToTheList() {
    composeTestRule.onNodeWithTag(Tab.CHATS.navItemTag).performClick()
    composeTestRule.onNodeWithTag(OPEN_ONE_TO_ONE).performClick()

    composeTestRule.onNodeWithTag(ONE_TO_ONE_CHAT).assertIsDisplayed()
    composeTestRule.runOnIdle {
      val entry = navController.currentBackStackEntry!!
      assertEquals(Tab.CHATS.oneToOneChatRoute, entry.destination.route)
      assertEquals(PAIR, entry.arguments?.getString(OneToOneChatViewModel.PAIR_ID_ARG))
    }
    composeTestRule.onNodeWithTag(Tab.CHATS.navItemTag).assertDoesNotExist()

    composeTestRule.onNodeWithTag(ONE_TO_ONE_CHAT).performClick()

    composeTestRule.onNodeWithTag(OPEN_ONE_TO_ONE).assertIsDisplayed()
    composeTestRule.onNodeWithTag(Tab.CHATS.navItemTag).assertIsSelected()
  }

  private companion object {
    const val OPEN_CHAT = "open_chat"
    const val OPEN_ONE_TO_ONE = "open_one_to_one"
    const val ONE_TO_ONE_CHAT = "one_to_one_chat"
    const val PAIR = "lea~me"
    const val CHAT = "group_chat"
  }
}
