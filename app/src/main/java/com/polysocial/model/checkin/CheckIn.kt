// Contributors: Claude (Opus 5.5, Anthropic) (the check-in window and venue check, #467).
package com.polysocial.model.checkin

import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.location.straightLineDistanceMeters
import java.time.Duration
import java.time.Instant

/** How long before the event's start check-in opens (#374). */
val CHECK_IN_OPENS_BEFORE_START: Duration = Duration.ofMinutes(30)

/** How long after its start check-in closes for an event without an end time (#374). */
val CHECK_IN_LENGTH_WITHOUT_END: Duration = Duration.ofHours(3)

/** How long a check-in is kept after its window closes, before Firestore deletes it (#374). */
val CHECK_IN_KEPT_AFTER_WINDOW: Duration = Duration.ofDays(30)

/** The largest distance from the event's location, in meters, at which a fix is at the venue. */
const val CHECK_IN_RADIUS_METERS = 150.0

/** The worst accuracy, in meters, that a fix may report and still check in. */
const val CHECK_IN_MAX_ACCURACY_METERS = 100.0

/**
 * When a registered student can check in: from [opensAt] (included) to [closesAt] (excluded). The
 * Security Rules check the same window against the server's time, so the app's answer is only a
 * preview of theirs.
 */
data class CheckInWindow(val opensAt: Instant, val closesAt: Instant) {
  /** Whether [time] is inside the window. */
  operator fun contains(time: Instant): Boolean = !time.isBefore(opensAt) && time.isBefore(closesAt)

  /**
   * When the check-ins of this window are deleted: [CHECK_IN_KEPT_AFTER_WINDOW] after it closes. A
   * check-in stores it as `expiresAt`, which the rules require to be exactly this value and a
   * Firestore TTL policy uses to delete the check-in.
   */
  val expiresAt: Instant
    get() = closesAt + CHECK_IN_KEPT_AFTER_WINDOW
}

/**
 * The check-in window of [event] (#374): it opens [CHECK_IN_OPENS_BEFORE_START] before the start
 * and closes at the end time, or [CHECK_IN_LENGTH_WITHOUT_END] after the start for an event without
 * one. It keeps the times' full precision, since the rules compare `expiresAt` exactly.
 *
 * Registering is not affected: it still closes when the event is past (`Event.isPast`).
 */
fun checkInWindow(event: Event): CheckInWindow =
    CheckInWindow(
        opensAt = event.startTime - CHECK_IN_OPENS_BEFORE_START,
        closesAt = event.endTime ?: (event.startTime + CHECK_IN_LENGTH_WITHOUT_END),
    )

/**
 * Whether one location fix shows the student at the venue (#374): the fix is at most
 * [CHECK_IN_RADIUS_METERS] from [venue], in straight line, and its reported accuracy is at most
 * [CHECK_IN_MAX_ACCURACY_METERS], so a vague fix can't pass by luck.
 *
 * The comparison happens only on the device: neither the fix nor the distance is stored or sent. An
 * accuracy that isn't a measurement (negative, infinite or NaN) and invalid coordinates, which an
 * event read from Firestore could have, are not at the venue instead of throwing, so the student
 * gets the entrance QR code instead.
 *
 * @param accuracyMeters the fix's reported horizontal accuracy (the radius of 68% confidence).
 */
fun isAtVenue(fix: Coordinates, accuracyMeters: Double, venue: Coordinates): Boolean {
  if (!(accuracyMeters >= 0.0 && accuracyMeters <= CHECK_IN_MAX_ACCURACY_METERS)) return false
  val distance =
      try {
        straightLineDistanceMeters(fix, venue)
      } catch (e: IllegalArgumentException) {
        return false
      }
  return distance <= CHECK_IN_RADIUS_METERS
}
