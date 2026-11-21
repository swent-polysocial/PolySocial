// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #237).
// Contributors: OpenAI Codex (tested successful retry and conflicting message IDs, #238).
// Contributors: Claude (Fable 5.2, Anthropic) (tests of a group's latest message, #363, with one
// more from a test audit).
// Contributors: Claude (Opus 5.5, Anthropic) (its Firestore mocks moved to FirestoreChatMocks.kt,
// #428).
package com.polysocial.model.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.group.GROUPS_COLLECTION
import com.polysocial.model.network.NetworkMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreMessageRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val events = mockk<CollectionReference>()
  private val event = mockk<DocumentReference>()
  private val groups = mockk<CollectionReference>()
  private val group = mockk<DocumentReference>()
  private val messages = mockk<CollectionReference>()
  private val newMessage = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>()
  private val written = slot<Any>()
  private val options = slot<TransactionOptions>()

  private val auth = FakeAuthRepository(AuthUser(STUDENT, "student@epfl.ch", true))
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private val repository = FirestoreMessageRepository(db, auth, network)

  @Before
  fun stubFirestore() {
    every { db.collection(EVENTS_COLLECTION) } returns events
    every { events.document(EVENT) } returns event
    every { event.collection(GROUPS_COLLECTION) } returns groups
    every { groups.document(GROUP) } returns group
    every { group.collection(MESSAGES_COLLECTION) } returns messages
    every { messages.document() } returns newMessage
    every { transaction.set(newMessage, capture(written)) } returns transaction
    every {
      db.runTransaction(capture(options), any<Transaction.Function<SendMessageResult>>())
    } answers
        {
          Tasks.forResult(secondArg<Transaction.Function<SendMessageResult>>().apply(transaction))
        }
  }

  private fun failTheWriteWith(code: FirebaseFirestoreException.Code) {
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<SendMessageResult>>())
    } returns Tasks.forException(firestoreFailure(code))
  }

  private fun verifyNoWrite() {
    verify(exactly = 0) {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<SendMessageResult>>())
    }
    verify(exactly = 0) { transaction.set(any<DocumentReference>(), any()) }
  }

  // sendMessage

  @Test
  fun sendMessage_writesTheTrimmedTextAsTheStudentWithTheServerTime() = runTest {
    assertEquals(
        SendMessageResult.Sent,
        repository.sendMessage(EVENT, GROUP, "  See you at 6?\n"),
    )

    assertEquals(newMessageFirestoreMap(STUDENT, "See you at 6?"), written.captured)
  }

  @Test
  fun aNewStableIdCreatesAMessageAndAnInvalidIdIsRefused() = runTest {
    val missing = mockk<DocumentSnapshot>()
    every { messages.document("draft-id") } returns newMessage
    every { transaction.get(newMessage) } returns missing
    every { missing.exists() } returns false

    assertEquals(
        SendMessageResult.Sent,
        repository.sendMessage(EVENT, GROUP, "New draft", "draft-id"),
    )
    assertEquals(
        SendMessageResult.UnexpectedError,
        repository.sendMessage(EVENT, GROUP, "New draft", "not/an/id"),
    )
    verify(exactly = 1) {
      transaction.set(newMessage, match<Map<String, Any>> { it["text"] == "New draft" })
    }
  }

  @Test
  fun aStableIdReturnsSentForTheSameDraftWithoutOverwritingIt() = runTest {
    val existing = mockk<DocumentSnapshot>()
    every { messages.document("retry-id") } returns newMessage
    every { transaction.get(newMessage) } returns existing
    every { existing.exists() } returns true
    every { newMessage.id } returns "retry-id"
    every { existing.data } returns storedMessage(STUDENT, "See you at 6?", timestamp(1L))

    assertEquals(
        SendMessageResult.Sent,
        repository.sendMessage(EVENT, GROUP, " See you at 6? ", "retry-id"),
    )
    assertEquals(
        SendMessageResult.UnexpectedError,
        repository.sendMessage(EVENT, GROUP, "Different message", "retry-id"),
    )
    every { existing.data } returns storedMessage("another-member", "See you at 6?", timestamp(1L))
    assertEquals(
        SendMessageResult.UnexpectedError,
        repository.sendMessage(EVENT, GROUP, "See you at 6?", "retry-id"),
    )
    every { existing.data } returns mapOf("text" to "See you at 6?")
    assertEquals(
        SendMessageResult.UnexpectedError,
        repository.sendMessage(EVENT, GROUP, "See you at 6?", "retry-id"),
    )
    verify(exactly = 0) { transaction.set(newMessage, any<Map<String, Any>>()) }
  }

  @Test
  fun sendMessage_writesInOneTransactionTriedOnce() = runTest {
    repository.sendMessage(EVENT, GROUP, "Hi")

    assertEquals(1, options.captured.maxAttempts)
  }

  @Test
  fun sendMessage_aBlankOrTooLongText_isInvalidWithoutFirestore() = runTest {
    assertEquals(
        SendMessageResult.Invalid(MessageTextError.BLANK),
        repository.sendMessage(EVENT, GROUP, " \n "),
    )
    assertEquals(
        SendMessageResult.Invalid(MessageTextError.TOO_LONG),
        repository.sendMessage(EVENT, GROUP, "a".repeat(MAX_MESSAGE_LENGTH + 1)),
    )

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun sendMessage_aThousandCharactersOnceTrimmed_isSent() = runTest {
    val longest = "a".repeat(MAX_MESSAGE_LENGTH)

    assertEquals(SendMessageResult.Sent, repository.sendMessage(EVENT, GROUP, " $longest "))

    assertEquals(newMessageFirestoreMap(STUDENT, longest), written.captured)
  }

  @Test
  fun sendMessage_idsThatCannotBeDocumentIds_areUnexpectedErrorsWithoutFirestore() = runTest {
    assertEquals(
        SendMessageResult.UnexpectedError,
        repository.sendMessage("events/other", GROUP, "Hi"),
    )
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(EVENT, " ", "Hi"))

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun sendMessage_nobodySignedIn_isAnUnexpectedErrorWithoutAWrite() = runTest {
    auth.user = null

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(EVENT, GROUP, "Hi"))

    verifyNoWrite()
  }

  @Test
  fun sendMessage_offline_isANetworkErrorWithoutStartingAWrite() = runTest {
    online = false

    assertEquals(SendMessageResult.NetworkError, repository.sendMessage(EVENT, GROUP, "Hi"))

    verifyNoWrite()
  }

  @Test
  fun sendMessage_losingTheConnectionDuringTheWrite_isANetworkError() = runTest {
    for (code in
        listOf(
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        )) {
      failTheWriteWith(code)

      assertEquals(
          code.name,
          SendMessageResult.NetworkError,
          repository.sendMessage(EVENT, GROUP, "Hi"),
      )
    }
  }

  @Test
  fun sendMessage_aWriteTheRulesDeny_isAnUnexpectedError() = runTest {
    // For example a student who isn't (or is no longer) a member of the group.
    failTheWriteWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(EVENT, GROUP, "Hi"))
  }

  @Test
  fun sendMessage_anUnexpectedException_isAnUnexpectedError() = runTest {
    // For example a database blocked while the session's cache is cleared (#198).
    every { db.collection(EVENTS_COLLECTION) } throws IllegalStateException("terminated")

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(EVENT, GROUP, "Hi"))
  }

  @Test
  fun sendMessage_aTaskCancelledWhileTheCallerRuns_isAnUnexpectedError() = runTest {
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<SendMessageResult>>())
    } returns Tasks.forCanceled()

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(EVENT, GROUP, "Hi"))
  }

  @Test
  fun sendMessage_cancellingTheCaller_stillCancelsIt() = runTest {
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<SendMessageResult>>())
    } returns TaskCompletionSource<SendMessageResult>().task
    var result: SendMessageResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) {
          result = repository.sendMessage(EVENT, GROUP, "Hi")
        }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }

  // observeMessages

  private val listeners = MockedChatListeners()

  /** Stubs exactly the query observeMessages must build, so any other query fails the test. */
  private fun stubMessagesListener() = listeners.onMessagesInServerOrder(messages)

  private fun timestamp(seconds: Long) = Timestamp(seconds, 0)

  @Test
  fun observeMessages_emitsLoadingThenTheMessagesInTheQuerysServerTimeOrder() = runTest {
    stubMessagesListener()
    val results = mutableListOf<MessagesResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeMessages(EVENT, GROUP).collect { results += it }
        }

    // Firestore returns them ordered by createdAt, then by document ID for equal times.
    listeners.last.onEvent(
        querySnapshot(
            messageDocument("a", storedMessage("other", "Hi all", timestamp(1_900_000_000L))),
            messageDocument("b", storedMessage(STUDENT, "Hello", timestamp(1_900_000_000L))),
            messageDocument("broken", mapOf("text" to "No sender", "createdAt" to timestamp(1L))),
            messageDocument(
                "c",
                storedMessage("other", "See you at 6?", timestamp(1_900_000_100L)),
            ),
            fromCache = true,
        ),
        null,
    )
    listeners.last.onEvent(querySnapshot(), null)
    collecting.cancel()

    assertEquals(
        listOf(
            MessagesResult.Loading,
            MessagesResult.Success(
                listOf(
                    Message("a", "other", "Hi all", Instant.ofEpochSecond(1_900_000_000L)),
                    Message("b", STUDENT, "Hello", Instant.ofEpochSecond(1_900_000_000L)),
                    Message("c", "other", "See you at 6?", Instant.ofEpochSecond(1_900_000_100L)),
                ),
                fromCache = true,
            ),
            MessagesResult.Success(emptyList(), fromCache = false),
        ),
        results,
    )
    verify { listeners.registration.remove() }
  }

  @Test
  fun observeMessages_aServerTimeNotReceivedYet_isReadAsFirestoresEstimate() = runTest {
    stubMessagesListener()
    val pending =
        mockk<DocumentSnapshot>().also {
          every { it.id } returns "pending"
          // Without ESTIMATE, a server time that hasn't arrived yet reads as null.
          every { it.data } returns storedMessage(STUDENT, "On my way", null)
          every { it.getData(ServerTimestampBehavior.NONE) } returns
              storedMessage(STUDENT, "On my way", null)
          every { it.getData(ServerTimestampBehavior.ESTIMATE) } returns
              storedMessage(STUDENT, "On my way", timestamp(1_900_000_200L))
        }
    val results = collectAll(repository.observeMessages(EVENT, GROUP))

    listeners.last.onEvent(querySnapshot(pending), null)
    listeners.last.onEvent(null, firestoreFailure(FirebaseFirestoreException.Code.UNAVAILABLE))

    assertEquals(
        listOf(
            MessagesResult.Loading,
            MessagesResult.Success(
                listOf(
                    Message("pending", STUDENT, "On my way", Instant.ofEpochSecond(1_900_000_200L))
                ),
                fromCache = false,
            ),
            MessagesResult.NetworkError,
        ),
        results.await(),
    )
  }

  @Test
  fun observeMessages_anUnreachableServer_isANetworkErrorAndEndsTheFlow() = runTest {
    stubMessagesListener()
    val results = collectAll(repository.observeMessages(EVENT, GROUP))

    listeners.last.onEvent(null, firestoreFailure(FirebaseFirestoreException.Code.UNAVAILABLE))

    assertEquals(listOf(MessagesResult.Loading, MessagesResult.NetworkError), results.await())
    verify { listeners.registration.remove() }
  }

  @Test
  fun observeMessages_aDeniedListener_isAnUnexpectedErrorAndRemovesTheListener() = runTest {
    // For example a student who isn't a member of the group, or who left it and the chat changed.
    stubMessagesListener()
    val results = collectAll(repository.observeMessages(EVENT, GROUP))

    listeners.last.onEvent(
        null,
        firestoreFailure(FirebaseFirestoreException.Code.PERMISSION_DENIED),
    )

    assertEquals(listOf(MessagesResult.Loading, MessagesResult.UnexpectedError), results.await())
    verify { listeners.registration.remove() }
  }

  @Test
  fun observeMessages_aSlowCollectorStillGetsTheErrorAfterManySnapshots() = runTest {
    stubMessagesListener()
    val release = CompletableDeferred<Unit>()
    val results = mutableListOf<MessagesResult>()
    launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeMessages(EVENT, GROUP).collect {
        results += it
        // Busy with the first result while every snapshot and the error arrive.
        if (results.size == 1) release.await()
      }
    }
    val snapshot =
        querySnapshot(messageDocument("a", storedMessage("other", "Hi", timestamp(1_900_000_000L))))

    repeat(200) { listeners.last.onEvent(snapshot, null) }
    listeners.last.onEvent(
        null,
        firestoreFailure(FirebaseFirestoreException.Code.PERMISSION_DENIED),
    )
    release.complete(Unit)
    advanceUntilIdle()

    assertEquals(listOf(MessagesResult.Loading, MessagesResult.UnexpectedError), results)
    verify { listeners.registration.remove() }
  }

  @Test
  fun observeMessages_aNullSnapshotWithoutAnError_isAnUnexpectedError() = runTest {
    stubMessagesListener()
    val results = collectAll(repository.observeMessages(EVENT, GROUP))

    listeners.last.onEvent(null, null)

    assertEquals(listOf(MessagesResult.Loading, MessagesResult.UnexpectedError), results.await())
  }

  @Test
  fun observeMessages_aSnapshotThatCannotBeRead_isAnUnexpectedError() = runTest {
    stubMessagesListener()
    val unreadable =
        mockk<QuerySnapshot>().also {
          every { it.documents } throws IllegalStateException("terminated")
        }
    val results = collectAll(repository.observeMessages(EVENT, GROUP))

    listeners.last.onEvent(unreadable, null)

    assertEquals(listOf(MessagesResult.Loading, MessagesResult.UnexpectedError), results.await())
  }

  @Test
  fun observeMessages_anUnexpectedExceptionWhileStartingToListen_isAnUnexpectedError() = runTest {
    // For example a database blocked while the session's cache is cleared (#198).
    every { db.collection(EVENTS_COLLECTION) } throws IllegalStateException("terminated")

    assertEquals(
        listOf(MessagesResult.Loading, MessagesResult.UnexpectedError),
        repository.observeMessages(EVENT, GROUP).toList(),
    )
  }

  @Test
  fun observeMessages_nobodySignedInWhenCollectionStarts_isAnUnexpectedErrorWithoutAQuery() =
      runTest {
        val flow = repository.observeMessages(EVENT, GROUP)
        auth.user = null

        assertEquals(listOf(MessagesResult.Loading, MessagesResult.UnexpectedError), flow.toList())

        verify(exactly = 0) { db.collection(any()) }
      }

  @Test
  fun observeMessages_idsThatCannotBeDocumentIds_areUnexpectedErrorsWithoutFirestore() = runTest {
    assertEquals(
        listOf(MessagesResult.Loading, MessagesResult.UnexpectedError),
        repository.observeMessages(EVENT, "groups/other").toList(),
    )
    assertEquals(
        listOf(MessagesResult.Loading, MessagesResult.UnexpectedError),
        repository.observeMessages("", GROUP).toList(),
    )

    verify(exactly = 0) { db.collection(any()) }
  }

  // observeLatestMessage

  /** Stubs exactly the query observeLatestMessage must build, so any other query fails the test. */
  private fun stubLatestMessageListener() = listeners.onNewestMessage(messages)

  @Test
  fun observeLatestMessage_readsOnlyTheNewestMessage_orNoneYet() = runTest {
    stubLatestMessageListener()
    val results = mutableListOf<LatestMessageResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeLatestMessage(EVENT, GROUP).collect { results += it }
        }
    val newest =
        messageDocument("c", storedMessage("other", "See you at 6?", timestamp(1_900_000_100L)))

    listeners.last.onEvent(querySnapshot(fromCache = true), null)
    listeners.last.onEvent(querySnapshot(newest, fromCache = true), null)
    listeners.last.onEvent(querySnapshot(newest), null)
    collecting.cancel()

    val message = Message("c", "other", "See you at 6?", Instant.ofEpochSecond(1_900_000_100L))
    assertEquals(
        listOf(
            LatestMessageResult.Loading,
            LatestMessageResult.Success(null, fromCache = true),
            LatestMessageResult.Success(message, fromCache = true),
            LatestMessageResult.Success(message, fromCache = false),
        ),
        results,
    )
    verify { listeners.registration.remove() }
  }

  @Test
  fun observeLatestMessage_aNewestMessageThatCannotBeRead_isNoMessage() = runTest {
    stubLatestMessageListener()
    val results = collectAll(repository.observeLatestMessage(EVENT, GROUP))

    listeners.last.onEvent(
        querySnapshot(messageDocument("broken", mapOf("text" to "No sender or time"))),
        null,
    )
    listeners.last.onEvent(null, firestoreFailure(FirebaseFirestoreException.Code.UNAVAILABLE))

    assertEquals(
        listOf(
            LatestMessageResult.Loading,
            LatestMessageResult.Success(null, fromCache = false),
            LatestMessageResult.NetworkError,
        ),
        results.await(),
    )
  }

  @Test
  fun observeLatestMessage_aDeletedAccountsMessage_isStillTheLatest_withoutASender() = runTest {
    stubLatestMessageListener()
    val results = collectAll(repository.observeLatestMessage(EVENT, GROUP))

    // Account cleanup keeps a message's text and time and empties its senderId (#241).
    listeners.last.onEvent(
        querySnapshot(
            messageDocument(
                "kept",
                storedMessage(DELETED_MESSAGE_SENDER, "See you at 6?", timestamp(1_900_000_100L)),
            )
        ),
        null,
    )
    listeners.last.onEvent(null, firestoreFailure(FirebaseFirestoreException.Code.UNAVAILABLE))

    assertEquals(
        listOf(
            LatestMessageResult.Loading,
            LatestMessageResult.Success(
                Message(
                    "kept",
                    DELETED_MESSAGE_SENDER,
                    "See you at 6?",
                    Instant.ofEpochSecond(1_900_000_100L),
                ),
                fromCache = false,
            ),
            LatestMessageResult.NetworkError,
        ),
        results.await(),
    )
  }

  @Test
  fun observeLatestMessage_failures_endTheFlowWithTheirOwnErrorAndRemoveTheListener() = runTest {
    stubLatestMessageListener()
    val offline = collectAll(repository.observeLatestMessage(EVENT, GROUP))
    listeners.last.onEvent(
        null,
        firestoreFailure(FirebaseFirestoreException.Code.DEADLINE_EXCEEDED),
    )
    // For example a student who isn't in the group, or a deleted account still listed in it.
    val denied = collectAll(repository.observeLatestMessage(EVENT, GROUP))
    listeners.last.onEvent(
        null,
        firestoreFailure(FirebaseFirestoreException.Code.PERMISSION_DENIED),
    )
    val unreadable = collectAll(repository.observeLatestMessage(EVENT, GROUP))
    listeners.last.onEvent(
        mockk<QuerySnapshot>().also {
          every { it.documents } throws IllegalStateException("terminated")
        },
        null,
    )

    assertEquals(
        listOf(LatestMessageResult.Loading, LatestMessageResult.NetworkError),
        offline.await(),
    )
    val unexpected = listOf(LatestMessageResult.Loading, LatestMessageResult.UnexpectedError)
    assertEquals(unexpected, denied.await())
    assertEquals(unexpected, unreadable.await())
    verify(exactly = 3) { listeners.registration.remove() }
  }

  @Test
  fun observeLatestMessage_withoutAUserOrWithIdsThatCannotBeDocumentIds_readsNothing() = runTest {
    val unexpected = listOf(LatestMessageResult.Loading, LatestMessageResult.UnexpectedError)

    assertEquals(unexpected, repository.observeLatestMessage(EVENT, "groups/other").toList())
    assertEquals(unexpected, repository.observeLatestMessage(" ", GROUP).toList())
    auth.user = null
    assertEquals(unexpected, repository.observeLatestMessage(EVENT, GROUP).toList())

    verify(exactly = 0) { db.collection(any()) }
  }

  private companion object {
    const val STUDENT = "student"
    const val EVENT = "event-1"
    const val GROUP = "group-1"
  }
}
