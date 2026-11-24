// Contributors: Claude (Opus 5.5, Anthropic) (the check-in repository contract, #467; the entrance
// code, #468).
package com.polysocial.model.checkin

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Outcome of [CheckInRepository.checkIn]. Only [CheckedIn] means anything is stored. */
sealed interface CheckInResult {
  /**
   * The student is checked in on the server, now or already before. Checking in twice keeps the
   * first check-in and its time, also once the window has closed.
   */
  data object CheckedIn : CheckInResult

  /** No user is signed in, for example after signing out on another screen. Nothing was written. */
  data object NotSignedIn : CheckInResult

  /**
   * There is no event the student can read under this ID: it never existed, it was deleted, or it
   * is private and the student isn't allowed in. These look the same on purpose, like
   * `RegisterResult.NotFound`. Nothing was written.
   */
  data object NotFound : CheckInResult

  /** The student isn't registered for the event ("I'm going"). Nothing was written. */
  data object NotRegistered : CheckInResult

  /**
   * The device's time is outside the event's [checkInWindow]: too early or too late. Nothing was
   * written.
   */
  data object OutsideWindow : CheckInResult

  /**
   * The device is offline. Nothing was written or queued: a queued check-in could reach the server
   * after the window and be refused without the student knowing, so they try again instead.
   */
  data object NetworkError : CheckInResult

  /**
   * Any other failure. It includes a check-in the Security Rules refuse although the app's own
   * checks passed, for example when the device's clock is wrong: the rules use the server's time.
   * Nothing was written.
   */
  data object UnexpectedError : CheckInResult
}

/** What [CheckInRepository.observeCheckIn] emits. It never says where or how the student was. */
sealed interface CheckInStatus {
  /** The student checked in at [checkedInAt], the server's time. */
  data class CheckedIn(val checkedInAt: Instant) : CheckInStatus

  /** The student hasn't checked in for this event. */
  data object NotCheckedIn : CheckInStatus

  /**
   * The check-in can't be read: nobody is signed in, an ID is invalid, the Security Rules refuse it
   * (a student who isn't, or is no longer, in the caller's group for the event) or the read failed.
   */
  data object Error : CheckInStatus
}

/** What [CheckInRepository.observeEntranceCode] emits. */
sealed interface EntranceCodeStatus {
  /**
   * The event's current [code]. [fromCache] when it comes from the offline cache: it may then be
   * out of date, so the organizer's screen doesn't show it.
   */
  data class Code(val code: EntranceCode, val fromCache: Boolean) : EntranceCodeStatus

  /** The event has no entrance code yet: the first organizer to open the screen creates it. */
  data object Missing : EntranceCodeStatus

  /**
   * The code can't be read: nobody is signed in, an invalid ID, the Security Rules refuse it (not
   * an organizer, or outside the window) or the read failed.
   */
  data object Error : EntranceCodeStatus
}

/** Outcome of [CheckInRepository.rotateEntranceCode]. */
sealed interface RotateCodeResult {
  /**
   * The event has a code newer than the one replaced: this call wrote it, or another organizer's
   * phone did first, in which case nothing was written. [CheckInRepository.observeEntranceCode]
   * shows it.
   */
  data object Done : RotateCodeResult

  /** The device is offline. Nothing was written or queued. */
  data object NetworkError : RotateCodeResult

  /**
   * Any other failure. Nothing was written. It includes a rotation the Security Rules refuse: not
   * an organizer, outside the window, or less than 50 s after the last one.
   */
  data object UnexpectedError : RotateCodeResult
}

/**
 * Stores check-ins at an event's venue in `events/{eventId}/checkIns/{uid}` (#374, Architecture
 * Diagram 3.10): one per registered student, with the server's time and how they checked in, and
 * never a location. The Security Rules enforce who checks in and when, and delete nothing
 * themselves: a Firestore TTL policy deletes each check-in at its window's
 * [CheckInWindow.expiresAt], and cancelling the registration deletes it at once.
 */
interface CheckInRepository {
  /**
   * Checks the signed-in student in at the event [eventId], with their location: the caller has
   * already found them at the venue with [isAtVenue], which the rules can't check.
   *
   * The checks run in this order, and each one stops before anything is written: an ID that can't
   * be a document ID is [CheckInResult.NotFound], a missing user [CheckInResult.NotSignedIn], and
   * an offline device [CheckInResult.NetworkError]. Then the event and the student's check-in are
   * read: a missing or unreadable event is [CheckInResult.NotFound], an existing check-in
   * [CheckInResult.CheckedIn] without writing again, a missing registration
   * [CheckInResult.NotRegistered], and a time outside [checkInWindow]
   * [CheckInResult.OutsideWindow]. Only then is the check-in written.
   */
  suspend fun checkIn(eventId: String): CheckInResult

  /**
   * Observes the check-in of the student [uid] at the event [eventId]: the signed-in student's own,
   * or a member of their group for that event, for "3 of 4 checked in".
   *
   * The flow emits on every change, including from the offline cache while the device is offline. A
   * slow collector gets only the latest status. It ends after a [CheckInStatus.Error], which is
   * always delivered first. Cancelling the collection stops listening.
   */
  fun observeCheckIn(eventId: String, uid: String): Flow<CheckInStatus>

  /**
   * Observes the entrance code of the event [eventId] (#468), which only its organizers may read,
   * during the check-in window. Every organizer's phone shows this stored code, so they all show
   * the same one.
   *
   * The flow emits on every change, including from the offline cache. A slow collector gets only
   * the latest status. It ends after an [EntranceCodeStatus.Error], which is always delivered
   * first. Cancelling the collection stops listening.
   */
  fun observeEntranceCode(eventId: String): Flow<EntranceCodeStatus>

  /**
   * Replaces the entrance code of the event [eventId] whose token is [replacing], or creates the
   * first one when [replacing] is null, with a new random token ([newEntranceToken]); the replaced
   * token stays accepted as the previous one.
   *
   * It runs in one transaction that reads the code again and writes only if its token is still
   * [replacing] (or, for null, if there is none yet), so when two organizers' phones try at once,
   * only one writes and the other returns [RotateCodeResult.Done] too. An offline device returns
   * [RotateCodeResult.NetworkError] before anything is read or written.
   */
  suspend fun rotateEntranceCode(eventId: String, replacing: String?): RotateCodeResult
}
