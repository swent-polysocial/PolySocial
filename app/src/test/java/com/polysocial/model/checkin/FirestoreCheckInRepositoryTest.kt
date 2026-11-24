// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, test-first, #467; the entrance
// code, #468).
package com.polysocial.model.checkin

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED
import com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
import com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
import com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.toFirestoreMap
import com.polysocial.model.event.validEvent
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.ATTENDEES_COLLECTION
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unit tests of [FirestoreCheckInRepository] with a mocked Firestore: the order of the checks, what
 * it writes, and how each failure is classified. The emulator tests run it against the rules.
 */
@RunWith(AndroidJUnit4::class)
class FirestoreCheckInRepositoryTest {
  private val start = Instant.parse("2026-11-27T19:00:00.123456Z")
  private val end = start + Duration.ofHours(4)
  private val storedEvent = validEvent(startTime = start, endTime = end).toFirestoreMap()

  private val db = mockk<FirebaseFirestore>()
  private val events = mockk<CollectionReference>()
  private val event = mockk<DocumentReference>()
  private val checkIns = mockk<CollectionReference>()
  private val checkIn = mockk<DocumentReference>()
  private val attendees = mockk<CollectionReference>()
  private val attendee = mockk<DocumentReference>()
  private val transaction = mockk<Transaction>()
  private val written = slot<Map<String, Any>>()

  /** The options of every transaction run, in order. */
  private val options = mutableListOf<TransactionOptions>()

  /** How the next transactions' commits end, in order: null commits, an exception fails it. */
  private val commits = ArrayDeque<FirebaseFirestoreException?>()

  private val auth = FakeAuthRepository(AuthUser("student", "student@epfl.ch", true))
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }
  private var now = start

  private val clock =
      object : Clock() {
        override fun instant() = now

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this
      }

  private val repository = FirestoreCheckInRepository(db, auth, network, clock)

  private fun snapshot(exists: Boolean, data: Map<String, Any?>? = null) =
      mockk<DocumentSnapshot>().also {
        every { it.exists() } returns exists
        every { it.data } returns data
      }

  private fun failed(code: FirebaseFirestoreException.Code) =
      FirebaseFirestoreException("failed", code)

  @Before
  fun stubFirestore() {
    every { db.collection(EVENTS_COLLECTION) } returns events
    every { events.document("event-1") } returns event
    every { event.collection(CHECK_INS_COLLECTION) } returns checkIns
    every { checkIns.document("student") } returns checkIn
    every { event.collection(ATTENDEES_COLLECTION) } returns attendees
    every { attendees.document("student") } returns attendee
    stubStored(event = storedEvent, checkedIn = false, registered = true)
    every { transaction.set(checkIn, capture(written)) } returns transaction
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } answers
        {
          options += firstArg<TransactionOptions>()
          try {
            val result = secondArg<Transaction.Function<Any>>().apply(transaction)
            commits.removeFirstOrNull()?.let { Tasks.forException(it) } ?: Tasks.forResult(result)
          } catch (e: FirebaseFirestoreException) {
            // A read that fails fails the transaction, like the SDK's.
            Tasks.forException(e)
          }
        }
  }

  /** What the transaction reads: the event (null: missing), the check-in and the registration. */
  private fun stubStored(event: Map<String, Any?>?, checkedIn: Boolean, registered: Boolean) {
    every { transaction.get(this@FirestoreCheckInRepositoryTest.event) } returns
        snapshot(event != null, event)
    every { transaction.get(checkIn) } returns snapshot(checkedIn)
    every { transaction.get(attendee) } returns snapshot(registered)
  }

  private fun noWrite() = verify(exactly = 0) { transaction.set(any(), any()) }

  // checkIn

  @Test
  fun checkIn_writesExactlyTheFieldsTheRulesExpect() = runTest {
    assertEquals(CheckInResult.CheckedIn, repository.checkIn("event-1"))

    val data = written.captured
    assertEquals(setOf("uid", "checkedInAt", "method", "expiresAt"), data.keys)
    assertEquals("student", data["uid"])
    assertSame(FieldValue.serverTimestamp(), data["checkedInAt"])
    assertEquals("location", data["method"])
  }

  @Test
  fun checkIn_expiresAtTheWindowsEndPlus30Days_toTheNanosecond() = runTest {
    repository.checkIn("event-1")

    val expected = end + Duration.ofDays(30)
    assertEquals(Timestamp(expected.epochSecond, expected.nano), written.captured["expiresAt"])
  }

  @Test
  fun checkIn_withoutAnEndTime_expires3HoursAfterTheStartPlus30Days() = runTest {
    stubStored(
        validEvent(startTime = start, endTime = null).toFirestoreMap(),
        checkedIn = false,
        registered = true,
    )

    repository.checkIn("event-1")

    val expected = start + Duration.ofHours(3) + Duration.ofDays(30)
    assertEquals(Timestamp(expected.epochSecond, expected.nano), written.captured["expiresAt"])
  }

  @Test
  fun checkIn_runsOneTransactionWithTheSdksDefaultAttempts() = runTest {
    repository.checkIn("event-1")

    assertEquals(
        listOf(TransactionOptions.Builder().build().maxAttempts),
        options.map { it.maxAttempts },
    )
  }

  @Test
  fun checkIn_alreadyCheckedIn_isCheckedIn_withoutWritingAgain_evenAfterTheWindow() = runTest {
    stubStored(storedEvent, checkedIn = true, registered = true)
    now = end + Duration.ofHours(1)

    assertEquals(CheckInResult.CheckedIn, repository.checkIn("event-1"))
    noWrite()
  }

  @Test
  fun checkIn_notRegistered_isNotRegistered() = runTest {
    stubStored(storedEvent, checkedIn = false, registered = false)

    assertEquals(CheckInResult.NotRegistered, repository.checkIn("event-1"))
    noWrite()
  }

  @Test
  fun checkIn_aMissingOrMalformedEvent_isNotFound() = runTest {
    stubStored(event = null, checkedIn = false, registered = true)
    assertEquals(CheckInResult.NotFound, repository.checkIn("event-1"))

    stubStored(event = mapOf("title" to 3), checkedIn = false, registered = true)
    assertEquals(CheckInResult.NotFound, repository.checkIn("event-1"))
    noWrite()
  }

  @Test
  fun checkIn_anEventTheRulesDontLetTheStudentRead_isNotFound() = runTest {
    every { transaction.get(event) } throws failed(PERMISSION_DENIED)

    assertEquals(CheckInResult.NotFound, repository.checkIn("event-1"))
    noWrite()
  }

  @Test
  fun checkIn_followsTheWindowAtTheClocksTime() = runTest {
    now = start - Duration.ofMinutes(30) - Duration.ofNanos(1)
    assertEquals(CheckInResult.OutsideWindow, repository.checkIn("event-1"))
    now = end
    assertEquals(CheckInResult.OutsideWindow, repository.checkIn("event-1"))
    noWrite()

    now = start - Duration.ofMinutes(30)
    assertEquals(CheckInResult.CheckedIn, repository.checkIn("event-1"))
  }

  @Test
  fun checkIn_readsTheEventFirst_soAMissingEventIsNotFoundBeforeTheRest() = runTest {
    stubStored(event = null, checkedIn = true, registered = false)

    assertEquals(CheckInResult.NotFound, repository.checkIn("event-1"))
  }

  @Test
  fun checkIn_readsTheCheckInBeforeTheRegistration() = runTest {
    stubStored(storedEvent, checkedIn = true, registered = false)

    assertEquals(CheckInResult.CheckedIn, repository.checkIn("event-1"))
  }

  @Test
  fun checkIn_readsTheRegistrationBeforeTheWindow() = runTest {
    stubStored(storedEvent, checkedIn = false, registered = false)
    now = end + Duration.ofHours(1)

    assertEquals(CheckInResult.NotRegistered, repository.checkIn("event-1"))
    noWrite()
  }

  @Test
  fun checkIn_aCommitTheRulesRefuse_isUnexpected() = runTest {
    commits += failed(PERMISSION_DENIED)

    assertEquals(CheckInResult.UnexpectedError, repository.checkIn("event-1"))
  }

  @Test
  fun checkIn_aCheckInOrRegistrationReadTheRulesRefuse_isUnexpected() = runTest {
    every { transaction.get(checkIn) } throws failed(PERMISSION_DENIED)
    assertEquals(CheckInResult.UnexpectedError, repository.checkIn("event-1"))

    stubStored(storedEvent, checkedIn = false, registered = true)
    every { transaction.get(attendee) } throws failed(PERMISSION_DENIED)
    assertEquals(CheckInResult.UnexpectedError, repository.checkIn("event-1"))
  }

  @Test
  fun checkIn_theServerUnreachable_isANetworkError() = runTest {
    commits += failed(UNAVAILABLE)
    assertEquals(CheckInResult.NetworkError, repository.checkIn("event-1"))

    every { transaction.get(event) } throws failed(DEADLINE_EXCEEDED)
    assertEquals(CheckInResult.NetworkError, repository.checkIn("event-1"))
  }

  @Test
  fun checkIn_anyOtherFailure_isUnexpected() = runTest {
    commits += failed(ABORTED)
    assertEquals(CheckInResult.UnexpectedError, repository.checkIn("event-1"))

    every { transaction.get(event) } throws IllegalStateException("broken")
    assertEquals(CheckInResult.UnexpectedError, repository.checkIn("event-1"))
  }

  @Test
  fun checkIn_aTaskCancelledWhileTheCallerRuns_isUnexpected() = runTest {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } returns
        Tasks.forCanceled()

    assertEquals(CheckInResult.UnexpectedError, repository.checkIn("event-1"))
  }

  @Test
  fun checkIn_cancellingTheCaller_stillCancelsIt() = runTest {
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } returns
        TaskCompletionSource<Any>().task
    var result: CheckInResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) { result = repository.checkIn("event-1") }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertEquals(null, result)
  }

  @Test
  fun checkIn_offline_isANetworkError_withoutStartingATransaction() = runTest {
    online = false

    assertEquals(CheckInResult.NetworkError, repository.checkIn("event-1"))
    verify(exactly = 0) {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>())
    }
  }

  @Test
  fun checkIn_nobodySignedIn_isNotSignedIn_beforeTheNetwork() = runTest {
    auth.user = null
    online = false

    assertEquals(CheckInResult.NotSignedIn, repository.checkIn("event-1"))
  }

  @Test
  fun checkIn_anInvalidId_isNotFound_beforeTheUser() = runTest {
    auth.user = null

    assertEquals(CheckInResult.NotFound, repository.checkIn("a/b"))
    assertEquals(CheckInResult.NotFound, repository.checkIn(" "))
    verify(exactly = 0) { db.collection(any()) }
  }

  // observeCheckIn

  private val registration = mockk<ListenerRegistration>(relaxed = true)
  private val listener = slot<EventListener<DocumentSnapshot>>()

  private fun stubListener(uid: String = "member") {
    val document = mockk<DocumentReference>()
    every { checkIns.document(uid) } returns document
    every { document.addSnapshotListener(capture(listener)) } returns registration
  }

  private fun stored(checkedInAt: Any?) =
      mockk<DocumentSnapshot>().also {
        every { it.exists() } returns true
        if (checkedInAt is Timestamp?) {
          every { it.getTimestamp("checkedInAt", ServerTimestampBehavior.ESTIMATE) } returns
              checkedInAt
        } else {
          every { it.getTimestamp("checkedInAt", ServerTimestampBehavior.ESTIMATE) } throws
              RuntimeException("Field 'checkedInAt' is not a Timestamp")
        }
      }

  private fun missing() = mockk<DocumentSnapshot>().also { every { it.exists() } returns false }

  @Test
  fun observe_emitsTheCheckInOnEveryChange_andStopsListeningWhenCancelled() = runTest {
    stubListener()
    val statuses = mutableListOf<CheckInStatus>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeCheckIn("event-1", "member").collect { statuses += it }
        }

    listener.captured.onEvent(missing(), null)
    listener.captured.onEvent(stored(Timestamp(start.epochSecond, start.nano)), null)
    collecting.cancel()

    assertEquals(listOf(CheckInStatus.NotCheckedIn, CheckInStatus.CheckedIn(start)), statuses)
    verify { registration.remove() }
  }

  @Test
  fun observe_aListenerFailure_isAnError_andEndsTheFlow() = runTest {
    stubListener()
    val statuses = mutableListOf<CheckInStatus>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeCheckIn("event-1", "member").toList(statuses)
        }

    listener.captured.onEvent(null, failed(PERMISSION_DENIED))

    assertEquals(listOf(CheckInStatus.Error), statuses)
    assertTrue(collecting.isCompleted)
    verify { registration.remove() }
  }

  @Test
  fun observe_aMissingOrWrongTypedTime_isAnError_insteadOfACrash() = runTest {
    for (time in listOf(null, "20:08")) {
      stubListener()
      val statuses = mutableListOf<CheckInStatus>()
      val collecting =
          launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeCheckIn("event-1", "member").toList(statuses)
          }

      listener.captured.onEvent(stored(time), null)

      assertEquals(listOf(CheckInStatus.Error), statuses)
      assertTrue(collecting.isCompleted)
    }
  }

  @Test
  fun observe_nobodySignedIn_orAnInvalidId_isAnErrorWithoutAListener() = runTest {
    assertEquals(listOf(CheckInStatus.Error), repository.observeCheckIn("a/b", "member").toList())
    assertEquals(listOf(CheckInStatus.Error), repository.observeCheckIn("event-1", "").toList())
    assertEquals(listOf(CheckInStatus.Error), repository.observeCheckIn("event-1", "x/y").toList())
    auth.user = null
    assertEquals(
        listOf(CheckInStatus.Error),
        repository.observeCheckIn("event-1", "member").toList(),
    )

    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun observe_aFailureBuildingTheListener_isAnError() = runTest {
    every { checkIns.document("member") } throws IllegalArgumentException("bad path")

    assertEquals(
        listOf(CheckInStatus.Error),
        repository.observeCheckIn("event-1", "member").toList(),
    )
    assertFalse(listener.isCaptured)
  }

  // The entrance code (#468)

  private val codes = mockk<CollectionReference>()
  private val code = mockk<DocumentReference>()
  private val codeWritten = slot<Map<String, Any>>()

  /** Stubs the code document, holding [token] (null: missing), and its write. */
  private fun stubCode(token: String?) {
    every { event.collection(CHECK_IN_CODE_COLLECTION) } returns codes
    every { codes.document(ENTRANCE_CODE_DOCUMENT) } returns code
    every { transaction.get(code) } returns
        mockk<DocumentSnapshot>().also {
          every { it.exists() } returns (token != null)
          every { it.getString("token") } returns token
        }
    every { transaction.set(code, capture(codeWritten)) } returns transaction
  }

  @Test
  fun rotate_writesANewTokenOverTheShownOne_keepingItAsThePreviousOne() = runTest {
    stubCode("a".repeat(22))

    assertEquals(RotateCodeResult.Done, repository.rotateEntranceCode("event-1", "a".repeat(22)))

    val data = codeWritten.captured
    assertEquals(setOf("token", "previousToken", "rotatedAt", "expiresAt"), data.keys)
    assertTrue(Regex("[A-Za-z0-9_-]{22}").matches(data["token"] as String))
    assertEquals("a".repeat(22), data["previousToken"])
    assertSame(FieldValue.serverTimestamp(), data["rotatedAt"])
    val expiry = end + Duration.ofDays(30)
    assertEquals(Timestamp(expiry.epochSecond, expiry.nano), data["expiresAt"])
  }

  @Test
  fun rotate_withoutACode_createsTheFirst_withoutAPreviousToken() = runTest {
    stubCode(null)

    assertEquals(RotateCodeResult.Done, repository.rotateEntranceCode("event-1", null))

    assertEquals(setOf("token", "rotatedAt", "expiresAt"), codeWritten.captured.keys)
  }

  @Test
  fun rotate_whenAnotherPhoneRotatedFirst_writesNothing() = runTest {
    stubCode("b".repeat(22))
    assertEquals(RotateCodeResult.Done, repository.rotateEntranceCode("event-1", "a".repeat(22)))

    stubCode("b".repeat(22))
    assertEquals(RotateCodeResult.Done, repository.rotateEntranceCode("event-1", null))

    verify(exactly = 0) { transaction.set(code, any()) }
  }

  @Test
  fun rotate_refusedBecauseAnotherPhoneRotatedFirst_isDone() = runTest {
    // The loser of a race is refused by the rules, not retried (CONTEXT.md, Traps): its
    // previousToken no longer matches. Read again, the code is no longer the one it replaced.
    stubCode("a".repeat(22))
    commits += failed(PERMISSION_DENIED)
    every { transaction.get(code) } returnsMany
        listOf(snapshotWithToken("a".repeat(22)), snapshotWithToken("b".repeat(22)))

    assertEquals(RotateCodeResult.Done, repository.rotateEntranceCode("event-1", "a".repeat(22)))
  }

  @Test
  fun rotate_refusedWhileTheCodeIsStillTheOneReplaced_isUnexpected() = runTest {
    stubCode("a".repeat(22))
    commits += failed(PERMISSION_DENIED)

    assertEquals(
        RotateCodeResult.UnexpectedError,
        repository.rotateEntranceCode("event-1", "a".repeat(22)),
    )
  }

  @Test
  fun rotate_refused_thenTheReReadFails_isClassified() = runTest {
    stubCode("a".repeat(22))
    commits += failed(PERMISSION_DENIED)
    commits += failed(UNAVAILABLE)
    assertEquals(
        RotateCodeResult.NetworkError,
        repository.rotateEntranceCode("event-1", "a".repeat(22)),
    )

    commits += failed(PERMISSION_DENIED)
    commits += failed(PERMISSION_DENIED)
    assertEquals(
        RotateCodeResult.UnexpectedError,
        repository.rotateEntranceCode("event-1", "a".repeat(22)),
    )
  }

  private fun snapshotWithToken(token: String?) =
      mockk<DocumentSnapshot>().also {
        every { it.exists() } returns (token != null)
        every { it.getString("token") } returns token
      }

  @Test
  fun rotate_aMissingEvent_isUnexpected_andWritesNothing() = runTest {
    stubCode(null)
    stubStored(event = null, checkedIn = false, registered = false)

    assertEquals(RotateCodeResult.UnexpectedError, repository.rotateEntranceCode("event-1", null))
    verify(exactly = 0) { transaction.set(code, any()) }
  }

  @Test
  fun rotate_offline_isANetworkError_beforeAnyTransaction() = runTest {
    online = false

    assertEquals(RotateCodeResult.NetworkError, repository.rotateEntranceCode("event-1", null))
    verify(exactly = 0) {
      db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>())
    }
  }

  @Test
  fun rotate_failures_areClassified() = runTest {
    stubCode(null)
    commits += failed(UNAVAILABLE)
    assertEquals(RotateCodeResult.NetworkError, repository.rotateEntranceCode("event-1", null))
    every { transaction.get(code) } throws IllegalStateException("broken")
    assertEquals(RotateCodeResult.UnexpectedError, repository.rotateEntranceCode("event-1", null))
  }

  @Test
  fun rotate_nobodySignedIn_orAnInvalidId_isUnexpected_withoutFirestore() = runTest {
    assertEquals(RotateCodeResult.UnexpectedError, repository.rotateEntranceCode("a/b", null))
    auth.user = null
    assertEquals(RotateCodeResult.UnexpectedError, repository.rotateEntranceCode("event-1", null))
    verify(exactly = 0) { db.collection(any()) }
  }

  @Test
  fun rotate_cancellingTheCaller_stillCancelsIt() = runTest {
    stubCode(null)
    every { db.runTransaction(any<TransactionOptions>(), any<Transaction.Function<Any>>()) } returns
        TaskCompletionSource<Any>().task
    var result: RotateCodeResult? = null
    val call =
        launch(start = CoroutineStart.UNDISPATCHED) {
          result = repository.rotateEntranceCode("event-1", null)
        }

    call.cancel()
    call.join()

    assertTrue(call.isCancelled)
    assertNull(result)
  }

  private val codeRegistration = mockk<ListenerRegistration>(relaxed = true)
  private val codeListener = slot<EventListener<DocumentSnapshot>>()

  private fun stubCodeListener() {
    every { event.collection(CHECK_IN_CODE_COLLECTION) } returns codes
    every { codes.document(ENTRANCE_CODE_DOCUMENT) } returns code
    every { code.addSnapshotListener(MetadataChanges.INCLUDE, capture(codeListener)) } returns
        codeRegistration
  }

  private fun storedCode(token: Any?, previous: Any?, rotatedAt: Timestamp?, fromCache: Boolean) =
      mockk<DocumentSnapshot>().also {
        every { it.exists() } returns true
        every { it.get("token") } returns token
        every { it.get("previousToken") } returns previous
        every { it.getTimestamp("rotatedAt", ServerTimestampBehavior.ESTIMATE) } returns rotatedAt
        every { it.metadata } returns
            mockk<SnapshotMetadata>().also { m -> every { m.isFromCache } returns fromCache }
      }

  @Test
  fun observeCode_emitsEachCode_andWhetherItIsCached() = runTest {
    stubCodeListener()
    val statuses = mutableListOf<EntranceCodeStatus>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeEntranceCode("event-1").collect { statuses += it }
        }
    val at = Timestamp(start.epochSecond, start.nano)

    codeListener.captured.onEvent(missing(), null)
    codeListener.captured.onEvent(storedCode("t1", null, at, fromCache = false), null)
    codeListener.captured.onEvent(storedCode("t2", "t1", at, fromCache = true), null)
    collecting.cancel()

    assertEquals(
        listOf(
            EntranceCodeStatus.Missing,
            EntranceCodeStatus.Code(EntranceCode("t1", null, start), fromCache = false),
            EntranceCodeStatus.Code(EntranceCode("t2", "t1", start), fromCache = true),
        ),
        statuses,
    )
    verify { codeRegistration.remove() }
  }

  @Test
  fun observeCode_aRefusedRead_orBadFields_isAnError_andEndsTheFlow() = runTest {
    val at = Timestamp(start.epochSecond, start.nano)
    for (send in
        listOf<(EventListener<DocumentSnapshot>) -> Unit>(
            { it.onEvent(null, failed(PERMISSION_DENIED)) },
            { it.onEvent(storedCode(42, null, at, false), null) },
            { it.onEvent(storedCode("t", 7, at, false), null) },
            { it.onEvent(storedCode("t", null, null, false), null) },
        )) {
      stubCodeListener()
      val statuses = mutableListOf<EntranceCodeStatus>()
      val collecting =
          launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeEntranceCode("event-1").toList(statuses)
          }

      send(codeListener.captured)

      assertEquals(listOf(EntranceCodeStatus.Error), statuses)
      assertTrue(collecting.isCompleted)
    }
  }

  @Test
  fun observeCode_nobodySignedIn_orAnInvalidId_isAnError_withoutAListener() = runTest {
    assertEquals(listOf(EntranceCodeStatus.Error), repository.observeEntranceCode("a/b").toList())
    auth.user = null
    assertEquals(
        listOf(EntranceCodeStatus.Error),
        repository.observeEntranceCode("event-1").toList(),
    )
    verify(exactly = 0) { db.collection(any()) }
  }
}
