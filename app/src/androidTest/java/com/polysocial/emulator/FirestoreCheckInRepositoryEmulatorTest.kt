// Contributors: Claude (Opus 5.5, Anthropic) (check-in repository tests against the rules, #467).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.checkin.CHECK_INS_COLLECTION
import com.polysocial.model.checkin.CheckInResult
import com.polysocial.model.checkin.CheckInStatus
import com.polysocial.model.checkin.FirestoreCheckInRepository
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.toFirestoreMap
import com.polysocial.model.event.validEvent
import com.polysocial.model.registration.ATTENDEES_COLLECTION
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.FirestoreRegistrationRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreCheckInRepository] against the Firestore and Auth emulators, so its transaction,
 * its listener and the check-in rules (#467) run on the real Firestore. Events, registrations and
 * group markers are seeded as an admin would, bypassing the rules. The rules compare times with the
 * real `request.time`, so events are seeded around now, with margins of minutes.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestoreCheckInRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  private fun repository(clock: Clock = Clock.systemUTC()) =
      FirestoreCheckInRepository(
          db = emulator.firestore,
          auth = EmulatorSignedInUser(emulator),
          network = AlwaysOnline,
          clock = clock,
      )

  private suspend fun signInStudent(): String = emulator.signInVerifiedUser(STUDENT_EMAIL).uid

  /** Seeds an event [id] from [startsIn] (negative: already started) for [lasts], or no end. */
  private suspend fun seedEvent(
      id: String,
      startsIn: Duration = Duration.ofMinutes(-10),
      lasts: Duration? = Duration.ofHours(1),
      isPrivate: Boolean = false,
  ) {
    val start = Instant.now() + startsIn
    val event = validEvent(startTime = start, endTime = lasts?.let { start + it })
    emulator.createAsAdmin(
        EVENTS_COLLECTION,
        id,
        event
            .copy(
                isPrivate = isPrivate,
                createdBy = ORGANIZER_UID,
                organizerIds = listOf(ORGANIZER_UID),
                allowedUids = listOf(ORGANIZER_UID),
            )
            .toFirestoreMap(),
    )
  }

  private suspend fun seedRegistration(eventId: String, uid: String) =
      emulator.createAsAdmin(
          "$EVENTS_COLLECTION/$eventId/$ATTENDEES_COLLECTION",
          uid,
          mapOf("uid" to uid, "registeredAt" to Instant.parse("2030-02-01T12:00:00Z")),
      )

  private suspend fun seedGroupMember(eventId: String, uid: String, groupId: String) =
      emulator.createAsAdmin(
          "$EVENTS_COLLECTION/$eventId/groupMembers",
          uid,
          mapOf("groupId" to groupId),
      )

  private suspend fun isCheckedIn(eventId: String, uid: String) =
      emulator.existsAsAdmin("$EVENTS_COLLECTION/$eventId/$CHECK_INS_COLLECTION", uid)

  /** The first status of [uid]'s check-in that isn't [CheckInStatus.NotCheckedIn]. */
  private suspend fun FirestoreCheckInRepository.checkedInStatus(eventId: String, uid: String) =
      withTimeout(TIMEOUT_MS) {
        observeCheckIn(eventId, uid).first { it != CheckInStatus.NotCheckedIn }
      }

  @After fun signOutTheOtherStudents() = StudentApps.signOutAll()

  @Test
  fun checkIn_aRegisteredStudentDuringTheWindow_isStoredAndObservedWithTheServersTime() =
      runBlocking {
        val uid = signInStudent()
        seedEvent("live")
        seedRegistration("live", uid)
        val before = Instant.now()

        assertEquals(CheckInResult.CheckedIn, repository().checkIn("live"))

        assertTrue(isCheckedIn("live", uid))
        val status = repository().checkedInStatus("live", uid)
        assertTrue("Expected a check-in, got $status", status is CheckInStatus.CheckedIn)
        val at = (status as CheckInStatus.CheckedIn).checkedInAt
        assertTrue(Duration.between(before, at).abs() < Duration.ofMinutes(1))
      }

  @Test
  fun checkIn_twice_keepsTheFirstTime() = runBlocking {
    val uid = signInStudent()
    seedEvent("live")
    seedRegistration("live", uid)
    repository().checkIn("live")
    val first = repository().checkedInStatus("live", uid)

    assertEquals(CheckInResult.CheckedIn, repository().checkIn("live"))

    assertEquals(first, repository().checkedInStatus("live", uid))
  }

  @Test
  fun checkIn_withoutAnEndTime_isAcceptedByTheRules() = runBlocking {
    val uid = signInStudent()
    seedEvent("open", startsIn = Duration.ofHours(-2), lasts = null)
    seedRegistration("open", uid)

    assertEquals(CheckInResult.CheckedIn, repository().checkIn("open"))
    assertTrue(isCheckedIn("open", uid))
  }

  @Test
  fun checkIn_notRegistered_isNotRegistered_andStoresNothing() = runBlocking {
    val uid = signInStudent()
    seedEvent("live")

    assertEquals(CheckInResult.NotRegistered, repository().checkIn("live"))
    assertFalse(isCheckedIn("live", uid))
  }

  @Test
  fun checkIn_aMissingEvent_orAPrivateOneTheStudentCannotRead_isNotFound() = runBlocking {
    val uid = signInStudent()
    seedRegistration("gone", uid)
    seedEvent("private", isPrivate = true)
    seedRegistration("private", uid)

    assertEquals(CheckInResult.NotFound, repository().checkIn("gone"))
    assertEquals(CheckInResult.NotFound, repository().checkIn("private"))
    assertFalse(isCheckedIn("private", uid))
  }

  @Test
  fun checkIn_beforeTheWindow_isOutsideWindow() = runBlocking {
    val uid = signInStudent()
    seedEvent("later", startsIn = Duration.ofHours(2))
    seedRegistration("later", uid)

    assertEquals(CheckInResult.OutsideWindow, repository().checkIn("later"))
    assertFalse(isCheckedIn("later", uid))
  }

  @Test
  fun checkIn_aDeviceClockInsideTheWindow_isRefusedByTheRules_atTheServersTime() = runBlocking {
    val uid = signInStudent()
    seedEvent("later", startsIn = Duration.ofHours(2))
    seedRegistration("later", uid)
    val wrongClock = Clock.fixed(Instant.now() + Duration.ofHours(2), ZoneOffset.UTC)

    assertEquals(CheckInResult.UnexpectedError, repository(wrongClock).checkIn("later"))
    assertFalse(isCheckedIn("later", uid))
  }

  @Test
  fun checkIn_offline_isANetworkError_andNothingIsQueued() = runBlocking {
    val uid = signInStudent()
    seedEvent("live")
    seedRegistration("live", uid)

    val result =
        whileOffline(emulator.firestore.collection(EVENTS_COLLECTION).document("live")) {
          withTimeout(OFFLINE_TIMEOUT_MS) { repository().checkIn("live") }
        }

    assertEquals(CheckInResult.NetworkError, result)
    assertFalse(isCheckedIn("live", uid))
  }

  @Test
  fun observe_offline_showsTheCheckInFromTheCache() = runBlocking {
    val uid = signInStudent()
    seedEvent("live")
    seedRegistration("live", uid)
    assertEquals(CheckInResult.CheckedIn, repository().checkIn("live"))
    // As in the app: the event's screen stays open, so the in-memory cache keeps the check-in.
    val open = launch { repository().observeCheckIn("live", uid).collect {} }
    val cached =
        try {
          repository().checkedInStatus("live", uid)
          whileOffline(emulator.firestore.collection(EVENTS_COLLECTION).document("live")) {
            withTimeout(OFFLINE_TIMEOUT_MS) { repository().observeCheckIn("live", uid).first() }
          }
        } finally {
          open.cancel()
        }

    assertTrue("Expected the cached check-in, got $cached", cached is CheckInStatus.CheckedIn)
  }

  @Test
  fun observe_aMemberOfTheStudentsGroupSeesTheirCheckIn_andAnotherGroupDoesNot() = runBlocking {
    val uid = signInStudent()
    seedEvent("live")
    seedRegistration("live", uid)
    repository().checkIn("live")
    val member = StudentApps.all[0]
    val memberUid = emulator.signInVerifiedUser(MEMBER_EMAIL, auth = member.auth).uid
    val memberRepository =
        FirestoreCheckInRepository(
            member.firestore,
            EmulatorSignedInUser(emulator, member.auth),
            AlwaysOnline,
            Clock.systemUTC(),
        )

    seedGroupMember("live", uid, "g1")
    seedGroupMember("live", memberUid, "g2")
    assertEquals(
        CheckInStatus.Error,
        withTimeout(TIMEOUT_MS) { memberRepository.observeCheckIn("live", uid).first() },
    )

    emulator.deleteAsAdmin("$EVENTS_COLLECTION/live/groupMembers", memberUid)
    seedGroupMember("live", memberUid, "g1")
    assertTrue(memberRepository.checkedInStatus("live", uid) is CheckInStatus.CheckedIn)
  }

  @Test
  fun cancellingTheRegistration_deletesTheCheckIn() = runBlocking {
    val uid = signInStudent()
    seedEvent("live")
    seedRegistration("live", uid)
    repository().checkIn("live")
    assertTrue(isCheckedIn("live", uid))
    val registrations =
        FirestoreRegistrationRepository(
            emulator.firestore,
            EmulatorSignedInUser(emulator),
            AlwaysOnline,
        )

    assertEquals(CancelRegistrationResult.Cancelled, registrations.cancel("live"))

    assertFalse(isCheckedIn("live", uid))
  }

  private companion object {
    /** Fictional accounts and events: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val MEMBER_EMAIL = "test.member@epfl.ch"
    const val ORGANIZER_UID = "fictional-organizer"
    const val TIMEOUT_MS = 10_000L

    /** Offline, a transaction fails within Firestore's own timeout, well under this. */
    const val OFFLINE_TIMEOUT_MS = 60_000L
  }
}
