// Contributors: Claude (Opus 5.5, Anthropic) (wrote this fake for tests, #467; the entrance code,
// #468).
package com.polysocial.model.checkin

import com.polysocial.model.event.Event
import com.polysocial.model.event.isReadableEventId
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
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
 * Likewise, any signed-in user reads and rotates entrance codes: set [codeReadFailure] or
 * [rotateFailure] for what the rules refuse. A rotation stamps the code with [now].
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

  /** The stored entrance codes, by event ID. */
  private val codes = MutableStateFlow<Map<String, EntranceCode>>(emptyMap())

  /** When true, [observeEntranceCode] emits [EntranceCodeStatus.Error], like a refused read. */
  var codeReadFailure = false

  /** The `fromCache` flag [observeEntranceCode] reports, to simulate being offline. */
  var codeFromCache = false

  /** When set, [rotateEntranceCode] returns this instead of rotating, like a failed write. */
  var rotateFailure: RotateCodeResult? = null

  /** When set, [rotateEntranceCode] waits for it before rotating, like a slow connection. */
  var rotateGate: CompletableDeferred<Unit>? = null

  /** The `replacing` tokens [rotateEntranceCode] was called with, in call order. */
  val rotations = mutableListOf<String?>()

  /** Stores [code] as the entrance code of [eventId], like another organizer's phone. */
  fun seedCode(eventId: String, code: EntranceCode) {
    codes.value += eventId to code
  }

  /** The entrance code of [eventId], or null if it has none. */
  fun codeOf(eventId: String): EntranceCode? = codes.value[eventId]

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

  override fun observeEntranceCode(eventId: String): Flow<EntranceCodeStatus> {
    if (currentUid == null || !isReadableEventId(eventId) || codeReadFailure) {
      return flowOf(EntranceCodeStatus.Error)
    }
    return codes
        .map { stored ->
          stored[eventId]?.let { EntranceCodeStatus.Code(it, codeFromCache) }
              ?: EntranceCodeStatus.Missing
        }
        .distinctUntilChanged()
  }

  override suspend fun rotateEntranceCode(eventId: String, replacing: String?): RotateCodeResult {
    if (!isReadableEventId(eventId) || currentUid == null) return RotateCodeResult.UnexpectedError
    rotations += replacing
    rotateFailure?.let {
      return it
    }
    rotateGate?.await()
    val stored = codeOf(eventId)
    // Someone else rotated (or created) it first: nothing to write.
    if (stored?.token != replacing) return RotateCodeResult.Done
    seedCode(eventId, EntranceCode(newEntranceToken(), replacing, now))
    return RotateCodeResult.Done
  }
}
