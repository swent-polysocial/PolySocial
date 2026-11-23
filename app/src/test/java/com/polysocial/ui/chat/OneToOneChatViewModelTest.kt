// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #431).
package com.polysocial.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.chat.FakeMessageRepository
import com.polysocial.model.chat.FakeOneToOneChatRepository
import com.polysocial.model.chat.MessagesResult
import com.polysocial.model.chat.OneToOneChatRepository
import com.polysocial.model.chat.SendMessageResult
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.friendshipId
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OneToOneChatViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val friends = FakeFriendRepository(currentUid = ME)
  private val chats =
      FakeOneToOneChatRepository(currentUid = ME).apply { friendshipOf = friends::friendship }
  private val profiles =
      FakePublicProfileRepository(
          mutableMapOf(FRIEND to PublicProfile(FRIEND, "Léa Martin", "IN", "BA3"))
      )
  private val auth = FakeAuthRepository(AuthUser(ME, "me@epfl.ch", true))
  private val network = FakeNetworkMonitor()
  private val clock = Clock.fixed(NOW, ZoneId.of("Europe/Zurich"))

  private fun viewModel(pairId: String = PAIR) =
      OneToOneChatViewModel(
          SavedStateHandle(mapOf(OneToOneChatViewModel.PAIR_ID_ARG to pairId)),
          chats,
          friends,
          profiles,
          auth,
          network,
          clock,
      )

  /** A view model whose state is collected, as the screen does, and settled. */
  private fun TestScope.started(pairId: String = PAIR): OneToOneChatViewModel {
    val viewModel = viewModel(pairId)
    backgroundScope.launch { viewModel.state.collect {} }
    runCurrent()
    return viewModel
  }

  private fun OneToOneChatViewModel.history() = state.value.history as ChatHistory.Ready

  private fun befriend() = friends.seedAcceptedFriendship(FRIEND, ME)

  // ---- The friend and the history ----

  @Test
  fun aFriendsChat_showsTheirName_anEmptyHistory_andAnOpenComposer() =
      runTest(main.dispatcher) {
        befriend()

        val viewModel = started()

        assertEquals(ChatFriend.Named("Léa Martin"), viewModel.state.value.friend)
        assertEquals(emptyList<Any>(), viewModel.history().messages)
        assertEquals(ChatAccess.OPEN, viewModel.state.value.access)
        assertTrue(viewModel.state.value.canWrite)
      }

  @Test
  fun theHistory_isInTheServersOrder() =
      runTest(main.dispatcher) {
        befriend()
        chats.seedChat(PAIR)
        chats.seedMessage(PAIR, "m2", ME, "Perfect, see you there!", NOW.plusSeconds(60))
        chats.seedMessage(PAIR, "m1", FRIEND, "Meet at 20:00?", NOW)

        val viewModel = started()

        assertEquals(
            listOf("Meet at 20:00?", "Perfect, see you there!"),
            viewModel.history().messages.map { it.text },
        )
      }

  @Test
  fun aProfileThatCantBeRead_stillOpensTheChat() =
      runTest(main.dispatcher) {
        befriend()
        profiles.failure = FakePublicProfileRepository.Failure.Unexpected

        val viewModel = started()

        assertEquals(ChatFriend.Unavailable, viewModel.state.value.friend)
        assertTrue(viewModel.state.value.canWrite)
      }

  @Test
  fun aReadFailure_isFailed_andRetryReadsAgain() =
      runTest(main.dispatcher) {
        befriend()
        chats.readFailure = FakeMessageRepository.ReadFailure.NETWORK
        val viewModel = started()
        assertEquals(ChatHistory.Failed, viewModel.state.value.history)
        assertFalse(viewModel.state.value.canWrite)

        chats.readFailure = null
        viewModel.retry()
        runCurrent()

        assertEquals(emptyList<Any>(), viewModel.history().messages)
      }

  @Test
  fun aRead_thatNeverAnswers_failsAfterTenSeconds() =
      runTest(main.dispatcher) {
        befriend()
        // No message listener answers: the chat stays loading until the timeout.
        val silent =
            OneToOneChatViewModel(
                SavedStateHandle(mapOf(OneToOneChatViewModel.PAIR_ID_ARG to PAIR)),
                SilentChats(chats),
                friends,
                profiles,
                auth,
                network,
                clock,
            )
        backgroundScope.launch { silent.state.collect {} }
        runCurrent()
        assertEquals(ChatHistory.Loading, silent.state.value.history)

        advanceTimeBy(9_999)
        assertEquals(ChatHistory.Loading, silent.state.value.history)
        advanceTimeBy(2)

        assertEquals(ChatHistory.Failed, silent.state.value.history)
      }

  @Test
  fun aPairIdThatIsntTheStudents_isFailed_withoutAName() =
      runTest(main.dispatcher) {
        val viewModel = started(pairId = checkNotNull(friendshipId(FRIEND, "someone-else")))

        assertEquals(ChatHistory.Failed, viewModel.state.value.history)
        assertEquals(ChatFriend.Unavailable, viewModel.state.value.friend)
        assertEquals(emptyList<Any>(), profiles.requestedUids)
      }

  // ---- Sending ----

  @Test
  fun theFirstMessage_startsTheChat_andClearsTheDraft() =
      runTest(main.dispatcher) {
        befriend()
        val viewModel = started()

        viewModel.edit("  Coffee at 4?  ")
        viewModel.send()
        runCurrent()

        assertEquals(listOf(ME, FRIEND).sorted(), chats.uidsOf(PAIR))
        assertEquals(listOf("Coffee at 4?"), chats.messagesOf(PAIR).map { it.text })
        assertEquals(ChatDraft(), viewModel.state.value.draft)
      }

  @Test
  fun anOfflineSend_keepsTheDraft_andItsRetryUsesTheSameMessageId() =
      runTest(main.dispatcher) {
        befriend()
        val viewModel = started()
        chats.online = false

        viewModel.edit("On my way")
        viewModel.send()
        runCurrent()
        val offline = viewModel.state.value.draft
        assertEquals(ChatDraft.Stage.OFFLINE, offline.stage)
        assertEquals("On my way", offline.text)

        chats.online = true
        viewModel.send()
        runCurrent()

        assertEquals(listOf(offline.id), chats.messagesOf(PAIR).map { it.id })
        assertEquals(ChatDraft(), viewModel.state.value.draft)
      }

  @Test
  fun aFailedSend_staysEditable_andAnEditGetsANewId() =
      runTest(main.dispatcher) {
        befriend()
        val viewModel = started()
        chats.sendFailure = SendMessageResult.UnexpectedError

        viewModel.edit("Hi")
        viewModel.send()
        runCurrent()
        val failed = viewModel.state.value.draft
        assertEquals(ChatDraft.Stage.FAILED, failed.stage)

        viewModel.edit("Hi there")
        runCurrent()

        assertEquals("Hi there", viewModel.state.value.draft.text)
        assertEquals(null, viewModel.state.value.draft.id)
      }

  @Test
  fun nothingIsSent_whileTheFriendshipIsStillUnknown_orAfterTheAccountChanged() =
      runTest(main.dispatcher) {
        befriend()
        friends.readFromCache = true
        friends.deleteFriendship(PAIR)
        val viewModel = started()
        assertEquals(ChatAccess.CHECKING, viewModel.state.value.access)

        viewModel.edit("Hi")
        viewModel.send()
        runCurrent()
        assertEquals(emptyList<Any>(), chats.messagesOf(PAIR))

        befriend()
        runCurrent()
        auth.user = AuthUser("another-account", "other@epfl.ch", true)
        viewModel.send()
        runCurrent()

        assertEquals(emptyList<Any>(), chats.messagesOf(PAIR))
      }

  // ---- Read-only ----

  @Test
  fun removingTheFriend_makesTheChatReadOnly_live_andKeepsTheHistory() =
      runTest(main.dispatcher) {
        befriend()
        chats.seedChat(PAIR)
        chats.seedMessage(PAIR, "m1", FRIEND, "See you", NOW)
        val viewModel = started()
        assertFalse(viewModel.state.value.readOnly)

        friends.deleteFriendship(PAIR)
        runCurrent()

        assertEquals(ChatAccess.NOT_FRIENDS, viewModel.state.value.access)
        assertTrue(viewModel.state.value.readOnly)
        assertEquals(listOf("See you"), viewModel.history().messages.map { it.text })

        befriend()
        runCurrent()
        assertEquals(ChatAccess.OPEN, viewModel.state.value.access)
      }

  @Test
  fun aPendingRequest_isReadOnly() =
      runTest(main.dispatcher) {
        friends.seedFriendship(
            Friendship(
                PAIR,
                listOf(ME, FRIEND).sorted(),
                FRIEND,
                FriendshipStatus.PENDING,
                NOW,
                null,
            )
        )

        val viewModel = started()

        assertEquals(ChatAccess.NOT_FRIENDS, viewModel.state.value.access)
      }

  @Test
  fun aCachedNotFriends_waitsForTheServer() =
      runTest(main.dispatcher) {
        friends.readFromCache = true

        val viewModel = started()

        assertEquals(ChatAccess.CHECKING, viewModel.state.value.access)
        assertFalse(viewModel.state.value.readOnly)
        assertFalse(viewModel.state.value.canWrite)

        friends.readFromCache = false
        runCurrent()
        assertEquals(ChatAccess.NOT_FRIENDS, viewModel.state.value.access)
      }

  @Test
  fun friendshipsThatCantBeRead_leaveTheComposerToTheRules() =
      runTest(main.dispatcher) {
        friends.readFailure = true

        val viewModel = started()

        assertEquals(ChatAccess.OPEN, viewModel.state.value.access)
      }

  @Test
  fun aDeletedAccount_isNamedSo_andTheChatIsReadOnly() =
      runTest(main.dispatcher) {
        // What deleteAccount leaves: the friendship gone, the chat listing only the student.
        chats.seedChat(PAIR, uids = listOf(ME))
        chats.seedMessage(PAIR, "m1", "", "Bye", NOW)

        val viewModel = started()

        assertEquals(ChatFriend.Deleted, viewModel.state.value.friend)
        assertEquals(ChatAccess.ACCOUNT_DELETED, viewModel.state.value.access)
        assertEquals(listOf("Bye"), viewModel.history().messages.map { it.text })
      }

  @Test
  fun offline_isReported_andTheCachedHistoryShows() =
      runTest(main.dispatcher) {
        befriend()
        chats.seedChat(PAIR)
        chats.seedMessage(PAIR, "m1", FRIEND, "Saved", NOW)
        network.online = false
        chats.readFromCache = true

        val viewModel = started()

        assertTrue(viewModel.state.value.offline)
        assertEquals(listOf("Saved"), viewModel.history().messages.map { it.text })
      }

  private companion object {
    const val ME = "me-uid"
    const val FRIEND = "lea-uid"
    val PAIR = checkNotNull(friendshipId(ME, FRIEND))
    val NOW: Instant = Instant.parse("2030-02-01T12:00:00Z")
  }
}

/** [chats], except that the message listener never answers. */
private class SilentChats(chats: FakeOneToOneChatRepository) : OneToOneChatRepository by chats {
  override fun observeMessages(pairId: String): Flow<MessagesResult> = flow { awaitCancellation() }
}
