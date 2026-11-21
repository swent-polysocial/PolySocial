// Contributors: Claude (Opus 5.5, Anthropic) (Firestore mocks of the group chat repository's
// tests, #237); Claude (Fable 5.2, Anthropic) (the latest message's query, #363).
// Contributors: Claude (Opus 5.5, Anthropic) (moved them here, so the one-to-one chat
// repository's tests share them, #428).
package com.polysocial.model.chat

import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SnapshotMetadata
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** A Firestore failure with [code], as a failed task or listener reports it. */
fun firestoreFailure(code: FirebaseFirestoreException.Code) =
    FirebaseFirestoreException("failed", code)

/** A stored message's fields: [senderId], [text] and [createdAt] (null while it's pending). */
fun storedMessage(
    senderId: String,
    text: String,
    createdAt: Any? = Timestamp(1_900_000_000L, 0),
): Map<String, Any?> = mapOf("senderId" to senderId, "text" to text, "createdAt" to createdAt)

/**
 * A message document that answers only a read with [ServerTimestampBehavior.ESTIMATE], so a test
 * fails if the repository reads it any other way.
 */
fun messageDocument(id: String, data: Map<String, Any?>): DocumentSnapshot =
    mockk<DocumentSnapshot>().also {
      every { it.id } returns id
      every { it.getData(ServerTimestampBehavior.ESTIMATE) } returns data
    }

/** A query's snapshot of [documents], in that order, from the cache if [fromCache]. */
fun querySnapshot(vararg documents: DocumentSnapshot, fromCache: Boolean = false): QuerySnapshot =
    mockk<QuerySnapshot>().also {
      every { it.documents } returns documents.toList()
      every { it.metadata } returns
          mockk<SnapshotMetadata>().also { m -> every { m.isFromCache } returns fromCache }
    }

/**
 * Collects [flow] to its end, starting at once, so its listener is registered when this returns.
 */
fun <T> TestScope.collectAll(flow: Flow<T>): Deferred<List<T>> =
    async(UnconfinedTestDispatcher(testScheduler)) { flow.toList() }

/**
 * The snapshot listeners a chat repository registers on mocked queries: each stub accepts exactly
 * the query a test expects, so any other query fails it, and every listener returns the same
 * relaxed [registration].
 */
class MockedChatListeners {
  val registration = mockk<ListenerRegistration>(relaxed = true)

  /** Every listener registered, oldest first. */
  val all = mutableListOf<EventListener<QuerySnapshot>>()

  /** The listener registered last. */
  val last: EventListener<QuerySnapshot>
    get() = all.last()

  /** Records each listener added to [query], metadata changes included. */
  fun on(query: Query) {
    every { query.addSnapshotListener(MetadataChanges.INCLUDE, any()) } answers
        {
          all += secondArg<EventListener<QuerySnapshot>>()
          registration
        }
  }

  /** Expects the listener of a chat's messages on [messages], in the server's order. */
  fun onMessagesInServerOrder(messages: CollectionReference) {
    val ordered = mockk<Query>()
    every { messages.orderBy(MESSAGE_CREATED_AT) } returns ordered
    on(ordered)
  }

  /** Expects the listener of a chat's newest message only on [messages]. */
  fun onNewestMessage(messages: CollectionReference) {
    val newestFirst = mockk<Query>()
    val newestOnly = mockk<Query>()
    every { messages.orderBy(MESSAGE_CREATED_AT, Query.Direction.DESCENDING) } returns newestFirst
    every { newestFirst.limit(1) } returns newestOnly
    on(newestOnly)
  }
}
