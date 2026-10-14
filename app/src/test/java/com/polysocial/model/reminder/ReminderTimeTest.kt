// Contributors: Claude Opus 5.5 (wrote these tests, #123).
package com.polysocial.model.reminder

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderTimeTest {

  private val now = Instant.parse("2026-10-03T12:00:00Z")
  private val zurich = ZoneId.of("Europe/Zurich")

  @Test
  fun aLaterEvent_isRemindedOneHourBeforeItStarts() {
    assertEquals(
        Instant.parse("2026-10-03T17:00:00Z"),
        reminderTime(Instant.parse("2026-10-03T18:00:00Z"), now),
    )
  }

  @Test
  fun anEventStartingWithinTheHour_getsNoReminder() {
    assertNull(reminderTime(now.plusSeconds(30 * 60), now))
  }

  @Test
  fun anEventStartingInExactlyOneHour_getsNoReminder() {
    assertNull(reminderTime(now.plusSeconds(60 * 60), now))
  }

  @Test
  fun anEventStartingJustOverAnHourAway_isRemindedInOneSecond() {
    assertEquals(now.plusSeconds(1), reminderTime(now.plusSeconds(60 * 60 + 1), now))
  }

  @Test
  fun anEventThatHasStarted_getsNoReminder() {
    assertNull(reminderTime(now.minusSeconds(60), now))
  }

  @Test
  fun theDay_isTodayTomorrowOrLaterInTheGivenZone() {
    val evening = Instant.parse("2026-10-03T21:30:00Z") // 23:30 in Zurich
    assertEquals(
        ReminderDay.TODAY,
        reminderDay(Instant.parse("2026-10-03T21:45:00Z"), evening, zurich),
    )
    // 00:30 in Zurich, while it's still the 3rd in UTC.
    assertEquals(
        ReminderDay.TOMORROW,
        reminderDay(Instant.parse("2026-10-03T22:30:00Z"), evening, zurich),
    )
    assertEquals(
        ReminderDay.TODAY,
        reminderDay(Instant.parse("2026-10-03T22:30:00Z"), evening, ZoneId.of("UTC")),
    )
    assertEquals(
        ReminderDay.LATER,
        reminderDay(Instant.parse("2026-10-05T10:00:00Z"), evening, zurich),
    )
  }
}
