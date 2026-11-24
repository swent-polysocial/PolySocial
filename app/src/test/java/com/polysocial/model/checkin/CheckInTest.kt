// Contributors: Claude (Opus 5.5, Anthropic) (the check-in window and venue tests, #467).
package com.polysocial.model.checkin

import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.location.straightLineDistanceMeters
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The window and the venue check of #374's decision (Architecture Diagram 3.10). */
class CheckInTest {
  private val start = Instant.parse("2026-11-27T19:00:00Z")
  private val end = Instant.parse("2026-11-27T23:00:00Z")
  private val venue = Coordinates(46.5184, 6.5680)

  private fun event(endTime: Instant?) =
      Event(
          title = "Concert",
          description = "",
          category = EventCategory.CULTURE,
          location = venue,
          startTime = start,
          endTime = endTime,
          isPrivate = false,
      )

  /** A point [meters] north of [venue], measured with the distance [isAtVenue] uses. */
  private fun northOfVenue(meters: Double): Coordinates {
    val point = Coordinates(venue.latitude + meters / 111_195.0, venue.longitude)
    assertEquals(meters, straightLineDistanceMeters(venue, point), 0.001)
    return point
  }

  @Test
  fun window_opens30MinutesBeforeTheStart_andClosesAtTheEnd() {
    assertEquals(
        CheckInWindow(opensAt = start - Duration.ofMinutes(30), closesAt = end),
        checkInWindow(event(endTime = end)),
    )
  }

  @Test
  fun window_withoutAnEndTime_closes3HoursAfterTheStart() {
    assertEquals(start + Duration.ofHours(3), checkInWindow(event(endTime = null)).closesAt)
  }

  @Test
  fun window_includesItsOpening_andExcludesItsClosing() {
    val window = checkInWindow(event(endTime = end))

    assertFalse(window.opensAt.minusNanos(1) in window)
    assertTrue(window.opensAt in window)
    assertTrue(start in window)
    assertTrue(end.minusNanos(1) in window)
    assertFalse(end in window)
  }

  @Test
  fun window_expires30DaysAfterItCloses() {
    assertEquals(end + Duration.ofDays(30), checkInWindow(event(endTime = end)).expiresAt)
    assertEquals(
        start + Duration.ofHours(3) + Duration.ofDays(30),
        checkInWindow(event(endTime = null)).expiresAt,
    )
  }

  @Test
  fun window_keepsSubMillisecondPrecision_likeTheStoredTimestamps() {
    // The rules compare expiresAt exactly, and Firestore stores microseconds.
    val preciseEnd = end.plusNanos(123_456_000)

    assertEquals(
        preciseEnd + Duration.ofDays(30),
        checkInWindow(event(endTime = preciseEnd)).expiresAt,
    )
  }

  @Test
  fun atVenue_within150Meters_withAccuracyUpTo100Meters() {
    assertTrue(isAtVenue(venue, accuracyMeters = 5.0, venue = venue))
    assertTrue(isAtVenue(northOfVenue(149.9), accuracyMeters = 100.0, venue = venue))
  }

  @Test
  fun atVenue_onlyUpTo150Meters() {
    assertTrue(isAtVenue(northOfVenue(149.995), accuracyMeters = 10.0, venue = venue))
    assertFalse(isAtVenue(northOfVenue(150.005), accuracyMeters = 10.0, venue = venue))
  }

  @Test
  fun atVenue_needsAnAccuracyOf100MetersOrBetter() {
    assertTrue(isAtVenue(venue, accuracyMeters = 100.0, venue = venue))
    assertFalse(isAtVenue(venue, accuracyMeters = 100.01, venue = venue))
  }

  @Test
  fun atVenue_isFalseForAnAccuracyThatIsNotAMeasurement() {
    assertTrue(isAtVenue(venue, accuracyMeters = 0.0, venue = venue))
    assertFalse(isAtVenue(venue, accuracyMeters = -1.0, venue = venue))
    assertFalse(isAtVenue(venue, accuracyMeters = Double.NaN, venue = venue))
    assertFalse(isAtVenue(venue, accuracyMeters = Double.POSITIVE_INFINITY, venue = venue))
  }

  @Test
  fun atVenue_isFalse_insteadOfThrowing_forInvalidCoordinates() {
    assertFalse(isAtVenue(Coordinates(91.0, 6.5680), accuracyMeters = 5.0, venue = venue))
    assertFalse(isAtVenue(venue, accuracyMeters = 5.0, venue = Coordinates(Double.NaN, 0.0)))
  }
}
