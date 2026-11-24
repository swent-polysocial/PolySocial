// Contributors: Claude (Opus 5.5, Anthropic) (wrote this fake for tests, #467).
package com.polysocial.model.checkin

import com.polysocial.model.event.Event
import com.polysocial.model.event.isReadableEventId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * In-memory [CheckInRepository] for tests. [checkIn] checks in the order of the
 * [CheckInRepository.checkIn] contract: an ID that can't be a document ID, then the signed-in user,
 * then [checkInFailure], then [readableEvents], an existing check-in, the registrations seeded with
 * [seedRegistration] and the [checkInWindow] at [now], which is also the stored time.
 *
 * Like `FakeRegistrationRepository.countAttendees`, [observeCheckIn] reads anyone's check-in: it
 * doesn't check the caller's group. Put a UID in [unreadableUids] to get what the rules refuse.
 *
 * @property currentUid the signed-in student, or null when nobody is signed in.
 * @property now the time the fake uses as the server's.
 */
class FakeCheckInRepository(
    var currentUid: String? = "test-student",
    var now: Instant = Instant.EPOCH,
) : CheckInRepository {
  /** The events the student can read, the only ones they can check in at. */
  val readableEvents = mutableListOf<Event>()

  /** The students whose check-ins [observeCheckIn] can't read, like a refused read. */
  val unreadableUids = mutableSetOf<String>()

  /** When set, [checkIn] returns this result instead of checking in, like a failed write. */
  var checkInFailure: CheckInResult? = null

  private val registrations = mutableSetOf<Pair<String, String>>()

  /** The stored check-ins' times, by event ID and student UID. */
  private val checkIns = MutableStateFlow<Map<Pair<String, String>, Instant>>(emptyMap())

  /** Registers [uid] for [eventId], which [checkIn] requires. */
  fun seedRegistration(eventId: String, uid: String) {
    registrations += eventId to uid
  }

  /** Stores a check-in of [uid] at [eventId] at [checkedInAt], like another student's. */
  fun seedCheckIn(eventId: String, uid: String, checkedInAt: Instant) {
    checkIns.value += (eventId to uid) to checkedInAt
  }

  /** When [uid] checked in at [eventId], or null if they haven't. */
  fun checkInOf(eventId: String, uid: String): Instant? = checkIns.value[eventId to uid]

  override suspend fun checkIn(eventId: String): CheckInResult {
    if (!isReadableEventId(eventId)) return CheckInResult.NotFound
    val uid = currentUid ?: return CheckInResult.NotSignedIn
    checkInFailure?.let {
      return it
    }
    val event = readableEvents.find { it.id == eventId } ?: return CheckInResult.NotFound
    if (checkInOf(eventId, uid) != null) return CheckInResult.CheckedIn
    if (eventId to uid !in registrations) return CheckInResult.NotRegistered
    if (now !in checkInWindow(event)) return CheckInResult.OutsideWindow
    seedCheckIn(eventId, uid, now)
    return CheckInResult.CheckedIn
  }

  override fun observeCheckIn(eventId: String, uid: String): Flow<CheckInStatus> {
    if (currentUid == null || !isReadableEventId(eventId) || !isReadableEventId(uid)) {
      return flowOf(CheckInStatus.Error)
    }
    if (uid in unreadableUids) return flowOf(CheckInStatus.Error)
    return checkIns
        .map { stored ->
          stored[eventId to uid]?.let { CheckInStatus.CheckedIn(it) } ?: CheckInStatus.NotCheckedIn
        }
        .distinctUntilChanged()
  }
}
