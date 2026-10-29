// Contributors: Claude Opus 5.5 (wrote these tests, #121).
package com.polysocial.model.registration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FieldValue
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
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.network.NetworkMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreRegistrationRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val events = mockk<CollectionReference>()
  private val event = mockk<DocumentReference>()
  private val attendees = mockk<CollectionReference>()
  private val attendee = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>()
  private val written = slot<Map<String, Any>>()
  private val options = slot<TransactionOptions>()

  private val auth = FakeAuthRepository(AuthUser("student", "student@epfl.ch", true))
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private val repository = FirestoreRegistrationRepository(db, auth, network)

  private fun snapshot(exists: Boolean) =
      mockk<DocumentSnapshot>().also { every { it.exists() } returns exists }

  @Before
  fun stubFirestore() {
    every { db.collection(EVENTS_COLLECTION) } returns events
    every { events.document("event-1") } returns event
    every { event.collection(ATTENDEES_COLLECTION) } returns attendees
    every { attendees.document("student") } returns attendee
    stubStored(eventExists = true, registered = false)
    every { transaction.set(attendee, capture(written)) } returns transaction
    every { transaction.delete(attendee) } returns transaction
    every { db.runTransaction(capture(options), any<Transaction.Function<Any>>()) } answers
        {
          Tasks.forResult(secondArg<Transaction.Function<Any>>().apply(transaction))
        }
  }

  private fun stubStored(eventExists: Boolean, registered: Boolean) {
    every { transaction.get(event) } returns snapshot(eventExists)
    every { transaction.get(attendee) } returns snapshot(registered)
  }

  private fun failed(code: FirebaseFirestoreException.Code) =
      FirebaseFirestoreException("failed", code)

  /** Fails the transaction with [failure] before it reads anything, like a denied first read. */
  private fun failBeforeReading(failure: Exception) {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } returns
        Tasks.forException(failure)
  }

  /** Runs the transaction's reads and writes, then fails its commit with [code]. */
  private fun failTheCommitWith(code: FirebaseFirestoreException.Code) {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } answers
        {
          secondArg<Transaction.Function<Any>>().apply(transaction)
          Tasks.forException(failed(code))
        }
  }

  // register

  @Test
  fun register_writesTheStudentsUidAndTheServerTime() = runTest {
    assertEquals(RegisterResult.Registered, repository.register("event-1"))

    assertEquals(setOf("uid", "registeredAt"), written.captured.keys)
    assertEquals("student", written.captured["uid"])
    assertSame(FieldValue.serverTimestamp(), written.captured["registeredAt"])
  }

  @Test
  fun register_readsTheEventAndTheRegistrationInOneTransactionTriedOnce() = runTest {
    repository.register("event-1")

    assertEquals(1, options.captured.maxAttempts)
    verify { transaction.get(event) }
    verify { transaction.get(attendee) }
  }

  @Test
  fun register_twice_writesNothingAndIsStillRegistered() = runTest {
    stubStored(eventExists = true, registered = true)

    assertEquals(RegisterResult.Registered, repository.register("event-1"))

    verify(exactly = 0) { transaction.set(any(), any()) }
  }

  @Test
  fun register_aDeletedEvent_isNotFoundAndWritesNothing() = runTest {
    stubStored(eventExists = false, registered = false)

    assertEquals(RegisterResult.NotFound, repository.register("event-1"))

    verify(exactly = 0) { transaction.set(any(), any()) }
  }

  @Test
  fun register_anEventTheRulesDontLetTheStudentRead_isNotFound() = runTest {
    failBeforeReading(failed(FirebaseFirestoreException.Code.PERMISSION_DENIED))

    assertEquals(RegisterResult.NotFound, repository.register("event-1"))
  }

  @Test
  fun register_aWriteTheRulesDenyAfterTheRead_isAnUnexpectedError() = runTest {
    failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)

    assertEquals(RegisterResult.UnexpectedError, repository.register("event-1"))
  }

  @Test
  fun register_anIdThatCannotBeADocumentId_isNotFoundWithoutFirestore() = runTest {
    assertEquals(RegisterResult.NotFound, repository.register("events/other"))
    assertEquals(RegisterResult.NotFound, repository.register(" "))

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun register_nobodySignedIn_writesNothing() = runTest {
    auth.user = null

    assertEquals(RegisterResult.NotSignedIn, repository.register("event-1"))

    verify(exactly = 0) {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>())
    }
  }

  @Test
  fun register_offline_isANetworkErrorWithoutStartingAWrite() = runTest {
    online = false

    assertEquals(RegisterResult.NetworkError, repository.register("event-1"))

    verify(exactly = 0) {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>())
    }
  }

  @Test
  fun register_losingTheConnectionDuringTheWrite_isANetworkError() = runTest {
    failTheCommitWith(FirebaseFirestoreException.Code.UNAVAILABLE)

    assertEquals(RegisterResult.NetworkError, repository.register("event-1"))
  }

  @Test
  fun register_anotherFirestoreFailure_isAnUnexpectedError() = runTest {
    failBeforeReading(failed(FirebaseFirestoreException.Code.ABORTED))

    assertEquals(RegisterResult.UnexpectedError, repository.register("event-1"))
  }

  @Test
  fun register_anUnexpectedException_isAnUnexpectedError() = runTest {
    every { db.collection(EVENTS_COLLECTION) } throws IllegalStateException("terminated")

    assertEquals(RegisterResult.UnexpectedError, repository.register("event-1"))
  }

  @Test
  fun register_aTaskCancelledWhileTheCallerRuns_isAnUnexpectedError() = runTest {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } returns
        Tasks.forCanceled()

    assertEquals(RegisterResult.UnexpectedError, repository.register("event-1"))
  }

  @Test
  fun register_cancellingTheCaller_stillCancelsIt() = runTest {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } returns
        TaskCompletionSource<Any>().task
    var result: RegisterResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) { result = repository.register("event-1") }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }

  // cancel

  @Test
  fun cancel_deletesTheStudentsRegistrationInATransactionTriedOnce() = runTest {
    assertEquals(CancelRegistrationResult.Cancelled, repository.cancel("event-1"))

    verify { transaction.delete(attendee) }
    assertEquals(1, options.captured.maxAttempts)
  }

  @Test
  fun cancel_anIdThatCannotBeADocumentId_hasNothingToCancel() = runTest {
    assertEquals(CancelRegistrationResult.Cancelled, repository.cancel("events/other"))

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun cancel_nobodySignedIn_deletesNothing() = runTest {
    auth.user = null

    assertEquals(CancelRegistrationResult.NotSignedIn, repository.cancel("event-1"))

    verify(exactly = 0) {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>())
    }
  }

  @Test
  fun cancel_offline_isANetworkErrorWithoutStartingADelete() = runTest {
    online = false

    assertEquals(CancelRegistrationResult.NetworkError, repository.cancel("event-1"))

    verify(exactly = 0) {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>())
    }
  }

  @Test
  fun cancel_losingTheConnectionDuringTheDelete_isANetworkError() = runTest {
    failTheCommitWith(FirebaseFirestoreException.Code.UNAVAILABLE)

    assertEquals(CancelRegistrationResult.NetworkError, repository.cancel("event-1"))
  }

  @Test
  fun cancel_aDeleteTheRulesDeny_isAnUnexpectedError() = runTest {
    failTheCommitWith(FirebaseFirestoreException.Code.PERMISSION_DENIED)

    assertEquals(CancelRegistrationResult.UnexpectedError, repository.cancel("event-1"))
  }

  @Test
  fun cancel_anUnexpectedException_isAnUnexpectedError() = runTest {
    every { db.collection(EVENTS_COLLECTION) } throws IllegalStateException("terminated")

    assertEquals(CancelRegistrationResult.UnexpectedError, repository.cancel("event-1"))
  }

  @Test
  fun cancel_aTaskCancelledWhileTheCallerRuns_isAnUnexpectedError() = runTest {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } returns
        Tasks.forCanceled()

    assertEquals(CancelRegistrationResult.UnexpectedError, repository.cancel("event-1"))
  }

  @Test
  fun cancel_cancellingTheCaller_stillCancelsIt() = runTest {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } returns
        TaskCompletionSource<Any>().task
    var result: CancelRegistrationResult? = null
    val call = launch(start = CoroutineStart.UNDISPATCHED) { result = repository.cancel("event-1") }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }

  // observeMyRegistrations

  private val group = mockk<Query>()
  private val mine = mockk<Query>()
  private val registration = mockk<ListenerRegistration>(relaxed = true)
  private val listener = slot<EventListener<QuerySnapshot>>()

  /** Stubs exactly the query the repository must build, so any other query fails the test. */
  private fun stubMyRegistrationsQuery() {
    every { db.collectionGroup(ATTENDEES_COLLECTION) } returns group
    every { group.whereEqualTo("uid", "student") } returns mine
    every { mine.addSnapshotListener(MetadataChanges.INCLUDE, capture(listener)) } returns
        registration
  }

  /** A registration of the student, stored under `events/[eventId]/attendees`. */
  private fun registrationFor(eventId: String) =
      mockk<DocumentSnapshot>().also {
        val eventDocument = mockk<DocumentReference> { every { id } returns eventId }
        val attendeesCollection =
            mockk<CollectionReference> { every { parent } returns eventDocument }
        every { it.reference } returns mockk { every { parent } returns attendeesCollection }
      }

  private fun registrations(vararg eventIds: String, fromCache: Boolean = false) =
      mockk<QuerySnapshot>().also {
        every { it.documents } returns eventIds.map(::registrationFor)
        every { it.metadata } returns
            mockk<SnapshotMetadata>().also { m -> every { m.isFromCache } returns fromCache }
      }

  @Test
  fun observe_emitsTheEventIdsOnEveryChangeAndSaysWhenTheyAreCached() = runTest {
    stubMyRegistrationsQuery()
    val results = mutableListOf<MyRegistrationsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeMyRegistrations().collect { results += it }
        }

    listener.captured.onEvent(registrations("event-1", "event-2", fromCache = true), null)
    listener.captured.onEvent(registrations("event-2"), null)
    collecting.cancel()

    assertEquals(
        listOf(
            MyRegistrationsResult.Registrations(setOf("event-1", "event-2"), fromCache = true),
            MyRegistrationsResult.Registrations(setOf("event-2"), fromCache = false),
        ),
        results,
    )
    verify { registration.remove() }
  }

  @Test
  fun observe_nobodySignedIn_isAnErrorWithoutAQuery() = runTest {
    auth.user = null

    assertEquals(listOf(MyRegistrationsResult.Error), repository.observeMyRegistrations().toList())

    verify(exactly = 0) { db.collectionGroup(any()) }
  }

  @Test
  fun observe_aFailedQuery_isAnErrorAndEndsTheFlow() = runTest {
    stubMyRegistrationsQuery()
    val results =
        async(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeMyRegistrations().toList()
        }

    listener.captured.onEvent(
        null,
        failed(FirebaseFirestoreException.Code.PERMISSION_DENIED),
    )

    assertEquals(listOf(MyRegistrationsResult.Error), results.await())
  }

  @Test
  fun observe_aNullSnapshotWithoutAnError_isAnErrorAndEndsTheFlow() = runTest {
    stubMyRegistrationsQuery()
    val results =
        async(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeMyRegistrations().toList()
        }

    listener.captured.onEvent(null, null)

    assertEquals(listOf(MyRegistrationsResult.Error), results.await())
  }

  @Test
  fun observe_aSnapshotThatCannotBeRead_isAnErrorAndEndsTheFlow() = runTest {
    stubMyRegistrationsQuery()
    val unreadable =
        mockk<QuerySnapshot>().also {
          every { it.documents } throws IllegalStateException("terminated")
        }
    val results =
        async(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeMyRegistrations().toList()
        }

    listener.captured.onEvent(unreadable, null)

    assertEquals(listOf(MyRegistrationsResult.Error), results.await())
  }

  @Test
  fun observe_anUnexpectedExceptionWhileStartingToListen_isAnErrorAndEnds() = runTest {
    every { db.collectionGroup(ATTENDEES_COLLECTION) } throws IllegalStateException("terminated")

    assertEquals(listOf(MyRegistrationsResult.Error), repository.observeMyRegistrations().toList())
  }
}
