// Contributors: Claude (wrote these tests, including the upcoming-events listener, #49).
package com.polysocial.model.event

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Transaction
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirestoreEventRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val collection = mockk<CollectionReference>()
  private val document = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>()
  private val written = slot<Any>()

  private val auth = FakeAuthRepository(AuthUser("creator", "creator@epfl.ch", true))
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private val repository =
      FirestoreEventRepository(db, auth, network, Clock.fixed(TEST_NOW, ZoneOffset.UTC))

  @Before
  fun stubFirestore() {
    every { db.collection(EVENTS_COLLECTION) } returns collection
    every { collection.document() } returns document
    every { document.id } returns "event-42"
    every { transaction.set(document, capture(written)) } returns transaction
    every { db.runTransaction(any<Transaction.Function<Transaction>>()) } answers
        {
          Tasks.forResult(firstArg<Transaction.Function<Transaction>>().apply(transaction))
        }
  }

  private fun failWith(code: FirebaseFirestoreException.Code) {
    every { db.runTransaction(any<Transaction.Function<Transaction>>()) } returns
        Tasks.forException(FirebaseFirestoreException("failed", code))
  }

  private fun writtenFields() = written.captured as Map<*, *>

  @Test
  fun aValidEvent_isWrittenAndGetsTheNewDocumentId() = runTest {
    assertEquals(CreateEventResult.Created("event-42"), repository.createEvent(validEvent()))

    assertEquals(validEvent().withCreator("creator", false).toFirestoreMap(), writtenFields())
  }

  @Test
  fun theCreator_isTheOnlyOrganizerAndReaderAndGetsNoBadge() = runTest {
    val claimed =
        validEvent()
            .copy(
                createdBy = "someone-else",
                organizerIds = listOf("someone-else"),
                allowedUids = listOf("intruder"),
                isAssociationEvent = true,
            )

    repository.createEvent(claimed)

    val fields = writtenFields()
    assertEquals("creator", fields["createdBy"])
    assertEquals(listOf("creator"), fields["organizerIds"])
    assertEquals(listOf("creator"), fields["allowedUids"])
    assertEquals(false, fields["isAssociationEvent"])
  }

  @Test
  fun anInvalidEvent_isRejectedBeforeFirestore() = runTest {
    val result =
        repository.createEvent(validEvent(title = " ", startTime = TEST_NOW.minusSeconds(3600)))

    assertEquals(
        CreateEventResult.Invalid(
            listOf(EventValidationError.BLANK_TITLE, EventValidationError.START_IN_PAST)
        ),
        result,
    )
    verify { db wasNot Called }
  }

  @Test
  fun nobodySignedIn_writesNothing() = runTest {
    auth.user = null

    assertEquals(CreateEventResult.NotSignedIn, repository.createEvent(validEvent()))
    verify { db wasNot Called }
  }

  @Test
  fun offline_returnsANetworkErrorWithoutStartingAWrite() = runTest {
    online = false

    assertEquals(CreateEventResult.NetworkError, repository.createEvent(validEvent()))
    verify { db wasNot Called }
  }

  @Test
  fun losingTheConnectionDuringTheWrite_isANetworkError() = runTest {
    failWith(FirebaseFirestoreException.Code.UNAVAILABLE)

    assertEquals(CreateEventResult.NetworkError, repository.createEvent(validEvent()))
  }

  @Test
  fun aWriteTheRulesDeny_isAnUnexpectedError() = runTest {
    failWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)

    assertEquals(CreateEventResult.UnexpectedError, repository.createEvent(validEvent()))
  }

  // Reading the public upcoming events (#49)

  private val publicQuery = mockk<Query>()
  private val fromNow = mockk<Query>()
  private val inWindow = mockk<Query>()
  private val sorted = mockk<Query>()
  private val registration = mockk<ListenerRegistration>(relaxed = true)
  private val listener = slot<EventListener<QuerySnapshot>>()

  private val windowStart = Timestamp(TEST_NOW.epochSecond, 0)
  private val windowEnd = Timestamp(TEST_NOW.plus(Duration.ofDays(14)).epochSecond, 0)

  /** Stubs exactly the query the repository must build, so any other query fails the test. */
  private fun stubPublicEventsQuery() {
    every { collection.whereEqualTo("isPrivate", false) } returns publicQuery
    every { publicQuery.whereGreaterThanOrEqualTo("startTime", windowStart) } returns fromNow
    every { fromNow.whereLessThan("startTime", windowEnd) } returns inWindow
    every { inWindow.orderBy("startTime") } returns sorted
    every { sorted.addSnapshotListener(MetadataChanges.INCLUDE, capture(listener)) } returns
        registration
  }

  private fun document(id: String, data: Map<String, Any?>) =
      mockk<DocumentSnapshot>().also {
        every { it.id } returns id
        every { it.data } returns buildMap { data.forEach { (k, v) -> if (v != null) put(k, v) } }
      }

  private fun snapshot(vararg documents: DocumentSnapshot, fromCache: Boolean = false) =
      mockk<QuerySnapshot>().also {
        every { it.documents } returns documents.toList()
        every { it.metadata } returns
            mockk<SnapshotMetadata>().also { m -> every { m.isFromCache } returns fromCache }
      }

  private val stored = validEvent().withCreator("creator", isVerifiedAssociation = false)

  @Test
  fun upcomingEvents_queriesPublicEventsInTheWindowSortedByStart() = runTest {
    stubPublicEventsQuery()

    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getUpcomingPublicEvents().collect {}
        }

    verify {
      collection.whereEqualTo("isPrivate", false)
      publicQuery.whereGreaterThanOrEqualTo("startTime", windowStart)
      fromNow.whereLessThan("startTime", windowEnd)
      inWindow.orderBy("startTime")
    }
    collecting.cancel()
  }

  @Test
  fun upcomingEvents_emitsTheEventsOnEveryChangeAndSaysWhenTheyAreCached() = runTest {
    stubPublicEventsQuery()
    val results = mutableListOf<PublicEventsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getUpcomingPublicEvents().toList(results)
        }

    listener.captured.onEvent(snapshot(fromCache = true), null)
    listener.captured.onEvent(snapshot(document("event-1", stored.toFirestoreMap())), null)
    collecting.cancel()

    assertEquals(
        listOf(
            PublicEventsResult.Events(emptyList(), fromCache = true),
            PublicEventsResult.Events(listOf(stored.copy(id = "event-1")), fromCache = false),
        ),
        results,
    )
  }

  @Test
  fun upcomingEvents_skipsAMalformedDocumentInsteadOfFailing() = runTest {
    stubPublicEventsQuery()
    val results = mutableListOf<PublicEventsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getUpcomingPublicEvents().toList(results)
        }

    listener.captured.onEvent(
        snapshot(
            document("broken", mapOf("title" to "No start time")),
            document("event-1", stored.toFirestoreMap()),
        ),
        null,
    )
    collecting.cancel()

    assertEquals(
        listOf(PublicEventsResult.Events(listOf(stored.copy(id = "event-1")), fromCache = false)),
        results,
    )
  }

  @Test
  fun upcomingEvents_emitsAnErrorAndEndsWhenTheQueryFails() = runTest {
    stubPublicEventsQuery()
    val results = mutableListOf<PublicEventsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getUpcomingPublicEvents().toList(results)
        }

    listener.captured.onEvent(
        null,
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED),
    )
    collecting.join()

    assertEquals(listOf(PublicEventsResult.Error), results)
    verify { registration.remove() }
  }

  /**
   * Collects the upcoming events with a collector that stays suspended on its first result until
   * [release] completes, like a slow screen. Returns everything it received.
   */
  private fun TestScope.collectSlowly(release: CompletableDeferred<Unit>) =
      mutableListOf<PublicEventsResult>().also { results ->
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getUpcomingPublicEvents().collect {
            results += it
            if (results.size == 1) release.await()
          }
        }
      }

  @Test
  fun upcomingEvents_aSlowCollectorStillGetsTheErrorAfterManySnapshots() = runTest {
    stubPublicEventsQuery()
    val release = CompletableDeferred<Unit>()
    val results = collectSlowly(release)

    listener.captured.onEvent(snapshot(), null)
    repeat(200) {
      listener.captured.onEvent(snapshot(document("event-1", stored.toFirestoreMap())), null)
    }
    listener.captured.onEvent(
        null,
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED),
    )
    release.complete(Unit)
    advanceUntilIdle()

    assertEquals(PublicEventsResult.Error, results.last())
    verify { registration.remove() }
  }

  @Test
  fun upcomingEvents_aSlowCollectorGetsOnlyTheLatestSnapshot() = runTest {
    stubPublicEventsQuery()
    val release = CompletableDeferred<Unit>()
    val results = collectSlowly(release)

    listener.captured.onEvent(snapshot(), null)
    listener.captured.onEvent(snapshot(fromCache = true), null)
    listener.captured.onEvent(snapshot(document("event-1", stored.toFirestoreMap())), null)
    release.complete(Unit)
    advanceUntilIdle()

    assertEquals(
        listOf(
            PublicEventsResult.Events(emptyList(), fromCache = false),
            PublicEventsResult.Events(listOf(stored.copy(id = "event-1")), fromCache = false),
        ),
        results,
    )
    coroutineContext.cancelChildren()
  }

  @Test
  fun upcomingEvents_stopsListeningWhenCollectionIsCancelled() = runTest {
    stubPublicEventsQuery()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getUpcomingPublicEvents().collect {}
        }

    collecting.cancel()
    collecting.join()

    verify { registration.remove() }
  }

  @Test
  fun upcomingEvents_rejectsAWindowThatIsNotPositive() {
    assertThrows(IllegalArgumentException::class.java) {
      repository.getUpcomingPublicEvents(windowDays = -1)
    }
  }
}
