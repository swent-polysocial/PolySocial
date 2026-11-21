// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #428; the Firestore mocks shared
// with the group chat's tests in FirestoreChatMocks.kt).
// Contributors: Claude (Opus 5.5, Anthropic) (tester: a rerun finding its stored message, a
// cancelled first-message send, Retry of the message listeners, #428).
package com.polysocial.model.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreOneToOneChatRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val chats = mockk<CollectionReference>()
  private val chat = mockk<DocumentReference>()
  private val messages = mockk<CollectionReference>()
  private val newMessage = mockk<DocumentReference>()
  private val draftMessage = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>()

  private val auth = FakeAuthRepository(AuthUser(ALICE, "alice@epfl.ch", true))
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private val repository = FirestoreOneToOneChatRepository(db, auth, network)

  /** Whether the chat exists when an attempt reads it, attempt after attempt. */
  private val chatExists = ArrayDeque<Boolean>()

  /** What each message ID holds when an attempt reads it, null for nothing. */
  private val storedMessages = mutableMapOf<DocumentReference, Map<String, Any?>?>()

  /** How each attempt's commit fails, in order; an attempt beyond them commits. */
  private val commitFailures = ArrayDeque<Exception>()

  /** Every attempt's options and writes, in order. */
  private val attempts = mutableListOf<TransactionOptions>()
  private val writes = mutableListOf<Pair<DocumentReference, Any>>()
  private val reads = mutableListOf<DocumentReference>()

  @Before
  fun stubFirestore() {
    every { db.collection(CHATS_COLLECTION) } returns chats
    every { chats.document(PAIR) } returns chat
    every { chat.collection(MESSAGES_COLLECTION) } returns messages
    every { messages.document() } returns newMessage
    every { messages.document(DRAFT) } returns draftMessage
    every { newMessage.id } returns "generated"
    every { draftMessage.id } returns DRAFT
    every { transaction.get(any()) } answers
        {
          val read = firstArg<DocumentReference>()
          reads += read
          val exists = if (read === chat) chatExists.removeFirstOrNull() ?: false else null
          val data = storedMessages[read]
          mockk<DocumentSnapshot>().also {
            every { it.exists() } returns (exists ?: (data != null))
            every { it.data } returns data
          }
        }
    every { transaction.set(any<DocumentReference>(), any()) } answers
        {
          writes += firstArg<DocumentReference>() to secondArg<Any>()
          transaction
        }
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<SendMessageResult>>())
    } answers
        {
          attempts += firstArg<TransactionOptions>()
          val written = writes.size
          val result = secondArg<Transaction.Function<SendMessageResult>>().apply(transaction)
          val failure = commitFailures.removeFirstOrNull()
          if (failure == null) Tasks.forResult(result)
          else {
            // A failed commit writes nothing.
            while (writes.size > written) writes.removeAt(writes.lastIndex)
            Tasks.forException(failure)
          }
        }
  }

  private fun verifyNoFirestore() {
    verify(exactly = 0) { db.collection(any()) }
    verify(exactly = 0) {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<SendMessageResult>>())
    }
  }

  // sendMessage

  @Test
  fun theFirstMessage_createsTheChatWithThePairsUids_inTheSameTransaction() = runTest {
    chatExists += false

    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "  Coffee at 4?\n"))

    assertEquals(
        listOf(
            chat to newChatFirestoreMap(listOf(ALICE, BOB)),
            newMessage to newMessageFirestoreMap(ALICE, "Coffee at 4?"),
        ),
        writes,
    )
    assertEquals(listOf(1), attempts.map { it.maxAttempts })
  }

  @Test
  fun theHigherUidsStudent_createsItWithTheUidsInTheIdsOrder() = runTest {
    auth.user = AuthUser(BOB, "bob@epfl.ch", true)
    chatExists += false

    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "Hi"))

    assertEquals(chat to newChatFirestoreMap(listOf(ALICE, BOB)), writes.first())
    assertEquals(newMessage to newMessageFirestoreMap(BOB, "Hi"), writes.last())
  }

  @Test
  fun inAnExistingChat_onlyTheMessageIsWritten_withoutReadingIt() = runTest {
    chatExists += true

    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "Hi"))

    assertEquals(listOf(newMessage to newMessageFirestoreMap(ALICE, "Hi")), writes)
    assertEquals(listOf(chat), reads)
  }

  @Test
  fun aNewRetryId_isReadThenWritten_underThatId() = runTest {
    chatExists += true

    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "Hi", DRAFT))

    assertEquals(listOf(chat, draftMessage), reads)
    assertEquals(listOf(draftMessage to newMessageFirestoreMap(ALICE, "Hi")), writes)
  }

  @Test
  fun aRetryIdHoldingTheSameDraft_isSentWithoutAWrite_andAnythingElseIsUnexpected() = runTest {
    storedMessages[draftMessage] = storedMessage(ALICE, "Hi")
    repeat(4) { chatExists += true }

    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, " Hi ", DRAFT))
    assertEquals(
        SendMessageResult.UnexpectedError,
        repository.sendMessage(PAIR, "Hello", DRAFT),
    )
    storedMessages[draftMessage] = storedMessage(BOB, "Hi")
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi", DRAFT))
    storedMessages[draftMessage] = mapOf("text" to "Hi")
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi", DRAFT))

    assertTrue("Nothing may be written: $writes", writes.isEmpty())
  }

  @Test
  fun aRetryAfterALostAnswer_findsTheStoredDraft_andWritesNothing() = runTest {
    chatExists += false
    commitFailures += firestoreFailure(Code.DEADLINE_EXCEEDED)
    assertEquals(SendMessageResult.NetworkError, repository.sendMessage(PAIR, "Hi", DRAFT))

    // The server had stored it after all: Try again under the same ID finds it.
    storedMessages[draftMessage] = storedMessage(ALICE, "Hi")
    chatExists += true
    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "Hi", DRAFT))

    assertTrue("Nothing may be written: $writes", writes.isEmpty())
    assertEquals(2, attempts.size)
  }

  @Test
  fun aBlankOrTooLongText_isInvalid_beforeAnyOtherCheck() = runTest {
    auth.user = null
    online = false

    assertEquals(
        SendMessageResult.Invalid(MessageTextError.BLANK),
        repository.sendMessage(PAIR, " \n "),
    )
    assertEquals(
        SendMessageResult.Invalid(MessageTextError.TOO_LONG),
        repository.sendMessage(PAIR, "a".repeat(MAX_MESSAGE_LENGTH + 1)),
    )
    verifyNoFirestore()
  }

  @Test
  fun aThousandCharactersOnceTrimmed_isSent() = runTest {
    chatExists += true
    val longest = "a".repeat(MAX_MESSAGE_LENGTH)

    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, " $longest "))

    assertEquals(listOf(newMessage to newMessageFirestoreMap(ALICE, longest)), writes)
  }

  @Test
  fun idsThatAreNotTheStudentsPair_areUnexpected_beforeTheConnectionAndFirestore() = runTest {
    online = false

    for (pairId in listOf("bob~carol", "bob~alice", "alice~bob~", "alice", "alice~alice", "")) {
      assertEquals(pairId, SendMessageResult.UnexpectedError, repository.sendMessage(pairId, "Hi"))
    }
    for (messageId in listOf("", " ", "messages/m1")) {
      assertEquals(
          messageId,
          SendMessageResult.UnexpectedError,
          repository.sendMessage(PAIR, "Hi", messageId),
      )
    }
    verifyNoFirestore()
  }

  @Test
  fun nobodySignedIn_isUnexpected_withoutFirestore() = runTest {
    auth.user = null

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))

    verifyNoFirestore()
  }

  @Test
  fun offline_isANetworkError_withoutStartingAWrite() = runTest {
    online = false

    assertEquals(SendMessageResult.NetworkError, repository.sendMessage(PAIR, "Hi"))

    verifyNoFirestore()
  }

  @Test
  fun losingTheFirstMessageRace_runsOnceMore_andAddsOnlyTheMessageToTheChat() = runTest {
    for (code in listOf(Code.ALREADY_EXISTS, Code.PERMISSION_DENIED, Code.ABORTED)) {
      writes.clear()
      attempts.clear()
      reads.clear()
      // The first attempt finds no chat; the other student's commit creates it before this one.
      chatExists += listOf(false, true)
      commitFailures += firestoreFailure(code)

      assertEquals(code.name, SendMessageResult.Sent, repository.sendMessage(PAIR, "Hi"))

      assertEquals(code.name, listOf(1, 1), attempts.map { it.maxAttempts })
      assertEquals(
          code.name,
          listOf(newMessage to newMessageFirestoreMap(ALICE, "Hi")),
          writes,
      )
      // The second attempt also reads the message under the same ID, so it can't store it twice.
      assertEquals(code.name, listOf(chat, chat, newMessage), reads)
    }
  }

  @Test
  fun losingTheRaceWithARetryId_storesTheMessageUnderThatIdOnTheSecondAttempt() = runTest {
    chatExists += listOf(false, true)
    commitFailures += firestoreFailure(Code.FAILED_PRECONDITION)

    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "Hi", DRAFT))

    assertEquals(listOf(chat, draftMessage, chat, draftMessage), reads)
    assertEquals(listOf(draftMessage to newMessageFirestoreMap(ALICE, "Hi")), writes)
  }

  @Test
  fun aRerunThatFindsTheMessageTheFailedCommitStored_isSent_withoutStoringItTwice() = runTest {
    // The first commit stored the chat and the message, but its answer was a failure that isn't
    // being offline: the rerun reads the message under the same generated ID and finds it.
    chatExists += listOf(false, true)
    commitFailures += firestoreFailure(Code.INTERNAL)
    storedMessages[newMessage] = storedMessage(ALICE, "Hi")

    assertEquals(SendMessageResult.Sent, repository.sendMessage(PAIR, "Hi"))

    assertEquals(2, attempts.size)
    assertTrue("Nothing may be written: $writes", writes.isEmpty())
  }

  @Test
  fun cancellingTheCallerWhileTheChatIsBeingCreated_startsNoRerun() = runTest {
    chatExists += false
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<SendMessageResult>>())
    } answers
        {
          attempts += firstArg<TransactionOptions>()
          // The attempt reads the missing chat and sets it, then waits for its commit.
          secondArg<Transaction.Function<SendMessageResult>>().apply(transaction)
          TaskCompletionSource<SendMessageResult>().task
        }
    var result: SendMessageResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) { result = repository.sendMessage(PAIR, "Hi") }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
    assertEquals(1, attempts.size)
  }

  @Test
  fun aSecondAttemptThatFailsToo_isReported_andIsTheLast() = runTest {
    chatExists += listOf(false, false)
    commitFailures +=
        listOf(firestoreFailure(Code.PERMISSION_DENIED), firestoreFailure(Code.PERMISSION_DENIED))
    // For example two students who aren't friends: the rules refuse both attempts.
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))
    assertEquals(2, attempts.size)

    chatExists += listOf(false, false)
    commitFailures +=
        listOf(firestoreFailure(Code.ALREADY_EXISTS), firestoreFailure(Code.UNAVAILABLE))
    assertEquals(SendMessageResult.NetworkError, repository.sendMessage(PAIR, "Hi"))
    assertEquals(4, attempts.size)
    assertTrue("Nothing may be written: $writes", writes.isEmpty())
  }

  @Test
  fun anAttemptThatTriedToCreateTheChat_andLostTheConnection_isNotRunAgain() = runTest {
    for (code in listOf(Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED)) {
      attempts.clear()
      chatExists += false
      commitFailures += firestoreFailure(code)

      assertEquals(code.name, SendMessageResult.NetworkError, repository.sendMessage(PAIR, "Hi"))
      assertEquals(code.name, 1, attempts.size)
    }
  }

  @Test
  fun aRefusedMessageInAnExistingChat_isUnexpected_andNotRunAgain() = runTest {
    // For example after the friend was removed: the chat stays, the rules refuse the message.
    chatExists += true
    commitFailures += firestoreFailure(Code.PERMISSION_DENIED)

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))

    assertEquals(1, attempts.size)
  }

  @Test
  fun aReadThatFails_isNotRunAgain() = runTest {
    // For example under a deletion marker, whose reads the rules refuse: the attempt never got
    // as far as creating the chat.
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<SendMessageResult>>())
    } returns Tasks.forException(firestoreFailure(Code.PERMISSION_DENIED))

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))

    verify(exactly = 1) {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<SendMessageResult>>())
    }
  }

  @Test
  fun anUnexpectedException_isAnUnexpectedError() = runTest {
    // For example a database blocked while the session's cache is cleared (#198).
    every { db.collection(CHATS_COLLECTION) } throws IllegalStateException("terminated")

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))
  }

  @Test
  fun aTaskCancelledWhileTheCallerRuns_isAnUnexpectedError() = runTest {
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<SendMessageResult>>())
    } returns Tasks.forCanceled()

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(PAIR, "Hi"))
  }

  @Test
  fun cancellingTheCaller_stillCancelsIt() = runTest {
    val pending: Task<SendMessageResult> = TaskCompletionSource<SendMessageResult>().task
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<SendMessageResult>>())
    } returns pending
    var result: SendMessageResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) { result = repository.sendMessage(PAIR, "Hi") }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }

  // observeMyChats, observeMessages and observeLatestMessage

  private val listeners = MockedChatListeners()

  private val myChatsQuery = mockk<Query>()

  /** Stubs exactly the query observeMyChats must build, so any other query fails the test. */
  private fun stubMyChats() {
    every { chats.whereArrayContains(CHAT_UIDS, ALICE) } returns myChatsQuery
    listeners.on(myChatsQuery)
  }

  private fun chatDocument(id: String, data: Map<String, Any?>) =
      mockk<DocumentSnapshot>().also {
        every { it.id } returns id
        every { it.data } returns data
      }

  @Test
  fun observeMyChats_listsTheStudentsReadableChats_withTheOtherStudentOrNone() = runTest {
    stubMyChats()
    val results = mutableListOf<MyOneToOneChatsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeMyChats().collect { results += it }
        }

    listeners.all
        .single()
        .onEvent(
            querySnapshot(
                chatDocument(PAIR, mapOf("uids" to listOf(ALICE, BOB))),
                chatDocument("alice~dave", mapOf("uids" to listOf(ALICE))),
                chatDocument("alice~carol", mapOf("uids" to "broken")),
                fromCache = true,
            ),
            null,
        )
    listeners.all.single().onEvent(querySnapshot(), null)
    collecting.cancel()

    assertEquals(
        listOf(
            MyOneToOneChatsResult.Loading,
            MyOneToOneChatsResult.Success(
                listOf(OneToOneChat(PAIR, BOB), OneToOneChat("alice~dave", null)),
                fromCache = true,
            ),
            MyOneToOneChatsResult.Success(emptyList(), fromCache = false),
        ),
        results,
    )
    verify { listeners.registration.remove() }
  }

  @Test
  fun observeMyChats_failures_endTheFlowWithTheirOwnError_andRemoveTheListener() = runTest {
    stubMyChats()
    val offline = collectAll(repository.observeMyChats())
    listeners.last.onEvent(null, firestoreFailure(Code.UNAVAILABLE))
    val denied = collectAll(repository.observeMyChats())
    listeners.last.onEvent(null, firestoreFailure(Code.PERMISSION_DENIED))
    val unreadable = collectAll(repository.observeMyChats())
    listeners.last.onEvent(
        mockk<QuerySnapshot>().also {
          every { it.documents } throws IllegalStateException("terminated")
        },
        null,
    )
    val empty = collectAll(repository.observeMyChats())
    listeners.last.onEvent(null, null)

    assertEquals(
        listOf(MyOneToOneChatsResult.Loading, MyOneToOneChatsResult.NetworkError),
        offline.await(),
    )
    val unexpected = listOf(MyOneToOneChatsResult.Loading, MyOneToOneChatsResult.UnexpectedError)
    assertEquals(unexpected, denied.await())
    assertEquals(unexpected, unreadable.await())
    assertEquals(unexpected, empty.await())
    verify(exactly = 4) { listeners.registration.remove() }
  }

  @Test
  fun observeMyChats_afterAFailure_aNewCollectionListensAgain() = runTest {
    stubMyChats()
    val failed = collectAll(repository.observeMyChats())
    listeners.last.onEvent(null, firestoreFailure(Code.UNAVAILABLE))
    assertEquals(
        listOf(MyOneToOneChatsResult.Loading, MyOneToOneChatsResult.NetworkError),
        failed.await(),
    )

    val retried = collectAll(repository.observeMyChats())
    listeners.last.onEvent(
        querySnapshot(chatDocument(PAIR, mapOf("uids" to listOf(ALICE, BOB)))),
        null,
    )
    listeners.last.onEvent(null, firestoreFailure(Code.UNAVAILABLE))

    assertEquals(
        listOf(
            MyOneToOneChatsResult.Loading,
            MyOneToOneChatsResult.Success(listOf(OneToOneChat(PAIR, BOB)), fromCache = false),
            MyOneToOneChatsResult.NetworkError,
        ),
        retried.await(),
    )
    assertEquals(2, listeners.all.size)
  }

  @Test
  fun observeMyChats_withoutAUserOrWhenListeningThrows_isUnexpected() = runTest {
    val unexpected = listOf(MyOneToOneChatsResult.Loading, MyOneToOneChatsResult.UnexpectedError)
    val flow = repository.observeMyChats()
    auth.user = null
    assertEquals(unexpected, flow.toList())
    verify(exactly = 0) { db.collection(any()) }

    auth.user = AuthUser(ALICE, "alice@epfl.ch", true)
    every { db.collection(CHATS_COLLECTION) } throws IllegalStateException("terminated")
    assertEquals(unexpected, repository.observeMyChats().toList())
  }

  @Test
  fun observeMessages_readsTheChatsMessagesInTheServersOrder() = runTest {
    listeners.onMessagesInServerOrder(messages)
    val results = collectAll(repository.observeMessages(PAIR))

    listeners.all
        .single()
        .onEvent(
            querySnapshot(
                messageDocument("a", storedMessage(BOB, "Hi")),
                messageDocument("broken", mapOf("text" to "No sender")),
                messageDocument("b", storedMessage(DELETED_MESSAGE_SENDER, "Bye")),
                fromCache = true,
            ),
            null,
        )
    listeners.all.single().onEvent(null, firestoreFailure(Code.PERMISSION_DENIED))

    val at = Instant.ofEpochSecond(1_900_000_000L)
    assertEquals(
        listOf(
            MessagesResult.Loading,
            MessagesResult.Success(
                listOf(
                    Message("a", BOB, "Hi", at),
                    Message("b", DELETED_MESSAGE_SENDER, "Bye", at),
                ),
                fromCache = true,
            ),
            MessagesResult.UnexpectedError,
        ),
        results.await(),
    )
    verify { listeners.registration.remove() }
  }

  @Test
  fun observeMessages_ofAChatThatDoesNotExistYet_isAnEmptyHistory() = runTest {
    listeners.onMessagesInServerOrder(messages)
    val results = collectAll(repository.observeMessages(PAIR))

    listeners.all.single().onEvent(querySnapshot(), null)
    listeners.all.single().onEvent(null, firestoreFailure(Code.DEADLINE_EXCEEDED))

    assertEquals(
        listOf(
            MessagesResult.Loading,
            MessagesResult.Success(emptyList(), fromCache = false),
            MessagesResult.NetworkError,
        ),
        results.await(),
    )
  }

  @Test
  fun observeLatestMessage_readsOnlyTheNewestMessage_orNone() = runTest {
    listeners.onNewestMessage(messages)
    val results = collectAll(repository.observeLatestMessage(PAIR))

    listeners.all.single().onEvent(querySnapshot(), null)
    listeners.all
        .single()
        .onEvent(querySnapshot(messageDocument("b", storedMessage(BOB, "Newest"))), null)
    listeners.all.single().onEvent(null, firestoreFailure(Code.UNAVAILABLE))

    assertEquals(
        listOf(
            LatestMessageResult.Loading,
            LatestMessageResult.Success(null, fromCache = false),
            LatestMessageResult.Success(
                Message("b", BOB, "Newest", Instant.ofEpochSecond(1_900_000_000L)),
                fromCache = false,
            ),
            LatestMessageResult.NetworkError,
        ),
        results.await(),
    )
  }

  @Test
  fun observeMessagesAndLatest_afterAFailure_aNewCollectionReadsTheWholeChatAgain() = runTest {
    listeners.onMessagesInServerOrder(messages)
    listeners.onNewestMessage(messages)
    val first = messageDocument("a", storedMessage(ALICE, "Hi"))
    val second = messageDocument("b", storedMessage(BOB, "Hello"))
    val failedMessages = collectAll(repository.observeMessages(PAIR))
    listeners.last.onEvent(querySnapshot(first), null)
    listeners.last.onEvent(null, firestoreFailure(Code.UNAVAILABLE))
    val failedLatest = collectAll(repository.observeLatestMessage(PAIR))
    listeners.last.onEvent(null, firestoreFailure(Code.PERMISSION_DENIED))
    assertEquals(MessagesResult.NetworkError, failedMessages.await().last())
    assertEquals(LatestMessageResult.UnexpectedError, failedLatest.await().last())

    // Retry: each new collection listens again and gets the whole chat, the message that
    // arrived while the first listener was down included.
    val messages = collectAll(repository.observeMessages(PAIR).take(2))
    listeners.last.onEvent(querySnapshot(first, second), null)
    val latest = collectAll(repository.observeLatestMessage(PAIR).take(2))
    listeners.last.onEvent(querySnapshot(second), null)

    val at = Instant.ofEpochSecond(1_900_000_000L)
    assertEquals(
        MessagesResult.Success(
            listOf(Message("a", ALICE, "Hi", at), Message("b", BOB, "Hello", at)),
            fromCache = false,
        ),
        messages.await().last(),
    )
    assertEquals(
        LatestMessageResult.Success(Message("b", BOB, "Hello", at), fromCache = false),
        latest.await().last(),
    )
    assertEquals(4, listeners.all.size)
    verify(exactly = 4) { listeners.registration.remove() }
  }

  @Test
  fun observeMessagesAndLatest_ofAChatThatIsNotTheStudentsOrWithoutAUser_readNothing() = runTest {
    val messagesUnexpected = listOf(MessagesResult.Loading, MessagesResult.UnexpectedError)
    val latestUnexpected = listOf(LatestMessageResult.Loading, LatestMessageResult.UnexpectedError)

    for (pairId in listOf("bob~carol", "bob~alice", "alice~bob~", "alice", "chats/alice~bob")) {
      assertEquals(pairId, messagesUnexpected, repository.observeMessages(pairId).toList())
      assertEquals(pairId, latestUnexpected, repository.observeLatestMessage(pairId).toList())
    }
    auth.user = null
    assertEquals(messagesUnexpected, repository.observeMessages(PAIR).toList())
    assertEquals(latestUnexpected, repository.observeLatestMessage(PAIR).toList())

    verify(exactly = 0) { db.collection(any()) }
  }

  private companion object {
    const val ALICE = "alice"
    const val BOB = "bob"
    const val PAIR = "alice~bob"
    const val DRAFT = "draft-1"
  }
}
