// Contributors: Claude (wrote these test fixtures); Mohamed Khellaf (reviewed).
package com.polysocial.model.event

import java.time.Instant

/** The "current time" used by the event tests. */
val TEST_NOW: Instant = Instant.parse("2026-01-01T12:00:00Z")

/** A valid event that starts one day after [TEST_NOW]. Tests change only the fields they check. */
fun validEvent(
    title: String = "Study session",
    startTime: Instant = TEST_NOW.plusSeconds(24 * 3600),
    endTime: Instant? = startTime.plusSeconds(2 * 3600),
    capacity: Int? = 8,
) =
    Event(
        title = title,
        description = "Going through the exercise sheet together.",
        category = EventCategory.STUDY,
        location = Coordinates(latitude = 46.5191, longitude = 6.5668),
        startTime = startTime,
        endTime = endTime,
        capacity = capacity,
        isPrivate = false,
    )
