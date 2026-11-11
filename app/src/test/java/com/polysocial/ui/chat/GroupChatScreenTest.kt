// Contributors: OpenAI Codex (tested chat behavior with fake repositories, #238).
package com.polysocial.ui.chat

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import com.polysocial.model.chat.MessageRepository
import com.polysocial.model.chat.SendMessageResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.resources.C
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GroupChatScreenTest {
  @get:Rule(order = 0) val main = MainDispatcherRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val groups = FakeGroupRepository(ME).apply { readableEventIds += EVENT }
  private val group = groups.seedGroup(EVENT, listOf(ME, PEER))
  private val messages =
      FakeMessageRepository(ME).apply { setMembers(EVENT, group.id, setOf(ME, PEER)) }
  private val events =
      FakeEventRepository().apply { seed(validEvent(title = "Campus concert").copy(id = EVENT)) }
  private val network = FakeNetworkMonitor()
  private val profiles =
      FakePublicProfileRepository(
          mutableMapOf(
              PEER to PublicProfile(PEER, "Test Peer", "IN", "BA3"),
          )
      )

  @Test
  fun emptyChatDisablesBlankSendThenShowsSentAndIncomingMessages() {
    show()
    text(R.string.chat_empty).assertIsDisplayed()
    compose.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
    enter("  Meet at 6?  ")
    tap(ChatTags.SEND)

    assertEquals("Meet at 6?", messages.messagesOf(EVENT, group.id).single().text)
    compose.onNodeWithText("Meet at 6?").assertIsDisplayed()
    compose.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
    messages.seedMessage(
        EVENT,
        group.id,
        "peer-message",
        PEER,
        "See you there",
        NOW.plusSeconds(20),
    )
    settle()
    compose.onNodeWithText("See you there").assertIsDisplayed()
    compose.onNodeWithText("Test Peer").assertIsDisplayed()
  }

  @Test
  fun offlineSendKeepsTheDraftUntilExplicitRetry() {
    network.online = false
    messages.online = false
    messages.readFromCache = true
    messages.seedMessage(EVENT, group.id, "cached", PEER, "Saved message", NOW)
    show()
    compose.onNodeWithTag(ChatTags.OFFLINE).assertIsDisplayed()
    compose.onNodeWithText("Saved message").assertIsDisplayed()
    enter("Still on my way")
    tap(ChatTags.SEND)
    text(R.string.chat_send_offline).assertIsDisplayed()
    compose.onNodeWithTag(ChatTags.INPUT).assertTextContains("Still on my way")

    network.online = true
    messages.online = true
    settle()
    assertEquals(1, messages.messagesOf(EVENT, group.id).size)
    tap(ChatTags.RETRY_SEND)
    assertEquals(
        listOf("Saved message", "Still on my way"),
        messages.messagesOf(EVENT, group.id).map { it.text },
    )
    compose.onNodeWithTag(ChatTags.RETRY_SEND).assertDoesNotExist()
  }

  @Test
  fun lostAcknowledgementCanBeRetriedWithoutADuplicate() {
    val finish = CompletableDeferred<Unit>()
    var attempts = 0
    show(
        object : MessageRepository by messages {
          override suspend fun sendMessage(
              eventId: String,
              groupId: String,
              text: String,
              messageId: String?,
          ): SendMessageResult {
            if (attempts++ != 0) return messages.sendMessage(eventId, groupId, text, messageId)
            finish.await()
            assertEquals(
                SendMessageResult.Sent,
                messages.sendMessage(eventId, groupId, text, messageId),
            )
            return SendMessageResult.UnexpectedError
          }
        }
    )
    enter("I'll wait outside")
    tap(ChatTags.SEND)
    text(R.string.chat_sending).assertIsDisplayed()
    compose.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
    finish.complete(Unit)
    settle()
    text(R.string.chat_send_failed).assertIsDisplayed()
    compose.onNodeWithTag(ChatTags.INPUT).assertTextContains("I'll wait outside")
    tap(ChatTags.RETRY_SEND)
    assertEquals(1, messages.messagesOf(EVENT, group.id).size)
    assertEquals(2, attempts)
  }

  @Test
  fun readFailureCanBeRetriedAndLosingMembershipClearsTheChat() {
    messages.readFailure = FakeMessageRepository.ReadFailure.UNEXPECTED
    show()
    text(R.string.chat_read_failed).assertIsDisplayed()
    messages.readFailure = null
    messages.seedMessage(EVENT, group.id, "private", PEER, "Group plan", NOW)
    tap(C.Tag.error_retry)
    compose.onNodeWithText("Group plan").assertIsDisplayed()
    enter("Unsent draft")
    assertTrue(
        runBlocking { groups.leaveGroup(EVENT, group.id) }
            is com.polysocial.model.group.GroupWriteResult.Success
    )
    settle()
    text(R.string.chat_membership_ended).assertIsDisplayed()
    compose.onNodeWithText("Group plan").assertDoesNotExist()
    compose.onNodeWithText("Unsent draft").assertDoesNotExist()
  }

  private fun show(repository: MessageRepository = messages) {
    val viewModel =
        GroupChatViewModel(
            SavedStateHandle(
                mapOf(
                    EventDetailViewModel.EVENT_ID_ARG to EVENT,
                    GroupChatViewModel.GROUP_ID_ARG to group.id,
                )
            ),
            groups,
            repository,
            profiles,
            FakeAuthRepository(AuthUser(ME, "student@epfl.ch", true)),
            network,
            events,
            Clock.fixed(NOW, ZoneId.of("Europe/Zurich")),
        )
    compose.setContent { PolySocialTheme { GroupChatRoute(onBack = {}, viewModel = viewModel) } }
    settle()
  }

  private fun text(id: Int) = compose.onNodeWithText(context.getString(id))

  private fun enter(value: String) {
    compose.onNodeWithTag(ChatTags.INPUT).performTextInput(value)
    settle()
  }

  private fun tap(tag: String) {
    compose.onNodeWithTag(tag).performClick()
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
    const val ME = "student"
    const val PEER = "peer"
    const val EVENT = "event"
    val NOW = Instant.parse("2030-01-01T12:00:00Z")
  }
}
