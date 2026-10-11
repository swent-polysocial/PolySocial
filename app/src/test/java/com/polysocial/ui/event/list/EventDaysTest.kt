// Contributors: Claude (wrote these tests, #105).
package com.polysocial.ui.event.list

import com.polysocial.model.event.validEvent
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class EventDaysTest {
  private val lausanne = ZoneId.of("Europe/Zurich")

  /** Sunday 11 October 2026, 10:00 in Lausanne. */
  private val clock = clockAt(LocalDateTime.of(2026, 10, 11, 10, 0), lausanne)

  private fun clockAt(localTime: LocalDateTime, zone: ZoneId): Clock =
      Clock.fixed(localTime.atZone(zone).toInstant(), zone)

  private fun startingAt(title: String, localTime: LocalDateTime, zone: ZoneId = lausanne) =
      validEvent(title = title, startTime = localTime.atZone(zone).toInstant())

  private fun List<EventDaySection>.titles() = map { section -> section.events.map { it.title } }

  @Test
  fun noEvents_giveNoSections() {
    assertEquals(emptyList<EventDaySection>(), groupEventsByDay(emptyList(), clock))
  }

  @Test
  fun events_areGroupedByDayInOrder_andSortedWithinEachDay() {
    val events =
        listOf(
            startingAt("Party", LocalDateTime.of(2026, 10, 11, 20, 0)),
            startingAt("Concert", LocalDateTime.of(2026, 10, 14, 18, 0)),
            startingAt("Lunch", LocalDateTime.of(2026, 10, 11, 12, 30)),
            startingAt("Climbing", LocalDateTime.of(2026, 10, 12, 9, 0)),
        )

    val sections = groupEventsByDay(events, clock)

    assertEquals(
        listOf(EventDay.Today, EventDay.Tomorrow, EventDay.On(LocalDate.of(2026, 10, 14))),
        sections.map { it.day },
    )
    assertEquals(
        listOf(listOf("Lunch", "Party"), listOf("Climbing"), listOf("Concert")),
        sections.titles(),
    )
  }

  @Test
  fun midnight_startsTheNextDay() {
    val events =
        listOf(
            startingAt("Late", LocalDateTime.of(2026, 10, 11, 23, 59)),
            startingAt("Midnight", LocalDateTime.of(2026, 10, 12, 0, 0)),
        )

    val sections = groupEventsByDay(events, clock)

    assertEquals(listOf(EventDay.Today, EventDay.Tomorrow), sections.map { it.day })
    assertEquals(listOf(listOf("Late"), listOf("Midnight")), sections.titles())
  }

  @Test
  fun theDay_isTakenInTheClocksTimeZone() {
    // 22:30 UTC on the 11th is already 00:30 on the 12th in Lausanne.
    val event = startingAt("Night", LocalDateTime.of(2026, 10, 11, 22, 30), ZoneOffset.UTC)
    val utcClock = clockAt(LocalDateTime.of(2026, 10, 11, 8, 0), ZoneOffset.UTC)

    assertEquals(EventDay.Tomorrow, groupEventsByDay(listOf(event), clock).single().day)
    assertEquals(EventDay.Today, groupEventsByDay(listOf(event), utcClock).single().day)
  }

  @Test
  fun tomorrow_followsTheCalendarAcrossMonths() {
    val endOfMonth = clockAt(LocalDateTime.of(2026, 10, 31, 18, 0), lausanne)
    val events =
        listOf(
            startingAt("Halloween", LocalDateTime.of(2026, 10, 31, 21, 0)),
            startingAt("Brunch", LocalDateTime.of(2026, 11, 1, 11, 0)),
            startingAt("Lecture", LocalDateTime.of(2026, 11, 2, 8, 15)),
        )

    assertEquals(
        listOf(EventDay.Today, EventDay.Tomorrow, EventDay.On(LocalDate.of(2026, 11, 2))),
        groupEventsByDay(events, endOfMonth).map { it.day },
    )
  }

  @Test
  fun eventsStartingAtTheSameTime_keepTheirOrder() {
    val time = LocalDateTime.of(2026, 10, 11, 18, 0)
    val events = listOf(startingAt("First", time), startingAt("Second", time))

    assertEquals(listOf(listOf("First", "Second")), groupEventsByDay(events, clock).titles())
  }
}
