// Contributors: OpenAI Codex (tested chat, stable history and sender names, connectivity, and
// input with fakes, #238).
package com.polysocial.ui.chat

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.chat.FakeMessageRepository
import com.polysocial.model.chat.MAX_MESSAGE_LENGTH
import com.polysocial.model.chat.MessageRepository
import com.polysocial.model.chat.SendMessageResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.MyGroupResult
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.resources.C
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
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
  private val auth = FakeAuthRepository(AuthUser(ME, "student@epfl.ch", true))
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
    compose.onNodeWithTag(ChatTags.OFFLINE).assertDoesNotExist()
    compose.onNodeWithTag(ChatTags.RETRY_SEND).assertIsEnabled()
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
    val viewModel =
        show(
            object : MessageRepository by messages {
              override suspend fun sendMessage(
                  eventId: String,
                  groupId: String,
                  text: String,
                  messageId: String?,
              ): SendMessageResult {
                if (attempts++ != 0) return messages.sendMessage(eventId, groupId, text, messageId)
                assertEquals(
                    SendMessageResult.Sent,
                    messages.sendMessage(eventId, groupId, text, messageId),
                )
                finish.await()
                error("Acknowledgement was lost")
              }
            }
        )
    enter("I'll wait outside")
    tap(ChatTags.SEND)
    text(R.string.chat_sending).assertIsDisplayed()
    compose.onAllNodesWithText("I'll wait outside").assertCountEquals(1)
    compose.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
    viewModel.edit("Edited during send")
    viewModel.send()
    finish.complete(Unit)
    settle()
    text(R.string.chat_send_failed).assertIsDisplayed()
    compose.onNodeWithTag(ChatTags.INPUT).assertTextContains("I'll wait outside")
    viewModel.edit("  I'll wait outside  ")
    settle()
    tap(ChatTags.RETRY_SEND)
    assertEquals(1, messages.messagesOf(EVENT, group.id).size)
    assertEquals(2, attempts)
  }

  @Test
  fun readFailureCanBeRetriedAndLosingMembershipClearsTheChat() {
    messages.readFailure = FakeMessageRepository.ReadFailure.NETWORK
    show()
    text(R.string.chat_read_failed).assertIsDisplayed()

    messages.readFailure = FakeMessageRepository.ReadFailure.UNEXPECTED
    tap(C.Tag.error_retry)
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

  @Test
  fun aSlowMembershipReadTimesOutAndOnlyTheOriginalAccountCanSend() {
    val snapshots = MutableStateFlow<MyGroupResult>(MyGroupResult.Success(null, fromCache = true))
    val viewModel =
        show(
            groupRepository =
                object : GroupRepository by groups {
                  override fun observeMyGroup(eventId: String) = snapshots
                }
        )
    text(R.string.chat_loading).assertIsDisplayed()
    enter("Meet outside")
    viewModel.send()
    assertTrue(messages.messagesOf(EVENT, group.id).isEmpty())

    main.dispatcher.scheduler.advanceTimeBy(10_001)
    settle()
    text(R.string.chat_read_failed).assertIsDisplayed()
    snapshots.value = MyGroupResult.Success(group, fromCache = false)
    settle()

    auth.user = null
    viewModel.send()
    settle()
    assertTrue(messages.messagesOf(EVENT, group.id).isEmpty())
    assertEquals("Meet outside", viewModel.state.value.draft.text)

    auth.user = AuthUser(ME, "student@epfl.ch", true)
    tap(ChatTags.SEND)
    assertEquals("Meet outside", messages.messagesOf(EVENT, group.id).single().text)
  }

  @Test
  fun aStaleRouteCannotReadTheChatAfterMembershipChanges() {
    val membership = MutableStateFlow<MyGroupResult>(MyGroupResult.NetworkError)
    var historyReads = 0
    show(
        repository =
            object : MessageRepository by messages {
              override fun observeMessages(eventId: String, groupId: String) =
                  messages.observeMessages(eventId, groupId).also { historyReads++ }
            },
        groupRepository =
            object : GroupRepository by groups {
              override fun observeMyGroup(eventId: String) = membership
            },
    )
    text(R.string.chat_read_failed).assertIsDisplayed()
    membership.value = MyGroupResult.UnexpectedError
    settle()
    text(R.string.chat_read_failed).assertIsDisplayed()

    membership.value = MyGroupResult.Success(group.copy(id = "another-group"), fromCache = false)
    settle()
    text(R.string.chat_membership_ended).assertIsDisplayed()

    membership.value =
        MyGroupResult.Success(group.copy(memberUids = listOf(PEER)), fromCache = false)
    settle()
    text(R.string.chat_membership_ended).assertIsDisplayed()
    assertEquals(0, historyReads)
  }

  @Test
  fun aCancelledSendKeepsItsDraftAndCanBeSentAfterEditing() {
    var attempts = 0
    val viewModel =
        show(
            object : MessageRepository by messages {
              override suspend fun sendMessage(
                  eventId: String,
                  groupId: String,
                  text: String,
                  messageId: String?,
              ): SendMessageResult {
                if (attempts++ == 0) throw CancellationException("Test cancelled send")
                return messages.sendMessage(eventId, groupId, text, messageId)
              }
            }
        )
    enter("Meet outside")
    tap(ChatTags.SEND)
    assertEquals("Meet outside", viewModel.state.value.draft.text)
    assertTrue(messages.messagesOf(EVENT, group.id).isEmpty())

    viewModel.edit("Meet inside")
    settle()
    tap(ChatTags.SEND)
    assertEquals("Meet inside", messages.messagesOf(EVENT, group.id).single().text)
  }

  @Test
  fun aMemberJoiningAfterTheTimeoutKeepsTheTimelineAndItsScrollPosition() {
    repeat(24) { index ->
      messages.seedMessage(
          EVENT,
          group.id,
          "m-$index",
          PEER,
          "Message $index",
          NOW.plusSeconds(index.toLong()),
      )
    }
    val membership = MutableStateFlow(MyGroupResult.Success(group, fromCache = false))
    var historyReads = 0
    show(
        object : MessageRepository by messages {
          override fun observeMessages(eventId: String, groupId: String) =
              messages.observeMessages(eventId, groupId).onStart { historyReads++ }
        },
        object : GroupRepository by groups {
          override fun observeMyGroup(eventId: String) = membership
        },
    )
    compose.onNodeWithTag(ChatTags.TIMELINE).performScrollToIndex(0)
    compose.onNodeWithText("Message 0").assertIsDisplayed()
    enter("Draft while reading")
    main.dispatcher.scheduler.advanceTimeBy(10_001)
    membership.value =
        MyGroupResult.Success(group.copy(memberUids = listOf(ME, PEER, "new")), fromCache = false)
    settle()
    compose.onNodeWithText("Message 0").assertIsDisplayed()
    compose.onNodeWithTag(ChatTags.SEND).assertIsEnabled()

    membership.value = membership.value.copy(fromCache = true)
    settle()
    assertEquals(1, historyReads)
    compose.onNodeWithText("Message 0").assertIsDisplayed()
    text(R.string.chat_read_failed).assertDoesNotExist()
  }

  @Test
  fun aMemberJoiningKeepsLoadedSenderNamesWhileProfilesRefresh() {
    val newcomer = "new"
    profiles.profiles[newcomer] = PublicProfile(newcomer, "Test Newcomer", "IN", "BA3")
    val finishProfileRead = CompletableDeferred<Unit>()
    val membership = MutableStateFlow(MyGroupResult.Success(group, fromCache = false))
    messages.seedMessage(EVENT, group.id, "peer-message", PEER, "See you there", NOW)
    show(
        groupRepository =
            object : GroupRepository by groups {
              override fun observeMyGroup(eventId: String) = membership
            },
        profileRepository =
            object : PublicProfileRepository by profiles {
              override suspend fun getPublicProfile(uid: String) =
                  profiles.getPublicProfile(uid).also {
                    if (uid == newcomer) finishProfileRead.await()
                  }
            },
    )
    compose.onNodeWithText("Test Peer").assertIsDisplayed()

    membership.value =
        membership.value.copy(group = group.copy(memberUids = listOf(ME, PEER, newcomer)))
    messages.setMembers(EVENT, group.id, setOf(ME, PEER, newcomer))
    messages.seedMessage(
        EVENT,
        group.id,
        "new-message",
        newcomer,
        "I'll be there",
        NOW.plusSeconds(1),
    )
    settle()
    compose.onNodeWithText("Test Peer").assertIsDisplayed()
    text(R.string.group_unknown_member).assertIsDisplayed()

    finishProfileRead.complete(Unit)
    settle()
    compose.onNodeWithText("Test Newcomer").assertIsDisplayed()
  }

  @Test
  fun overlongPasteKeepsThePrefixWithoutSplittingAnEmoji() {
    val viewModel = show()
    val prefix = "a".repeat(MAX_MESSAGE_LENGTH - 1)
    viewModel.edit(prefix + "😀" + "b")
    settle()
    assertEquals(prefix, viewModel.state.value.draft.text)

    val fullLength = prefix + "b"
    viewModel.edit(fullLength + "c")
    settle()
    assertEquals(fullLength, viewModel.state.value.draft.text)
    compose.onNodeWithTag(ChatTags.SEND).assertIsEnabled()
  }

  @Test
  fun dateLabelChangesAtMidnightWithoutANewMessage() {
    val zone = ZoneId.of("Europe/Zurich")
    var now = Instant.parse("2030-01-01T22:59:59Z")
    val clock =
        object : Clock() {
          override fun getZone() = zone

          override fun withZone(zone: ZoneId) = Clock.fixed(now, zone)

          override fun instant() = now
        }
    messages.seedMessage(EVENT, group.id, "before-midnight", PEER, "Late message", now)
    show(clock = clock)
    text(R.string.events_today).assertIsDisplayed()

    now = now.plusSeconds(1)
    main.dispatcher.scheduler.advanceTimeBy(1_001)
    settle()
    text(R.string.events_today).assertDoesNotExist()
    compose.onNodeWithText("Late message").assertIsDisplayed()
  }

  @Test
  fun aPrivateEventDoesNotHideChatAndAdjacentMessagesShareTheSenderLabel() {
    events.deniedIds += EVENT
    messages.seedMessage(EVENT, group.id, "first", PEER, "First message", NOW)
    messages.seedMessage(EVENT, group.id, "second", PEER, "Second message", NOW.plusSeconds(1))
    show()

    text(R.string.group_chat_title).assertIsDisplayed()
    compose.onNodeWithText("First message").assertIsDisplayed()
    compose.onNodeWithText("Second message").assertIsDisplayed()
    compose.onAllNodesWithText("Test Peer").assertCountEquals(1)
  }

  private fun show(
      repository: MessageRepository = messages,
      groupRepository: GroupRepository = groups,
      clock: Clock = Clock.fixed(NOW, ZoneId.of("Europe/Zurich")),
      profileRepository: PublicProfileRepository = profiles,
  ): GroupChatViewModel {
    val viewModel =
        GroupChatViewModel(
            SavedStateHandle(
                mapOf(
                    EventDetailViewModel.EVENT_ID_ARG to EVENT,
                    GroupChatViewModel.GROUP_ID_ARG to group.id,
                )
            ),
            groupRepository,
            repository,
            profileRepository,
            auth,
            network,
            events,
            clock,
        )
    compose.setContent { PolySocialTheme { GroupChatRoute(onBack = {}, viewModel = viewModel) } }
    settle()
    return viewModel
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
