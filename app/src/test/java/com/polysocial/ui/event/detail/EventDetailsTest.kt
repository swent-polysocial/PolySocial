// Contributors: Claude (wrote these tests of the event detail's local times, #112).
package com.polysocial.ui.event.detail

import com.polysocial.model.event.validEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventDetailsTest {
  private val zurich = ZoneId.of("Europe/Zurich")

  private fun details(start: String, end: String?) =
      eventDetails(
          validEvent(startTime = Instant.parse(start), endTime = end?.let(Instant::parse)),
          zurich,
      )

  @Test
  fun theDateAndStartAreReadInTheZone() {
    // 23:30 UTC on 9 October is 01:30 on 10 October in Lausanne (summer time).
    val details = details("2026-10-09T23:30:00Z", "2026-10-10T01:00:00Z")

    assertEquals(LocalDate.of(2026, 10, 10), details.date)
    assertEquals(LocalTime.of(1, 30), details.startTime)
    assertEquals(LocalTime.of(3, 0), details.endTime)
    assertEquals(0, details.endDayOffset)
  }

  @Test
  fun anEndOnTheSameDay_hasNoOffset() {
    val details = details("2026-10-10T12:00:00Z", "2026-10-10T14:00:00Z")

    assertEquals(LocalTime.of(14, 0), details.startTime)
    assertEquals(LocalTime.of(16, 0), details.endTime)
    assertEquals(0, details.endDayOffset)
  }

  @Test
  fun anOvernightEnd_isOneDayLater() {
    // 22:00 to 02:00 in Lausanne.
    val details = details("2026-10-10T20:00:00Z", "2026-10-11T00:00:00Z")

    assertEquals(LocalDate.of(2026, 10, 10), details.date)
    assertEquals(LocalTime.of(22, 0), details.startTime)
    assertEquals(LocalTime.of(2, 0), details.endTime)
    assertEquals(1, details.endDayOffset)
  }

  @Test
  fun aShortEventAcrossLocalMidnight_isOneDayLaterEvenIfUtcStaysOnTheSameDay() {
    // 23:30 to 00:30 in Lausanne, but 21:30 to 22:30 UTC.
    val details = details("2026-10-10T21:30:00Z", "2026-10-10T22:30:00Z")

    assertEquals(LocalTime.of(0, 30), details.endTime)
    assertEquals(1, details.endDayOffset)
  }

  @Test
  fun anEventAcrossUtcMidnightOnly_isTheSameLocalDay() {
    // 01:00 to 03:00 in Lausanne on 11 October, but it starts on 10 October in UTC.
    val details = details("2026-10-10T23:00:00Z", "2026-10-11T01:00:00Z")

    assertEquals(LocalDate.of(2026, 10, 11), details.date)
    assertEquals(0, details.endDayOffset)
  }

  @Test
  fun noEndTime_hasNoEndAndNoOffset() {
    val details = details("2026-10-10T20:00:00Z", null)

    assertNull(details.endTime)
    assertEquals(0, details.endDayOffset)
  }

  @Test
  fun anEndTwoDaysLater_isTwoDaysLater() {
    val details = details("2026-10-10T08:00:00Z", "2026-10-12T08:00:00Z")

    assertEquals(2, details.endDayOffset)
  }

  @Test
  fun theEndOfSummerTime_keepsTheLocalTimes() {
    // Sunday 25 October 2026, clocks go back from 03:00 to 02:00. 22:00 summer time on Saturday
    // to 04:00 winter time on Sunday is 7 hours.
    val details = details("2026-10-24T20:00:00Z", "2026-10-25T03:00:00Z")

    assertEquals(LocalDate.of(2026, 10, 24), details.date)
    assertEquals(LocalTime.of(22, 0), details.startTime)
    assertEquals(LocalTime.of(4, 0), details.endTime)
    assertEquals(1, details.endDayOffset)
  }

  @Test
  fun anEventDuringTheChangeOfTime_isTheSameDay() {
    // 01:00 summer time to 05:00 winter time on Sunday 25 October.
    val details = details("2026-10-24T23:00:00Z", "2026-10-25T04:00:00Z")

    assertEquals(LocalDate.of(2026, 10, 25), details.date)
    assertEquals(LocalTime.of(1, 0), details.startTime)
    assertEquals(LocalTime.of(5, 0), details.endTime)
    assertEquals(0, details.endDayOffset)
  }

  @Test
  fun theStartOfSummerTime_keepsTheLocalTimes() {
    // Sunday 29 March 2026, clocks go forward from 02:00 to 03:00: 01:30 to 03:30 is one hour.
    val details = details("2026-03-29T00:30:00Z", "2026-03-29T01:30:00Z")

    assertEquals(LocalTime.of(1, 30), details.startTime)
    assertEquals(LocalTime.of(3, 30), details.endTime)
    assertEquals(0, details.endDayOffset)
  }
}
