// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #431).
package com.polysocial.ui.chat

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.chat.FakeMessageRepository
import com.polysocial.model.chat.FakeOneToOneChatRepository
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.friendshipId
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OneToOneChatScreenTest {
  @get:Rule(order = 0) val main = MainDispatcherRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val friends = FakeFriendRepository(currentUid = ME)
  private val chats =
      FakeOneToOneChatRepository(currentUid = ME).apply { friendshipOf = friends::friendship }
  private val profiles =
      FakePublicProfileRepository(
          mutableMapOf(FRIEND to PublicProfile(FRIEND, "Léa Martin", "IN", "BA3"))
      )
  private val network = FakeNetworkMonitor()

  @Test
  fun aFriendsEmptyChat_namesThem_andTheFirstMessageShows() {
    friends.seedAcceptedFriendship(FRIEND, ME)
    show()

    compose.onNodeWithTag(OneToOneChatTags.NAME).assertTextEquals("Léa Martin")
    compose
        .onNodeWithTag(OneToOneChatTags.EMPTY)
        .assertTextEquals(context.getString(R.string.one_to_one_chat_empty_named, "Léa"))
    compose.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()

    compose.onNodeWithTag(ChatTags.INPUT).performTextInput("Coffee at 4?")
    settle()
    compose.onNodeWithTag(ChatTags.SEND).assertIsEnabled().performClick()
    settle()

    assertEquals(listOf("Coffee at 4?"), chats.messagesOf(PAIR).map { it.text })
    compose.onNodeWithText("Coffee at 4?").assertIsDisplayed()
    compose.onNodeWithTag(OneToOneChatTags.EMPTY).assertDoesNotExist()
  }

  @Test
  fun theFriendsMessages_haveNoSenderName() {
    friends.seedAcceptedFriendship(FRIEND, ME)
    chats.seedChat(PAIR)
    chats.seedMessage(PAIR, "m1", FRIEND, "Meet at the Satellite?", NOW)
    show()

    compose.onNodeWithText("Meet at the Satellite?").assertIsDisplayed()
    // The header names the friend; their bubble doesn't, unlike in a group chat.
    compose.onAllNodesWithText("Léa Martin").assertCountEquals(1)
    compose.onNodeWithTag(OneToOneChatTags.NAME).assertIsDisplayed()
  }

  @Test
  fun removingTheFriend_replacesTheComposerWithTheNote_andKeepsTheHistory() {
    friends.seedAcceptedFriendship(FRIEND, ME)
    chats.seedChat(PAIR)
    chats.seedMessage(PAIR, "m1", FRIEND, "See you", NOW)
    show()
    compose.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()

    friends.deleteFriendship(PAIR)
    settle()

    compose
        .onNodeWithTag(OneToOneChatTags.READ_ONLY)
        .assertTextEquals(context.getString(R.string.one_to_one_chat_not_friends_named, "Léa"))
    compose.onNodeWithTag(ChatTags.INPUT).assertDoesNotExist()
    compose.onNodeWithText("See you").assertIsDisplayed()
  }

  @Test
  fun aDeletedAccount_isNamedSo_withItsNote() {
    chats.seedChat(PAIR, uids = listOf(ME))
    chats.seedMessage(PAIR, "m1", "", "Bye", NOW)
    show()

    compose
        .onNodeWithTag(OneToOneChatTags.NAME)
        .assertTextEquals(context.getString(R.string.chat_deleted_author))
    compose
        .onNodeWithTag(OneToOneChatTags.READ_ONLY)
        .assertTextEquals(context.getString(R.string.one_to_one_chat_account_deleted))
    compose.onNodeWithText("Bye").assertIsDisplayed()
  }

  @Test
  fun aProfileThatCantBeRead_stillOpensTheChat() {
    friends.seedAcceptedFriendship(FRIEND, ME)
    profiles.failure = FakePublicProfileRepository.Failure.Unexpected
    show()

    compose
        .onNodeWithTag(OneToOneChatTags.NAME)
        .assertTextEquals(context.getString(R.string.group_profile_unavailable))
    compose
        .onNodeWithTag(OneToOneChatTags.EMPTY)
        .assertTextEquals(context.getString(R.string.one_to_one_chat_empty))
    compose.onNodeWithTag(ChatTags.INPUT).assertIsDisplayed()
  }

  @Test
  fun offline_showsTheCachedHistory_andASendToRetry() {
    friends.seedAcceptedFriendship(FRIEND, ME)
    chats.seedChat(PAIR)
    chats.seedMessage(PAIR, "m1", FRIEND, "Saved message", NOW)
    network.online = false
    chats.online = false
    chats.readFromCache = true
    show()
    compose.onNodeWithTag(ChatTags.OFFLINE).assertIsDisplayed()
    compose.onNodeWithText("Saved message").assertIsDisplayed()

    compose.onNodeWithTag(ChatTags.INPUT).performTextInput("On my way")
    settle()
    compose.onNodeWithTag(ChatTags.SEND).performClick()
    settle()

    compose.onNodeWithText(context.getString(R.string.chat_send_offline)).assertIsDisplayed()
    compose.onNodeWithTag(ChatTags.INPUT).assertTextContains("On my way")
    network.online = true
    chats.online = true
    settle()
    compose.onNodeWithTag(ChatTags.RETRY_SEND).performClick()
    settle()
    assertEquals(listOf("Saved message", "On my way"), chats.messagesOf(PAIR).map { it.text })
  }

  @Test
  fun aReadFailure_offersRetry() {
    friends.seedAcceptedFriendship(FRIEND, ME)
    chats.readFailure = FakeMessageRepository.ReadFailure.UNEXPECTED
    show()
    compose.onNodeWithText(context.getString(R.string.chat_read_failed)).assertIsDisplayed()

    chats.readFailure = null
    compose.onNodeWithText(context.getString(R.string.retry)).performClick()
    settle()

    compose.onNodeWithTag(OneToOneChatTags.EMPTY).assertIsDisplayed()
  }

  @Test
  fun back_leavesTheChat() {
    friends.seedAcceptedFriendship(FRIEND, ME)
    var backs = 0
    show(onBack = { backs++ })

    compose.onNodeWithTag(OneToOneChatTags.BACK).performClick()

    assertEquals(1, backs)
  }

  private fun show(onBack: () -> Unit = {}) {
    val viewModel =
        OneToOneChatViewModel(
            SavedStateHandle(mapOf(OneToOneChatViewModel.PAIR_ID_ARG to PAIR)),
            chats,
            friends,
            profiles,
            FakeAuthRepository(AuthUser(ME, "me@epfl.ch", true)),
            network,
            Clock.fixed(NOW, ZoneId.of("Europe/Zurich")),
        )
    compose.setContent {
      PolySocialTheme { OneToOneChatRoute(onBack = onBack, viewModel = viewModel) }
    }
    settle()
  }

  private fun settle() {
    repeat(2) {
      compose.waitForIdle()
      main.dispatcher.scheduler.runCurrent()
    }
    compose.waitForIdle()
  }

  private companion object {
    const val ME = "me-uid"
    const val FRIEND = "lea-uid"
    val PAIR = checkNotNull(friendshipId(ME, FRIEND))
    val NOW: Instant = Instant.parse("2030-02-01T12:00:00Z")
  }
}
