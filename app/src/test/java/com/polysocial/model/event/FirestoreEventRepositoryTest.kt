// Contributors: Claude (wrote these tests; verified association badge, #45; the upcoming-events
// listener, #49; unexpected failures and cancellation, #182; the single-event listener and its
// failures, #112; updating and deleting as an organizer, #130).
// Contributors: OpenAI Codex (listener disposal failure and cancellation tests, #156).
package com.polysocial.model.event

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.TaskCompletionSource
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
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreEventRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val collection = mockk<CollectionReference>()
  private val document = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>()
  private val written = slot<Any>()
  private val options = slot<TransactionOptions>()

  private val auth = FakeAuthRepository(AuthUser("creator", "creator@epfl.ch", true))
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private val creatorProfile =
      UserProfile(
          uid = "creator",
          email = "creator@epfl.ch",
          displayName = "Test Creator",
          section = "IN",
          year = "BA3",
      )
  private val profiles = FakeUserProfileRepository(ProfileResult.Found(creatorProfile))

  private val repository =
      FirestoreEventRepository(db, auth, profiles, network, Clock.fixed(TEST_NOW, ZoneOffset.UTC))

  @Before
  fun stubFirestore() {
    every { db.collection(EVENTS_COLLECTION) } returns collection
    every { collection.document() } returns document
    every { document.id } returns "event-42"
    every { transaction.set(document, capture(written)) } returns transaction
    every { db.runTransaction(capture(options), any<Transaction.Function<Transaction>>()) } answers
        {
          Tasks.forResult(secondArg<Transaction.Function<Transaction>>().apply(transaction))
        }
  }

  private fun failWith(code: FirebaseFirestoreException.Code) {
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Transaction>>())
    } returns Tasks.forException(FirebaseFirestoreException("failed", code))
  }

  private fun writtenFields() = written.captured as Map<*, *>

  @Test
  fun aValidEvent_isWrittenAndGetsTheNewDocumentId() = runTest {
    assertEquals(CreateEventResult.Created("event-42"), repository.createEvent(validEvent()))

    assertEquals(validEvent().withCreator("creator", false).toFirestoreMap(), writtenFields())
  }

  @Test
  fun theWrite_isTriedOnlyOnce() = runTest {
    repository.createEvent(validEvent())

    assertEquals(1, options.captured.maxAttempts)
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

  @Test
  fun aVerifiedAssociation_getsTheBadge() = runTest {
    profiles.getProfileResult =
        ProfileResult.Found(
            creatorProfile.copy(accountType = AccountType.ASSOCIATION, isAssociationVerified = true)
        )

    assertEquals(CreateEventResult.Created("event-42"), repository.createEvent(validEvent()))

    assertEquals(true, writtenFields()["isAssociationEvent"])
    assertEquals("creator", profiles.lastRequestedUid)
  }

  @Test
  fun anUnverifiedAssociation_getsNoBadge() = runTest {
    profiles.getProfileResult =
        ProfileResult.Found(creatorProfile.copy(accountType = AccountType.ASSOCIATION))

    repository.createEvent(validEvent())

    assertEquals(false, writtenFields()["isAssociationEvent"])
  }

  @Test
  fun aCreatorWithoutAProfile_getsNoBadge() = runTest {
    profiles.getProfileResult = ProfileResult.NotFound

    assertEquals(CreateEventResult.Created("event-42"), repository.createEvent(validEvent()))

    assertEquals(false, writtenFields()["isAssociationEvent"])
  }

  @Test
  fun aProfileReadOffline_isANetworkErrorAndWritesNothing() = runTest {
    profiles.getProfileResult = ProfileResult.NetworkError

    assertEquals(CreateEventResult.NetworkError, repository.createEvent(validEvent()))
    verify { db wasNot Called }
  }

  @Test
  fun aFailedProfileRead_isAnUnexpectedErrorAndWritesNothing() = runTest {
    profiles.getProfileResult = ProfileResult.UnexpectedError

    assertEquals(CreateEventResult.UnexpectedError, repository.createEvent(validEvent()))
    verify { db wasNot Called }
  }

  @Test
  fun offline_doesNotReadTheProfile() = runTest {
    online = false

    repository.createEvent(validEvent())

    assertEquals(0, profiles.getProfileCalls)
  }

  /** What Firestore throws when it is used after being shut down: not a FirebaseException. */
  private fun terminated() = IllegalStateException("The client has already been terminated")

  @Test
  fun anUnexpectedExceptionBeforeTheWrite_isAnUnexpectedError() = runTest {
    every { collection.document() } throws terminated()

    assertEquals(CreateEventResult.UnexpectedError, repository.createEvent(validEvent()))
  }

  @Test
  fun anUnexpectedFailureOfTheWrite_isAnUnexpectedError() = runTest {
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Transaction>>())
    } returns Tasks.forException(terminated())

    assertEquals(CreateEventResult.UnexpectedError, repository.createEvent(validEvent()))
  }

  @Test
  fun aWriteTaskCancelledWhileTheCallerRuns_isAnUnexpectedError() = runTest {
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Transaction>>())
    } returns Tasks.forCanceled()

    assertEquals(CreateEventResult.UnexpectedError, repository.createEvent(validEvent()))
  }

  @Test
  fun cancellingTheCaller_stillCancelsTheWrite() = runTest {
    every {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Transaction>>())
    } returns TaskCompletionSource<Transaction>().task
    var result: CreateEventResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) {
          result = repository.createEvent(validEvent())
        }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
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
  fun upcomingEvents_anUnexpectedExceptionWhileStartingToListen_emitsAnErrorAndEnds() = runTest {
    stubPublicEventsQuery()
    every { sorted.addSnapshotListener(MetadataChanges.INCLUDE, any()) } throws terminated()

    assertEquals(listOf(PublicEventsResult.Error), repository.getUpcomingPublicEvents().toList())
  }

  @Test
  fun upcomingEvents_aSnapshotThatCannotBeRead_emitsAnErrorAndEnds() = runTest {
    stubPublicEventsQuery()
    val results = mutableListOf<PublicEventsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getUpcomingPublicEvents().toList(results)
        }
    val unreadable = mockk<QuerySnapshot>()
    every { unreadable.documents } throws terminated()

    listener.captured.onEvent(unreadable, null)
    collecting.join()

    assertEquals(listOf(PublicEventsResult.Error), results)
    verify { registration.remove() }
  }

  @Test
  fun upcomingEvents_cancellingTheCollector_stillCancelsItWithoutAnError() = runTest {
    stubPublicEventsQuery()
    val results = mutableListOf<PublicEventsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getUpcomingPublicEvents().toList(results)
        }

    collecting.cancel()
    collecting.join()

    assertTrue(collecting.isCancelled)
    assertEquals(emptyList<PublicEventsResult>(), results)
  }

  @Test
  fun upcomingEvents_listenerDisposalFailure_reportsAnErrorAndCompletes() = runTest {
    stubPublicEventsQuery()
    every { registration.remove() } throws terminated()
    val results = mutableListOf<PublicEventsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getUpcomingPublicEvents().toList(results)
        }

    listener.captured.onEvent(snapshot(document("event-1", stored.toFirestoreMap())), null)
    listener.captured.onEvent(
        null,
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED),
    )
    collecting.join()

    assertTrue(collecting.isCompleted)
    assertFalse(collecting.isCancelled)
    assertEquals(
        PublicEventsResult.Events(listOf(stored.copy(id = "event-1")), fromCache = false),
        results.first(),
    )
    // Both the query and disposal fail. Screens must still receive an error and finish collecting;
    // they do not depend on how many times the same terminal error is reported.
    assertEquals(setOf(PublicEventsResult.Error), results.drop(1).toSet())
    verify(exactly = 1) { registration.remove() }
  }

  @Test
  fun upcomingEvents_cancellingAfterASnapshot_removesTheListenerWithoutAnError() = runTest {
    stubPublicEventsQuery()
    val results = mutableListOf<PublicEventsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getUpcomingPublicEvents().toList(results)
        }
    val expected = PublicEventsResult.Events(listOf(stored.copy(id = "event-1")), fromCache = true)
    listener.captured.onEvent(
        snapshot(document("event-1", stored.toFirestoreMap()), fromCache = true),
        null,
    )

    collecting.cancel()
    collecting.join()

    assertTrue(collecting.isCancelled)
    assertEquals(listOf(expected), results)
    verify(exactly = 1) { registration.remove() }
  }

  @Test
  fun upcomingEvents_rejectsAWindowThatIsNotPositive() {
    assertThrows(IllegalArgumentException::class.java) {
      repository.getUpcomingPublicEvents(windowDays = -1)
    }
  }

  // getEvent (#112)

  private val eventDocument = mockk<DocumentReference>()
  private val eventListener = slot<EventListener<DocumentSnapshot>>()

  /** Stubs exactly the listener getEvent must register, so any other listener fails the test. */
  private fun stubEventDocument(id: String = "event-1") {
    every { collection.document(id) } returns eventDocument
    every {
      eventDocument.addSnapshotListener(MetadataChanges.INCLUDE, capture(eventListener))
    } returns registration
  }

  /** A snapshot of `events/{id}`: [data] is null when the document doesn't exist. */
  private fun eventSnapshot(data: Map<String, Any?>?, fromCache: Boolean = false) =
      mockk<DocumentSnapshot>().also {
        every { it.exists() } returns (data != null)
        every { it.data } returns
            data?.let { d -> buildMap { d.forEach { (k, v) -> if (v != null) put(k, v) } } }
        every { it.metadata } returns
            mockk<SnapshotMetadata>().also { m -> every { m.isFromCache } returns fromCache }
      }

  private fun send(data: Map<String, Any?>?, fromCache: Boolean = false) =
      eventListener.captured.onEvent(eventSnapshot(data, fromCache), null)

  private fun deny() =
      eventListener.captured.onEvent(
          null,
          FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED),
      )

  private fun TestScope.collectEvent(results: MutableList<EventResult>, id: String = "event-1") =
      launch(UnconfinedTestDispatcher(testScheduler)) { repository.getEvent(id).toList(results) }

  @Test
  fun getEvent_listensToItsDocumentIncludingMetadataChanges() = runTest {
    stubEventDocument()
    val collecting = collectEvent(mutableListOf())

    verify {
      db.collection(EVENTS_COLLECTION)
      collection.document("event-1")
      eventDocument.addSnapshotListener(MetadataChanges.INCLUDE, any())
    }
    collecting.cancel()
  }

  @Test
  fun getEvent_emitsTheEventAndSaysWhetherItComesFromTheCache() = runTest {
    stubEventDocument()
    val results = mutableListOf<EventResult>()
    val collecting = collectEvent(results)

    send(stored.toFirestoreMap(), fromCache = true)
    send(stored.toFirestoreMap(), fromCache = false)
    collecting.cancel()

    val event = stored.copy(id = "event-1")
    assertEquals(
        listOf(
            EventResult.Found(event, fromCache = true),
            EventResult.Found(event, fromCache = false),
        ),
        results,
    )
  }

  @Test
  fun getEvent_emitsAgainWithTheNewDataWhenTheEventChanges() = runTest {
    stubEventDocument()
    val results = mutableListOf<EventResult>()
    val collecting = collectEvent(results)

    send(stored.toFirestoreMap())
    send(stored.copy(title = "Moved to Satellite").toFirestoreMap())
    collecting.cancel()

    assertEquals(
        listOf(
            EventResult.Found(stored.copy(id = "event-1"), fromCache = false),
            EventResult.Found(
                stored.copy(id = "event-1", title = "Moved to Satellite"),
                fromCache = false,
            ),
        ),
        results,
    )
  }

  @Test
  fun getEvent_emitsTheEventThenNotFoundOnceItIsDeletedAndKeepsListening() = runTest {
    stubEventDocument()
    val results = mutableListOf<EventResult>()
    val collecting = collectEvent(results)

    send(stored.toFirestoreMap())
    send(null)

    assertEquals(
        listOf(EventResult.Found(stored.copy(id = "event-1"), false), EventResult.NotFound),
        results,
    )
    assertTrue(collecting.isActive)
    verify(exactly = 0) { registration.remove() }

    send(stored.toFirestoreMap())
    assertEquals(EventResult.Found(stored.copy(id = "event-1"), false), results.last())
    collecting.cancel()
  }

  @Test
  fun getEvent_offlineAndNeverRead_isNotCachedUntilTheServerAnswers() = runTest {
    stubEventDocument()
    val results = mutableListOf<EventResult>()
    val collecting = collectEvent(results)

    send(null, fromCache = true)
    assertTrue(collecting.isActive)
    send(stored.toFirestoreMap(), fromCache = false)
    collecting.cancel()

    assertEquals(
        listOf(EventResult.NotCached, EventResult.Found(stored.copy(id = "event-1"), false)),
        results,
    )
  }

  @Test
  fun getEvent_aMalformedDocumentIsNotFoundFromTheServerOrTheCache() = runTest {
    stubEventDocument()
    val results = mutableListOf<EventResult>()
    val collecting = collectEvent(results)

    send(stored.toFirestoreMap() - "title", fromCache = false)
    send(stored.toFirestoreMap() + ("startTime" to "tomorrow"), fromCache = true)
    collecting.cancel()

    assertEquals(listOf(EventResult.NotFound, EventResult.NotFound), results)
  }

  @Test
  fun getEvent_aDeniedReadIsNotFoundAndEndsTheFlow() = runTest {
    stubEventDocument()
    val results = mutableListOf<EventResult>()
    val collecting = collectEvent(results)

    deny()
    collecting.join()

    assertEquals(listOf(EventResult.NotFound), results)
    verify { registration.remove() }
  }

  @Test
  fun getEvent_anUnexpectedExceptionWhileStartingToListen_isNotFoundAndEnds() = runTest {
    // For example a Firestore client terminated at logout (#185) while a detail screen is alive.
    every { collection.document("event-1") } returns eventDocument
    every { eventDocument.addSnapshotListener(MetadataChanges.INCLUDE, any()) } throws terminated()

    assertEquals(listOf(EventResult.NotFound), repository.getEvent("event-1").toList())
  }

  @Test
  fun getEvent_aSnapshotThatCannotBeRead_isNotFoundAndEnds() = runTest {
    stubEventDocument()
    val results = mutableListOf<EventResult>()
    val collecting = collectEvent(results)
    val unreadable = mockk<DocumentSnapshot>()
    every { unreadable.metadata } throws terminated()

    // Thrown on Firestore's callback thread, this would crash the app.
    eventListener.captured.onEvent(unreadable, null)
    collecting.join()

    assertEquals(listOf(EventResult.NotFound), results)
    verify { registration.remove() }
  }

  @Test
  fun getEvent_aDeniedReadAfterTheEventWasShownIsNotFoundAndEndsTheFlow() = runTest {
    stubEventDocument()
    val results = mutableListOf<EventResult>()
    val collecting = collectEvent(results)

    send(stored.toFirestoreMap())
    deny()
    collecting.join()

    assertEquals(
        listOf(EventResult.Found(stored.copy(id = "event-1"), false), EventResult.NotFound),
        results,
    )
  }

  @Test
  fun getEvent_aNullSnapshotWithoutAnErrorIsNotFoundAndEndsTheFlow() = runTest {
    stubEventDocument()
    val results = mutableListOf<EventResult>()
    val collecting = collectEvent(results)

    eventListener.captured.onEvent(null, null)
    collecting.join()

    assertEquals(listOf(EventResult.NotFound), results)
    verify { registration.remove() }
  }

  @Test
  fun getEvent_anIdThatCannotBeADocumentIdIsNotFoundWithoutReading() = runTest {
    for (id in listOf("", "   ", "a/b", "events/event-1", "/")) {
      assertEquals(id, listOf(EventResult.NotFound), repository.getEvent(id).toList())
    }
    verify { db wasNot Called }
  }

  @Test
  fun getEvent_stopsListeningWhenCollectionIsCancelled() = runTest {
    stubEventDocument()
    val collecting = collectEvent(mutableListOf())

    collecting.cancel()
    collecting.join()

    verify { registration.remove() }
  }

  @Test
  fun getEvent_aSlowCollectorStillGetsNotFoundAfterManySnapshotsAndADeniedRead() = runTest {
    stubEventDocument()
    val release = CompletableDeferred<Unit>()
    val results = mutableListOf<EventResult>()
    launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.getEvent("event-1").collect {
        results += it
        if (results.size == 1) release.await()
      }
    }

    send(stored.toFirestoreMap())
    repeat(200) { send(stored.copy(title = "Update $it").toFirestoreMap()) }
    deny()
    release.complete(Unit)
    advanceUntilIdle()

    assertEquals(
        listOf(EventResult.Found(stored.copy(id = "event-1"), false), EventResult.NotFound),
        results,
    )
    verify { registration.remove() }
  }

  // Updating and deleting as an organizer (#130)

  private val storedDocument = mockk<DocumentReference>()
  private val updated = slot<Any>()

  /** An event "creator" made, now managed with "co-organizer" and readable by a member too. */
  private val managed =
      stored.copy(
          id = "event-1",
          organizerIds = listOf("creator", "co-organizer"),
          allowedUids = listOf("creator", "co-organizer", "member"),
          isAssociationEvent = true,
      )

  /** New values from the form, which also tries to take over the event. */
  private val edit =
      validEvent(title = "Exam review")
          .copy(
              id = "event-1",
              createdBy = "intruder",
              organizerIds = listOf("intruder"),
              allowedUids = emptyList(),
              isAssociationEvent = false,
          )

  /**
   * Stubs `events/event-1` as stored with [data] (missing when null), read and written through the
   * transaction the repository runs.
   */
  private fun stubStoredEvent(data: Map<String, Any?>? = managed.toFirestoreMap()) {
    every { collection.document("event-1") } returns storedDocument
    every { transaction.get(storedDocument) } returns eventSnapshot(data)
    every { transaction.set(storedDocument, capture(updated)) } returns transaction
    every { transaction.delete(storedDocument) } returns transaction
    every { db.runTransaction(capture(options), any<Transaction.Function<Any>>()) } answers
        {
          Tasks.forResult(secondArg<Transaction.Function<Any>>().apply(transaction))
        }
  }

  private fun denied() =
      FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)

  /**
   * Runs the transaction's reads, then fails its commit with [code], like a write the server
   * refuses.
   */
  private fun failTheCommitWith(code: FirebaseFirestoreException.Code) {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } answers
        {
          secondArg<Transaction.Function<Any>>().apply(transaction)
          Tasks.forException(FirebaseFirestoreException("failed", code))
        }
  }

  private fun updatedFields() = updated.captured as Map<*, *>

  @Test
  fun update_byAnOrganizer_writesTheNewValuesAndKeepsWhoManagesAndReadsTheEvent() = runTest {
    stubStoredEvent()

    assertEquals(UpdateEventResult.Updated, repository.updateEvent(edit))

    assertEquals(edit.withOwnershipOf(managed).toFirestoreMap(), updatedFields())
    assertEquals("Exam review", updatedFields()["title"])
    assertEquals("creator", updatedFields()["createdBy"])
    assertEquals(listOf("creator", "co-organizer"), updatedFields()["organizerIds"])
    assertEquals(listOf("creator", "co-organizer", "member"), updatedFields()["allowedUids"])
    assertEquals(true, updatedFields()["isAssociationEvent"])
    assertEquals(1, options.captured.maxAttempts)
  }

  @Test
  fun update_byAnOrganizerWhoIsNotTheCreator_isSaved() = runTest {
    stubStoredEvent()
    auth.user = AuthUser("co-organizer", "co@epfl.ch", true)

    assertEquals(UpdateEventResult.Updated, repository.updateEvent(edit))
  }

  @Test
  fun update_bySomeoneWhoIsNotAnOrganizer_writesNothing() = runTest {
    stubStoredEvent()
    auth.user = AuthUser("member", "member@epfl.ch", true)

    assertEquals(UpdateEventResult.NotOrganizer, repository.updateEvent(edit))
    verify(exactly = 0) { transaction.set(any<DocumentReference>(), any()) }
  }

  @Test
  fun update_withInvalidValues_isRejectedBeforeFirestore() = runTest {
    val result =
        repository.updateEvent(edit.copy(title = " ", startTime = TEST_NOW.minusSeconds(60)))

    assertEquals(
        UpdateEventResult.Invalid(
            listOf(EventValidationError.BLANK_TITLE, EventValidationError.START_IN_PAST)
        ),
        result,
    )
    verify { db wasNot Called }
  }

  @Test
  fun update_offline_returnsANetworkErrorWithoutStartingAWrite() = runTest {
    online = false

    assertEquals(UpdateEventResult.NetworkError, repository.updateEvent(edit))
    verify { db wasNot Called }
  }

  @Test
  fun update_withNobodySignedIn_isNotAnOrganizerAndReadsNothing() = runTest {
    auth.user = null

    assertEquals(UpdateEventResult.NotOrganizer, repository.updateEvent(edit))
    verify { db wasNot Called }
  }

  @Test
  fun update_anIdThatCannotBeADocumentId_isNotFoundWithoutReading() = runTest {
    assertEquals(UpdateEventResult.NotFound, repository.updateEvent(edit.copy(id = "a/b")))
    verify { db wasNot Called }
  }

  @Test
  fun update_aMissingOrMalformedEvent_isNotFoundAndWritesNothing() = runTest {
    stubStoredEvent(data = null)
    assertEquals(UpdateEventResult.NotFound, repository.updateEvent(edit))

    stubStoredEvent(data = managed.toFirestoreMap() - "title")
    assertEquals(UpdateEventResult.NotFound, repository.updateEvent(edit))

    verify(exactly = 0) { transaction.set(any<DocumentReference>(), any()) }
  }

  @Test
  fun update_aReadTheRulesDeny_isNotFound() = runTest {
    stubStoredEvent()
    every { transaction.get(storedDocument) } throws denied()

    assertEquals(UpdateEventResult.NotFound, repository.updateEvent(edit))
  }

  @Test
  fun update_aWriteTheRulesDeny_isNotAnOrganizer() = runTest {
    stubStoredEvent()
    failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)

    assertEquals(UpdateEventResult.NotOrganizer, repository.updateEvent(edit))
  }

  @Test
  fun update_losingTheConnectionDuringTheWrite_isANetworkError() = runTest {
    stubStoredEvent()
    failTheCommitWith(FirebaseFirestoreException.Code.UNAVAILABLE)

    assertEquals(UpdateEventResult.NetworkError, repository.updateEvent(edit))
  }

  @Test
  fun update_anyOtherFailure_isAnUnexpectedError() = runTest {
    stubStoredEvent()
    failTheCommitWith(FirebaseFirestoreException.Code.ABORTED)
    assertEquals(UpdateEventResult.UnexpectedError, repository.updateEvent(edit))

    every { collection.document("event-1") } throws terminated()
    assertEquals(UpdateEventResult.UnexpectedError, repository.updateEvent(edit))
  }

  @Test
  fun update_aWriteTaskCancelledWhileTheCallerRuns_isAnUnexpectedError() = runTest {
    stubStoredEvent()
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } returns
        Tasks.forCanceled()

    assertEquals(UpdateEventResult.UnexpectedError, repository.updateEvent(edit))
  }

  @Test
  fun delete_byAnOrganizer_deletesTheEventInOneAttempt() = runTest {
    stubStoredEvent()
    auth.user = AuthUser("co-organizer", "co@epfl.ch", true)

    assertEquals(DeleteEventResult.Deleted, repository.deleteEvent("event-1"))

    verify { transaction.delete(storedDocument) }
    assertEquals(1, options.captured.maxAttempts)
  }

  @Test
  fun delete_bySomeoneWhoIsNotAnOrganizer_deletesNothing() = runTest {
    stubStoredEvent()
    auth.user = AuthUser("member", "member@epfl.ch", true)

    assertEquals(DeleteEventResult.NotOrganizer, repository.deleteEvent("event-1"))
    verify(exactly = 0) { transaction.delete(any<DocumentReference>()) }
  }

  @Test
  fun delete_anEventThatNoLongerExists_isNotFound() = runTest {
    stubStoredEvent(data = null)

    assertEquals(DeleteEventResult.NotFound, repository.deleteEvent("event-1"))
    verify(exactly = 0) { transaction.delete(any<DocumentReference>()) }
  }

  @Test
  fun delete_offlineOrSignedOutOrWithABadId_readsNothing() = runTest {
    assertEquals(DeleteEventResult.NotFound, repository.deleteEvent(" "))

    auth.user = null
    assertEquals(DeleteEventResult.NotOrganizer, repository.deleteEvent("event-1"))

    auth.user = AuthUser("creator", "creator@epfl.ch", true)
    online = false
    assertEquals(DeleteEventResult.NetworkError, repository.deleteEvent("event-1"))

    verify { db wasNot Called }
  }

  @Test
  fun delete_aWriteTheRulesDeny_isNotAnOrganizer() = runTest {
    stubStoredEvent()
    failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)

    assertEquals(DeleteEventResult.NotOrganizer, repository.deleteEvent("event-1"))
  }

  @Test
  fun delete_losingTheConnectionOrAnyOtherFailure_isReported() = runTest {
    stubStoredEvent()
    failTheCommitWith(FirebaseFirestoreException.Code.UNAVAILABLE)
    assertEquals(DeleteEventResult.NetworkError, repository.deleteEvent("event-1"))

    failTheCommitWith(FirebaseFirestoreException.Code.ABORTED)
    assertEquals(DeleteEventResult.UnexpectedError, repository.deleteEvent("event-1"))
  }
}
