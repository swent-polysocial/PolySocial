// Contributors: Claude (drafted the event validation rules); Mohamed Khellaf (reviewed). Claude
// (validating an update, #130); Franciszek Najda (reviewed).
package com.polysocial.model.event

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Smallest allowed [Event.capacity]: an event is meant for a group, so at least two people. */
const val MIN_CAPACITY = 2

/** Longest allowed [Event.title], in line with event platforms (Meetup 80, Eventbrite 75). */
const val MAX_TITLE_LENGTH = 80

/** Longest allowed [Event.description], the same as Meetup's limit. */
const val MAX_DESCRIPTION_LENGTH = 5000

/**
 * Longest stored [Event.locationName]. The name comes from the location picker, not from typing, so
 * the form shortens a longer one instead of showing an error.
 */
const val MAX_LOCATION_NAME_LENGTH = 100

/** A rule that a new [Event] breaks. The Create Event form shows one message per error. */
enum class EventValidationError {
  BLANK_TITLE,
  TITLE_TOO_LONG,
  DESCRIPTION_TOO_LONG,
  START_IN_PAST,
  END_NOT_AFTER_START,
  CAPACITY_TOO_SMALL,
}

/**
 * Returns every rule that [event] breaks, or an empty list if it can be created.
 *
 * An event needs a non-blank title of at most [MAX_TITLE_LENGTH] characters, and a start time that
 * is not before the current minute, so a start the form picked for this minute still passes when
 * the repository checks it again a moment later. The description may be blank but is at most
 * [MAX_DESCRIPTION_LENGTH] characters. If the event has an end time, it must be after the start
 * time, and if it has a capacity, it must allow at least [MIN_CAPACITY] people. [now] is a
 * parameter so that callers decide the clock and tests stay deterministic.
 */
fun validateNewEvent(event: Event, now: Instant): List<EventValidationError> = buildList {
  if (event.title.isBlank()) add(EventValidationError.BLANK_TITLE)
  if (event.title.length > MAX_TITLE_LENGTH) add(EventValidationError.TITLE_TOO_LONG)
  if (event.description.length > MAX_DESCRIPTION_LENGTH) {
    add(EventValidationError.DESCRIPTION_TOO_LONG)
  }
  if (event.startTime.isBefore(now.truncatedTo(ChronoUnit.MINUTES))) {
    add(EventValidationError.START_IN_PAST)
  }
  if (event.endTime != null && !event.endTime.isAfter(event.startTime)) {
    add(EventValidationError.END_NOT_AFTER_START)
  }
  if (event.capacity != null && event.capacity < MIN_CAPACITY) {
    add(EventValidationError.CAPACITY_TOO_SMALL)
  }
}

/**
 * Returns every rule that [event], the new values of the stored event [stored], breaks, or an empty
 * list if the update can be saved. The rules are [validateNewEvent]'s, except that a start time in
 * the past is accepted when it is [stored]'s: an organizer can still fix a typo in an event that
 * has already started, but can't move an event into the past.
 */
fun validateEventUpdate(event: Event, stored: Event, now: Instant): List<EventValidationError> {
  val errors = validateNewEvent(event, now)
  return if (event.startTime == stored.startTime) {
    errors - EventValidationError.START_IN_PAST
  } else {
    errors
  }
}
