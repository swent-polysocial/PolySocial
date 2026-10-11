// Contributors: Claude (day sections of the events list, #105).
package com.polysocial.ui.event.list

import com.polysocial.model.event.Event
import java.time.Clock
import java.time.LocalDate

/** The heading of a day section in the events list: today, tomorrow, or a later date. */
sealed interface EventDay {
  data object Today : EventDay

  data object Tomorrow : EventDay

  /** Any other day, shown as its date. */
  data class On(val date: LocalDate) : EventDay
}

/** The events that start on [day], in start-time order. */
data class EventDaySection(val day: EventDay, val events: List<Event>)

/**
 * Groups [events] by the day they start on, in the time zone of [clock], with the days in order and
 * each day's events sorted by start time (events starting at the same time keep their order). A day
 * is [EventDay.Today] or [EventDay.Tomorrow] relative to the current date of [clock], otherwise its
 * date.
 */
fun groupEventsByDay(events: List<Event>, clock: Clock): List<EventDaySection> {
  val today = LocalDate.now(clock)
  return events
      .sortedBy { it.startTime }
      .groupBy { it.startTime.atZone(clock.zone).toLocalDate() }
      .map { (date, eventsThatDay) ->
        val day =
            when (date) {
              today -> EventDay.Today
              today.plusDays(1) -> EventDay.Tomorrow
              else -> EventDay.On(date)
            }
        EventDaySection(day, eventsThatDay)
      }
}
