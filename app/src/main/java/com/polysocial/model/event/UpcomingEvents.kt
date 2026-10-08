// Contributors: Claude (drafted the upcoming-events window, #49).
package com.polysocial.model.event

import java.time.Duration
import java.time.Instant

/** The end of the upcoming window: [windowDays] days after [now], excluded. */
fun upcomingWindowEnd(now: Instant, windowDays: Int): Instant =
    now.plus(Duration.ofDays(windowDays.toLong()))

/**
 * True if this event belongs on the map: it is public and starts between [now] (included) and
 * [upcomingWindowEnd] (excluded). An event that has already started is not upcoming.
 *
 * `FirestoreEventRepository` asks Firestore for the same events with a query, so both must keep
 * these bounds.
 */
fun Event.isUpcomingPublicEvent(now: Instant, windowDays: Int): Boolean =
    !isPrivate && !startTime.isBefore(now) && startTime.isBefore(upcomingWindowEnd(now, windowDays))
