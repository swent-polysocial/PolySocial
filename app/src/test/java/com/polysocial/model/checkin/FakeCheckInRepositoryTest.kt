// Contributors: Claude (Opus 5.5, Anthropic) (the fake check-in repository's tests, #467; the
// entrance code, #468).
package com.polysocial.model.checkin

import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The fake follows the [CheckInRepository] contracts the screens will be tested against. */
class FakeCheckInRepositoryTest {
  private val start = Instant.parse("2026-11-27T19:00:00Z")
  private val event =
      Event(
          id = "event-1",
          title = "Concert",
          description = "",
          category = EventCategory.CULTURE,
          location = Coordinates(46.5184, 6.5680),
          startTime = start,
          endTime = start + Duration.ofHours(4),
          isPrivate = false,
      )

  private val repository =
      FakeCheckInRepository(currentUid = "student", now = start).apply {
        readableEvents += event
        seedRegistration("event-1", "student")
      }

  private suspend fun statusOf(uid: String) = repository.observeCheckIn("event-1", uid).first()

  @Test
  fun checkIn_storesTheTime_andTwiceKeepsTheFirst() = runTest {
    assertEquals(CheckInResult.CheckedIn, repository.checkIn("event-1"))
    assertEquals(CheckInStatus.CheckedIn(start), statusOf("student"))

    repository.now = start + Duration.ofMinutes(5)
    assertEquals(CheckInResult.CheckedIn, repository.checkIn("event-1"))
    assertEquals(CheckInStatus.CheckedIn(start), statusOf("student"))
  }

  @Test
  fun alreadyCheckedIn_isCheckedIn_evenOnceTheWindowHasClosed() = runTest {
    repository.checkIn("event-1")
    repository.now = event.endTime!!

    assertEquals(CheckInResult.CheckedIn, repository.checkIn("event-1"))
  }

  @Test
  fun checkIn_followsTheWindow() = runTest {
    repository.now = start - Duration.ofMinutes(30) - Duration.ofNanos(1)
    assertEquals(CheckInResult.OutsideWindow, repository.checkIn("event-1"))

    repository.now = event.endTime!!
    assertEquals(CheckInResult.OutsideWindow, repository.checkIn("event-1"))
    assertEquals(CheckInStatus.NotCheckedIn, statusOf("student"))

    repository.now = start - Duration.ofMinutes(30)
    assertEquals(CheckInResult.CheckedIn, repository.checkIn("event-1"))
  }

  @Test
  fun aStudentWhoIsNotRegistered_cannotCheckIn() = runTest {
    repository.currentUid = "other"

    assertEquals(CheckInResult.NotRegistered, repository.checkIn("event-1"))
    assertNull(repository.checkInOf("event-1", "other"))
  }

  @Test
  fun anEventTheStudentCannotRead_orAnInvalidId_isNotFound() = runTest {
    repository.seedRegistration("private", "student")

    assertEquals(CheckInResult.NotFound, repository.checkIn("private"))
    assertEquals(CheckInResult.NotFound, repository.checkIn("a/b"))
    assertNull(repository.checkInOf("private", "student"))
  }

  @Test
  fun nobodySignedIn_cannotCheckInOrObserve() = runTest {
    repository.currentUid = null

    assertEquals(CheckInResult.NotSignedIn, repository.checkIn("event-1"))
    assertEquals(CheckInStatus.Error, statusOf("student"))
  }

  @Test
  fun anInvalidId_isNotFound_beforeTheUserIsChecked() = runTest {
    repository.currentUid = null

    assertEquals(CheckInResult.NotFound, repository.checkIn("a/b"))
  }

  @Test
  fun aFailure_isReturnedBeforeAnythingIsStored() = runTest {
    repository.checkInFailure = CheckInResult.NetworkError

    assertEquals(CheckInResult.NetworkError, repository.checkIn("event-1"))
    assertNull(repository.checkInOf("event-1", "student"))
  }

  @Test
  fun observeCheckIn_showsAGroupMembersCheckIn_live() =
      runTest(UnconfinedTestDispatcher()) {
        val statuses = mutableListOf<CheckInStatus>()
        val job = launch { repository.observeCheckIn("event-1", "member").take(2).toList(statuses) }

        repository.seedCheckIn("event-1", "member", start + Duration.ofMinutes(8))
        job.join()

        assertEquals(
            listOf(
                CheckInStatus.NotCheckedIn,
                CheckInStatus.CheckedIn(start + Duration.ofMinutes(8)),
            ),
            statuses,
        )
      }

  @Test
  fun anUnreadableCheckIn_isAnError_forThatStudentOnly() = runTest {
    repository.seedCheckIn("event-1", "member", start)
    repository.unreadableUids += "left-the-group"

    assertEquals(CheckInStatus.Error, statusOf("left-the-group"))
    assertEquals(CheckInStatus.CheckedIn(start), statusOf("member"))
  }

  @Test
  fun anInvalidId_cannotBeObserved() = runTest {
    assertEquals(CheckInStatus.Error, repository.observeCheckIn("a/b", "student").first())
    assertEquals(CheckInStatus.Error, repository.observeCheckIn("event-1", "").first())
  }

  // The entrance code (#468)

  @Test
  fun theFirstRotation_createsACode_andTheNextReplacesItKeepingThePreviousToken() = runTest {
    assertEquals(EntranceCodeStatus.Missing, repository.observeEntranceCode("event-1").first())

    assertEquals(RotateCodeResult.Done, repository.rotateEntranceCode("event-1", null))
    val first = repository.codeOf("event-1")!!
    assertEquals(null, first.previousToken)
    assertEquals(start, first.rotatedAt)

    repository.now = start + Duration.ofMinutes(1)
    repository.rotateEntranceCode("event-1", first.token)
    val second = repository.codeOf("event-1")!!
    assertEquals(first.token, second.previousToken)
    assertEquals(
        EntranceCodeStatus.Code(second, fromCache = false),
        repository.observeEntranceCode("event-1").first(),
    )
  }

  @Test
  fun aRotationOverAnOutdatedToken_writesNothing_likeASecondPhone() = runTest {
    repository.rotateEntranceCode("event-1", null)
    val current = repository.codeOf("event-1")

    repository.rotateEntranceCode("event-1", "not-the-current-token")
    repository.rotateEntranceCode("event-1", null)

    assertEquals(current, repository.codeOf("event-1"))
    assertEquals(listOf(null, "not-the-current-token", null), repository.rotations)
  }

  @Test
  fun aRotationFailure_orAReadFailure_isReported() = runTest {
    repository.rotateFailure = RotateCodeResult.NetworkError
    assertEquals(RotateCodeResult.NetworkError, repository.rotateEntranceCode("event-1", null))
    assertEquals(null, repository.codeOf("event-1"))

    repository.codeReadFailure = true
    assertEquals(EntranceCodeStatus.Error, repository.observeEntranceCode("event-1").first())
  }
}
