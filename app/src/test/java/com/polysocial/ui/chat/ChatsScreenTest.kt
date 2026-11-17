// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #364).
package com.polysocial.ui.chat

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.EventCategory
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** UI tests for the Chats list (#364), drawn from [ChatsUiState] alone. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ChatsScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val opened = mutableListOf<Pair<String, String>>()
  private var retries = 0

  private fun show(state: ChatsUiState) {
    composeTestRule.setContent {
      PolySocialTheme {
        ChatsScreen(state, onOpenChat = { e, g -> opened += e to g }, onRetry = { retries++ })
      }
    }
  }

  private fun row(
      groupId: String,
      event: ChatEvent = ChatEvent.Known("Balélec warm-up", EventCategory.PARTY),
      otherNames: List<String> = listOf("Léa", "Tom"),
      otherCount: Int = otherNames.size,
      latest: LatestMessage? =
          LatestMessage(
              Sender.Named("Léa"),
              "I'm at the entrance",
              ChatTime.Today(LocalTime.of(20, 6)),
          ),
  ) = ChatRow("party", groupId, event, otherNames, otherCount, latest)

  /** The row is one button, so its texts are merged into it, as TalkBack reads them. */
  private fun assertInRow(groupId: String, text: String) {
    composeTestRule.onNodeWithTag(ChatsTags.row("party", groupId)).assert(hasText(text))
  }

  @Test
  fun aRow_showsTheEventTimeMembersAndLatestMessage_andOpensItsChat() {
    show(ChatsUiState.Chats(listOf(row("g1")), isOffline = false))

    assertInRow("g1", "Balélec warm-up")
    assertInRow("g1", "20:06")
    assertInRow("g1", "Léa, Tom and you")
    assertInRow("g1", "Léa: I'm at the entrance")
    composeTestRule.onNodeWithTag(ChatsTags.OFFLINE_BANNER).assertDoesNotExist()

    composeTestRule.onNodeWithTag(ChatsTags.row("party", "g1")).performClick()

    assertEquals(listOf("party" to "g1"), opened)
  }

  @Test
  fun times_areYesterdayTheWeekdayOrTheDate() {
    fun at(time: ChatTime) = LatestMessage(Sender.You, "Hi", time)
    show(
        ChatsUiState.Chats(
            listOf(
                row("g1", latest = at(ChatTime.Yesterday)),
                row("g2", latest = at(ChatTime.ThisWeek(DayOfWeek.MONDAY))),
                row("g3", latest = at(ChatTime.Earlier(LocalDate.of(2026, 10, 2)))),
            ),
            isOffline = false,
        )
    )

    assertInRow("g1", "Yesterday")
    assertInRow("g2", "Mon")
    assertInRow("g3", "Fri 2 Oct")
    assertInRow("g1", "You: Hi")
  }

  @Test
  fun aChatWithoutMessages_saysSo_withoutATime() {
    show(ChatsUiState.Chats(listOf(row("g1", otherNames = emptyList(), latest = null)), false))

    assertInRow("g1", "No messages yet")
    assertInRow("g1", "Just you")
  }

  @Test
  fun unnamedMembers_areCounted_andSendersFallBack() {
    show(
        ChatsUiState.Chats(
            listOf(
                row(
                    "g1",
                    otherNames = emptyList(),
                    otherCount = 2,
                    latest = LatestMessage(Sender.Unknown, "See you", ChatTime.Yesterday),
                ),
                row(
                    "g2",
                    latest = LatestMessage(Sender.DeletedAccount, "Old", ChatTime.Yesterday),
                ),
            ),
            isOffline = false,
        )
    )

    assertInRow("g1", "3 members")
    assertInRow("g1", "See you")
    assertInRow("g2", "Deleted account: Old")
  }

  @Test
  fun aChatWhoseEventIsGone_isAGroupChat() {
    show(ChatsUiState.Chats(listOf(row("g1", event = ChatEvent.Unavailable)), false))

    assertInRow("g1", "Group chat")
  }

  @Test
  fun anEventStillLoading_hasNoTitleYet_butTheRestOfTheRow() {
    show(ChatsUiState.Chats(listOf(row("g1", event = ChatEvent.Loading)), false))

    composeTestRule.onNodeWithTag(ChatsTags.row("party", "g1")).assert(hasText("Léa, Tom and you"))
    composeTestRule.onNodeWithText("Balélec warm-up").assertDoesNotExist()
    composeTestRule.onNodeWithText("Group chat").assertDoesNotExist()
  }

  @Test
  fun offline_showsTheBannerAboveTheChats() {
    show(ChatsUiState.Chats(listOf(row("g1")), isOffline = true))

    composeTestRule.onNodeWithTag(ChatsTags.OFFLINE_BANNER).assertIsDisplayed()
    composeTestRule.onNodeWithTag(ChatsTags.row("party", "g1")).assertIsDisplayed()
  }

  @Test
  fun noChats_invitesToJoinAGroup() {
    show(ChatsUiState.Chats(emptyList(), isOffline = false))

    composeTestRule.onNodeWithTag(ChatsTags.EMPTY).assertIsDisplayed()
    composeTestRule.onNodeWithText("Join a group to chat").assertIsDisplayed()
    composeTestRule.onNodeWithTag(ChatsTags.LIST).assertDoesNotExist()
  }

  @Test
  fun couldntLoad_offersRetry() {
    show(ChatsUiState.Error)

    composeTestRule.onNodeWithText("Couldn't load your chats").assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.error_retry).performClick()

    assertEquals(1, retries)
  }

  @Test
  fun offlineWithNothingSaved_saysSo_withRetry() {
    show(ChatsUiState.OfflineNothingSaved)

    composeTestRule.onNodeWithText("You're offline").assertIsDisplayed()
    composeTestRule.onNodeWithText("Join a group to chat").assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.error_retry).performClick()

    assertEquals(1, retries)
  }

  @Test
  fun whileLoading_itSaysSo() {
    show(ChatsUiState.Loading)

    composeTestRule.onNodeWithText("Loading chats…").assertIsDisplayed()
  }
}
