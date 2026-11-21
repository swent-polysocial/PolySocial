// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #428).
package com.polysocial.model.chat

import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipStatus
import java.time.Instant
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fake follows the [OneToOneChatRepository] contracts the chat screen (#431) and the Chats tab
 * (#432) will be tested against.
 */
class FakeOneToOneChatRepositoryTest {
  private val friends = FakeFriendRepository(currentUid = ALICE)

  private val repository =
      FakeOneToOneChatRepository(currentUid = ALICE).apply { friendshipOf = friends::friendship }

  private fun friendship(status: FriendshipStatus, id: String = PAIR) =
      Friendship(
          id = id,
          uids = id.split("~"),
          requestedBy = id.substringBefore("~"),
          status = status,
          createdAt = Instant.parse("2026-11-20T10:00:00Z"),
          acceptedAt =
              if (status == FriendshipStatus.ACCEPTED) Instant.parse("2026-11-20T11:00:00Z")
              else null,
      )

  private fun befriend(id: String = PAIR) =
      friends.seedFriendship(friendship(FriendshipStatus.ACCEPTED, id))

  /** The first result after Loading. */
  private suspend fun myChats() = repository.observeMyChats().drop(1).first()

  private suspend fun messages(pairId: String = PAIR) =
      repository.observeMessages(pairId).drop(1).first()

  private suspend fun latest(pairId: String = PAIR) =
      repository.observeLatestMessage(pairId).drop(1).first()

  @Test
  fun theFirstMessage_createsTheChatWithBothUids_andStoresTheTrimmedText() = runTest {
    befriend()

    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "  Coffee at 4?\n"))

    assertEquals(listOf(ALICE, BOB), repository.uidsOf(PAIR))
    val message = repository.messagesOf(PAIR).single()
    assertEquals(ALICE, message.senderId)
    assertEquals("Coffee at 4?", message.text)
    assertEquals(MessagesResult.Success(listOf(message), fromCache = false), messages())
    assertEquals(
        MyOneToOneChatsResult.Success(listOf(OneToOneChat(PAIR, BOB)), fromCache = false),
        myChats(),
    )
  }

  @Test
  fun eitherStudent_sendsInTheSameChat_whichKeepsItsUids() = runTest {
    befriend()
    repository.sendMessage(PAIR, "Hi Bob")

    repository.currentUid = BOB
    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "Hi Alice"))

    assertEquals(listOf(ALICE, BOB), repository.uidsOf(PAIR))
    assertEquals(listOf(ALICE, BOB), repository.messagesOf(PAIR).map { it.senderId })
    assertEquals(
        MyOneToOneChatsResult.Success(listOf(OneToOneChat(PAIR, ALICE)), fromCache = false),
        myChats(),
    )
  }

  @Test
  fun observeMyChats_listsOnlyTheStudentsChats_byPairId_withOrWithoutAMessage() = runTest {
    repository.seedChat("alice~dave")
    repository.seedChat("Zed~alice")
    repository.seedChat("bob~carol")
    repository.seedChat(PAIR)

    assertEquals(
        MyOneToOneChatsResult.Success(
            listOf(
                OneToOneChat("Zed~alice", "Zed"),
                OneToOneChat(PAIR, BOB),
                OneToOneChat("alice~dave", "dave"),
            ),
            fromCache = false,
        ),
        myChats(),
    )
  }

  @Test
  fun aChatWhoseOtherAccountWasDeleted_isListedWithoutItsUid_andStaysReadable() = runTest {
    repository.seedChat(PAIR, uids = listOf(ALICE))
    val kept = repository.seedMessage(PAIR, "m1", DELETED_MESSAGE_SENDER, "See you", Instant.EPOCH)

    assertEquals(
        MyOneToOneChatsResult.Success(listOf(OneToOneChat(PAIR, null)), fromCache = false),
        myChats(),
    )
    assertEquals(MessagesResult.Success(listOf(kept), fromCache = false), messages())
    // The deleted account's friendships went with it.
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hello?"))
    assertEquals(listOf(kept), repository.messagesOf(PAIR))
  }

  @Test
  fun observeMyChats_showsAChatOnceItsFirstMessageCreatesIt() = runTest {
    befriend()
    val results = mutableListOf<MyOneToOneChatsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeMyChats().toList(results)
        }

    repository.sendMessage(PAIR, "Hi")
    // Another message changes no chat, so nothing more is emitted.
    repository.sendMessage(PAIR, "Still there?")
    collecting.cancel()

    assertEquals(
        listOf(
            MyOneToOneChatsResult.Loading,
            MyOneToOneChatsResult.Success(emptyList(), fromCache = false),
            MyOneToOneChatsResult.Success(listOf(OneToOneChat(PAIR, BOB)), fromCache = false),
        ),
        results,
    )
  }

  @Test
  fun withoutAnAcceptedFriendship_sendingIsUnexpected_andCreatesNothing() = runTest {
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))

    friends.seedFriendship(friendship(FriendshipStatus.PENDING))
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))
    repository.currentUid = BOB
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))

    assertNull(repository.uidsOf(PAIR))
    assertTrue(repository.messagesOf(PAIR).isEmpty())
  }

  @Test
  fun aFriendshipWithSomeoneElse_doesNotLetTheStudentSendHere() = runTest {
    befriend("alice~carol")

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))
    assertEquals(SendMessageResult.Sent, repository.sendMessage("alice~carol", "Hi"))
  }

  @Test
  fun afterUnfriending_bothStillReadTheChat_butNeitherSends_untilFriendsAgain() = runTest {
    befriend()
    repository.sendMessage(PAIR, "Before")
    val before = repository.messagesOf(PAIR)

    friends.deleteFriendship(PAIR)
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "After"))
    repository.currentUid = BOB
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "After"))
    assertEquals(MessagesResult.Success(before, fromCache = false), messages())
    assertEquals(
        MyOneToOneChatsResult.Success(listOf(OneToOneChat(PAIR, ALICE)), fromCache = false),
        myChats(),
    )

    befriend()
    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "Friends again"))
    assertEquals(listOf("Before", "Friends again"), repository.messagesOf(PAIR).map { it.text })
  }

  @Test
  fun aRetryOfAStoredDraft_isSentWithoutADuplicate_evenAfterUnfriending() = runTest {
    befriend()
    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "Hi", DRAFT))

    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, " Hi ", DRAFT))
    friends.deleteFriendship(PAIR)
    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "Hi", DRAFT))

    val stored = repository.messagesOf(PAIR).single()
    assertEquals(DRAFT, stored.id)
    assertEquals("Hi", stored.text)
  }

  @Test
  fun aRetryIdHoldingAnotherTextOrSender_isUnexpected_andChangesNothing() = runTest {
    befriend()
    repository.sendMessage(PAIR, "Hi", DRAFT)
    val stored = repository.messagesOf(PAIR)

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hey", DRAFT))
    repository.currentUid = BOB
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi", DRAFT))

    assertEquals(stored, repository.messagesOf(PAIR))
  }

  @Test
  fun withoutARetryId_eachSendStoresANewMessage() = runTest {
    befriend()

    repository.sendMessage(PAIR, "Hi")
    repository.sendMessage(PAIR, "Hi")

    assertEquals(2, repository.messagesOf(PAIR).map { it.id }.distinct().size)
  }

  @Test
  fun aBlankOrTooLongText_isInvalid_beforeAnyOtherCheck() = runTest {
    repository.currentUid = null
    repository.online = false

    assertEquals(
        SendMessageResult.Invalid(MessageTextError.BLANK),
        repository.sendMessage(PAIR, " \n"),
    )
    assertEquals(
        SendMessageResult.Invalid(MessageTextError.TOO_LONG),
        repository.sendMessage(PAIR, "a".repeat(MAX_MESSAGE_LENGTH + 1)),
    )
    assertNull(repository.uidsOf(PAIR))
  }

  @Test
  fun idsThatAreNotTheStudentsPair_orNobodySignedIn_areUnexpected_beforeTheConnection() = runTest {
    befriend()
    friends.seedFriendship(friendship(FriendshipStatus.ACCEPTED, id = "bob~carol"))
    repository.online = false

    for (pairId in listOf("bob~carol", "bob~alice", "alice~bob~", "alice", "alice~alice")) {
      assertEquals(pairId, SendMessageResult.UnexpectedError, repository.sendMessage(pairId, "Hi"))
    }
    for (messageId in listOf("", " ", "messages/m1")) {
      assertEquals(
          messageId,
          SendMessageResult.UnexpectedError,
          repository.sendMessage(PAIR, "Hi", messageId),
      )
    }
    repository.currentUid = null
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))
  }

  @Test
  fun offline_isANetworkError_beforeTheFriendship_andStoresNothing() = runTest {
    repository.online = false

    assertEquals(SendMessageResult.NetworkError, repository.sendMessage(PAIR, "Hi"))
    befriend()
    assertEquals(SendMessageResult.NetworkError, repository.sendMessage(PAIR, "Hi"))

    assertNull(repository.uidsOf(PAIR))
    assertTrue(repository.messagesOf(PAIR).isEmpty())
  }

  @Test
  fun aSendFailure_isReturnedAfterTheConnection_andStoresNothing() = runTest {
    befriend()
    repository.sendFailure = SendMessageResult.UnexpectedError
    repository.online = false
    assertEquals(SendMessageResult.NetworkError, repository.sendMessage(PAIR, "Hi"))

    repository.online = true
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))
    repository.sendFailure = SendMessageResult.NetworkError
    assertEquals(SendMessageResult.NetworkError, repository.sendMessage(PAIR, "Hi"))

    assertNull(repository.uidsOf(PAIR))
    assertTrue(repository.messagesOf(PAIR).isEmpty())
  }

  @Test
  fun aChatThatDoesNotExistYet_readsAsNoMessages_forEitherStudentOfThePair() = runTest {
    assertEquals(MessagesResult.Success(emptyList(), fromCache = false), messages())
    assertEquals(LatestMessageResult.Success(null, fromCache = false), latest())
    repository.currentUid = BOB
    assertEquals(MessagesResult.Success(emptyList(), fromCache = false), messages())
  }

  @Test
  fun observeMessages_emitsEveryNewMessage_oldestFirst() = runTest {
    befriend()
    befriend("alice~carol")
    val results = mutableListOf<MessagesResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeMessages(PAIR).toList(results)
        }

    repository.sendMessage(PAIR, "First")
    repository.sendMessage("alice~carol", "Elsewhere")
    repository.sendMessage(PAIR, "Second")
    collecting.cancel()

    assertEquals(
        listOf(listOf(), listOf("First"), listOf("First", "Second")),
        results.drop(1).map { result ->
          (result as MessagesResult.Success).messages.map { it.text }
        },
    )
  }

  @Test
  fun aStudentOutsideThePair_orAnIdThatIsNotAPairId_cannotRead() = runTest {
    repository.seedChat(PAIR)
    repository.seedMessage(PAIR, "m1", BOB, "Hi", Instant.EPOCH)
    val unexpected = listOf(MessagesResult.Loading, MessagesResult.UnexpectedError)

    for (pairId in listOf("bob~alice", "alice~bob~", "alice", "chats/alice~bob")) {
      assertEquals(pairId, unexpected, repository.observeMessages(pairId).toList())
    }
    repository.currentUid = CAROL
    assertEquals(unexpected, repository.observeMessages(PAIR).toList())
    assertEquals(
        listOf(LatestMessageResult.Loading, LatestMessageResult.UnexpectedError),
        repository.observeLatestMessage(PAIR).toList(),
    )
    assertEquals(
        MyOneToOneChatsResult.Success(emptyList(), fromCache = false),
        myChats(),
    )
  }

  @Test
  fun nobodySignedIn_cannotRead() = runTest {
    repository.currentUid = null

    assertEquals(
        listOf(MyOneToOneChatsResult.Loading, MyOneToOneChatsResult.UnexpectedError),
        repository.observeMyChats().toList(),
    )
    assertEquals(MessagesResult.UnexpectedError, messages())
    assertEquals(LatestMessageResult.UnexpectedError, latest())
  }

  @Test
  fun readFailures_endEveryFlowWithItsOwnError() = runTest {
    repository.readFailure = FakeMessageRepository.ReadFailure.NETWORK
    assertEquals(
        listOf(MyOneToOneChatsResult.Loading, MyOneToOneChatsResult.NetworkError),
        repository.observeMyChats().toList(),
    )
    assertEquals(
        listOf(MessagesResult.Loading, MessagesResult.NetworkError),
        repository.observeMessages(PAIR).toList(),
    )
    assertEquals(
        listOf(LatestMessageResult.Loading, LatestMessageResult.NetworkError),
        repository.observeLatestMessage(PAIR).toList(),
    )

    repository.readFailure = FakeMessageRepository.ReadFailure.UNEXPECTED
    assertEquals(MyOneToOneChatsResult.UnexpectedError, myChats())
    assertEquals(MessagesResult.UnexpectedError, messages())
    assertEquals(LatestMessageResult.UnexpectedError, latest())
  }

  @Test
  fun theObserveFlows_readTheUserAndReadFailureWhenCollectionStarts() = runTest {
    repository.seedChat(PAIR)
    val chats = mutableListOf<MyOneToOneChatsResult>()
    val history = mutableListOf<MessagesResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          launch { repository.observeMyChats().toList(chats) }
          launch { repository.observeMessages(PAIR).toList(history) }
        }

    repository.currentUid = CAROL
    repository.readFailure = FakeMessageRepository.ReadFailure.UNEXPECTED
    repository.seedChat("alice~dave")
    repository.seedMessage(PAIR, "m1", BOB, "Hi", Instant.EPOCH)
    collecting.cancel()

    assertEquals(
        MyOneToOneChatsResult.Success(
            listOf(OneToOneChat(PAIR, BOB), OneToOneChat("alice~dave", "dave")),
            fromCache = false,
        ),
        chats.last(),
    )
    assertEquals(listOf("Hi"), (history.last() as MessagesResult.Success).messages.map { it.text })
  }

  @Test
  fun aChangeOfTheCacheFlag_emitsANewResult() = runTest {
    repository.seedChat(PAIR)
    val chats = mutableListOf<MyOneToOneChatsResult>()
    val latestMessages = mutableListOf<LatestMessageResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          launch { repository.observeMyChats().toList(chats) }
          launch { repository.observeLatestMessage(PAIR).toList(latestMessages) }
        }

    repository.readFromCache = true
    collecting.cancel()

    assertEquals(
        MyOneToOneChatsResult.Success(listOf(OneToOneChat(PAIR, BOB)), fromCache = true),
        chats.last(),
    )
    assertEquals(
        listOf(
            LatestMessageResult.Loading,
            LatestMessageResult.Success(null, fromCache = false),
            LatestMessageResult.Success(null, fromCache = true),
        ),
        latestMessages,
    )
  }

  @Test
  fun observeLatestMessage_emitsTheChatsNewestMessage_onlyWhenItChanges() = runTest {
    befriend()
    befriend("alice~carol")
    val time = Instant.parse("2030-01-01T10:00:00Z")
    val results = mutableListOf<LatestMessageResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeLatestMessage(PAIR).toList(results)
        }

    val first = repository.seedMessage(PAIR, "b", BOB, "First", time)
    // Neither an older message nor another chat's changes the newest one.
    repository.seedMessage(PAIR, "a", BOB, "Older", time.minusSeconds(60))
    repository.sendMessage("alice~carol", "Elsewhere")
    repository.sendMessage(PAIR, "Newest")
    collecting.cancel()

    assertEquals(
        listOf(
            LatestMessageResult.Loading,
            LatestMessageResult.Success(null, fromCache = false),
            LatestMessageResult.Success(first, fromCache = false),
            LatestMessageResult.Success(repository.messagesOf(PAIR).last(), fromCache = false),
        ),
        results,
    )
    assertEquals("Newest", repository.messagesOf(PAIR).last().text)
  }

  @Test
  fun seedChat_refusesAChatThatIsNotAPairsOrNamesSomeoneElse() {
    for (pairId in listOf("bob~alice", "alice~bob~", "alice")) {
      assertThrows(IllegalArgumentException::class.java) { repository.seedChat(pairId) }
    }
    assertThrows(IllegalArgumentException::class.java) {
      repository.seedChat(PAIR, uids = listOf(ALICE, CAROL))
    }
    assertThrows(IllegalArgumentException::class.java) {
      repository.seedChat(PAIR, uids = emptyList())
    }
    assertNull(repository.uidsOf(PAIR))
  }

  private companion object {
    const val ALICE = "alice"
    const val BOB = "bob"
    const val CAROL = "carol"
    const val PAIR = "alice~bob"
    const val DRAFT = "draft-1"
  }
}
