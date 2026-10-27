// Contributors: Claude (an association's upcoming and past events, #132).
package com.polysocial.model.event

import java.time.Instant

/**
 * An organizer's events split as on the association's Events tab: [upcoming] in start order, the
 * next one first, and [past] in reverse start order, the latest one first.
 */
data class UpcomingAndPast(val upcoming: List<Event>, val past: List<Event>)

/**
 * True once this event is over at [now]: its end time has passed, or, without an end time, its
 * start time has. An event that has started but not ended is still upcoming (Figma's "Live").
 */
fun Event.isPast(now: Instant): Boolean = (endTime ?: startTime).isBefore(now)

/** True while this event runs at [now]: it has started and is not [isPast]. */
fun Event.isLive(now: Instant): Boolean = !startTime.isAfter(now) && !isPast(now)

/** Splits [events] into upcoming and past ones at [now] (see [isPast]), each in its tab's order. */
fun splitUpcomingAndPast(events: List<Event>, now: Instant): UpcomingAndPast {
  val (past, upcoming) = events.partition { it.isPast(now) }
  return UpcomingAndPast(
      upcoming = upcoming.sortedBy { it.startTime },
      past = past.sortedByDescending { it.startTime },
  )
}
