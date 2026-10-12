// Contributors: Claude (day sections of the events list, #105).
package com.polysocial.ui.event.list

import com.polysocial.model.event.Event
import java.time.Clock
import java.time.LocalDate

/** The events that start on [date], in start-time order. */
data class EventDaySection(val date: LocalDate, val events: List<Event>)

/**
 * Groups [events] by the date they start on, in the time zone of [clock], with the dates in order
 * and each date's events sorted by start time (events starting at the same time keep their order).
 */
fun groupEventsByDay(events: List<Event>, clock: Clock): List<EventDaySection> =
    events
        .sortedBy { it.startTime }
        .groupBy { it.startTime.atZone(clock.zone).toLocalDate() }
        .map { (date, eventsThatDay) -> EventDaySection(date, eventsThatDay) }
