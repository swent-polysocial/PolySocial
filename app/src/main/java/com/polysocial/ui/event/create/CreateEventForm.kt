// Contributors: Claude (drafted the Create Event form logic; picked place's address, #111; saving
// the place's name, #112); Mohamed Khellaf (reviewed). Claude (filling the form from an event to
// edit it, #131; the repository's errors, #131 review).
package com.polysocial.ui.event.create

import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventValidationError
import com.polysocial.model.event.MAX_LOCATION_NAME_LENGTH
import com.polysocial.model.event.validateNewEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A place picked on the map: its coordinates, the name the form shows, and its address line, which
 * the location picker shows again when it reopens on this place.
 *
 * @property isNamed false when [name] is only the placeholder for a pin whose address couldn't be
 *   looked up (for example offline), so the picker looks it up again when it reopens.
 */
data class PickedLocation(
    val coordinates: Coordinates,
    val name: String,
    val address: String = "",
    val isNamed: Boolean = true,
)

/** What the organizer has entered on the Create Event form so far. */
data class CreateEventForm(
    val title: String = "",
    val description: String = "",
    val category: EventCategory? = null,
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val location: PickedLocation? = null,
    val capacityText: String = "",
    val isPrivate: Boolean = true,
)

/** A problem the form shows next to a field. */
enum class CreateEventFormError {
  MISSING_TITLE,
  MISSING_CATEGORY,
  TITLE_TOO_LONG,
  DESCRIPTION_TOO_LONG,
  MISSING_DATE,
  START_IN_PAST,
  END_SAME_AS_START,
  MISSING_LOCATION,
  CAPACITY_NOT_A_NUMBER,
  CAPACITY_TOO_SMALL,
}

/** True when the end time is earlier than the start time, so the event ends the next day. */
val CreateEventForm.endsNextDay: Boolean
  get() = startTime != null && endTime != null && endTime < startTime

/**
 * Builds the [Event] to create, or returns null while a required field (category, date, start time,
 * location) is missing or the capacity isn't a number. Times are read in [zone], and an end time
 * earlier than the start time is the next day. The title and description are trimmed, and the
 * place's name is shortened to [MAX_LOCATION_NAME_LENGTH] characters (see [shortenPlaceName]).
 */
fun CreateEventForm.toEvent(zone: ZoneId): Event? {
  val kind = category ?: return null
  val start = startIn(zone) ?: return null
  val place = location ?: return null
  val capacity =
      parseCapacity(capacityText).getOrElse {
        return null
      }
  return Event(
      title = title.trim(),
      description = description.trim(),
      category = kind,
      location = place.coordinates,
      locationName = shortenPlaceName(place.name),
      startTime = start.toInstant(),
      endTime = endAfter(start, zone),
      capacity = capacity,
      isPrivate = isPrivate,
  )
}

/**
 * The form filled in with [event]'s values, to edit it. Times are shown in [zone]; an end on the
 * next day shows as an end time earlier than the start (see [endsNextDay]). The place keeps the
 * name it was saved with; without one, the location picker looks it up again when it reopens.
 */
fun Event.toForm(zone: ZoneId): CreateEventForm {
  val start = startTime.atZone(zone)
  return CreateEventForm(
      title = title,
      description = description,
      category = category,
      date = start.toLocalDate(),
      startTime = start.toLocalTime(),
      endTime = endTime?.atZone(zone)?.toLocalTime(),
      location = PickedLocation(location, locationName, isNamed = locationName.isNotBlank()),
      capacityText = capacity?.toString().orEmpty(),
      isPrivate = isPrivate,
  )
}

/**
 * Returns every problem with the form at [now]: missing required fields, a capacity that isn't a
 * whole number, and the rules of [validateNewEvent]. When editing an event, [keptStart] is its
 * stored start: a start in the past is then only an error if it differs from it, as
 * [com.polysocial.model.event.validateEventUpdate] decides, so an event that has started can still
 * be edited.
 */
fun CreateEventForm.errors(
    now: Instant,
    zone: ZoneId,
    keptStart: Instant? = null,
): Set<CreateEventFormError> {
  val errors = mutableSetOf<CreateEventFormError>()
  if (date == null || startTime == null) errors += CreateEventFormError.MISSING_DATE
  if (location == null) errors += CreateEventFormError.MISSING_LOCATION
  if (parseCapacity(capacityText).isFailure) errors += CreateEventFormError.CAPACITY_NOT_A_NUMBER
  if (title.isBlank()) errors += CreateEventFormError.MISSING_TITLE
  if (category == null) errors += CreateEventFormError.MISSING_CATEGORY
  val event = toEvent(zone) ?: placeholderFor(now, zone)
  errors += validateNewEvent(event, now).map { it.toFormError() }
  if (keptStart != null && startIn(zone)?.toInstant() == keptStart) {
    errors -= CreateEventFormError.START_IN_PAST
  }
  return errors
}

/** The start in [zone], or null until both the date and the start time are picked. */
private fun CreateEventForm.startIn(zone: ZoneId): ZonedDateTime? = date?.let { day ->
  startTime?.let { day.atTime(it).atZone(zone) }
}

/** The end of an event starting at [start]: the next day if it is earlier than the start. */
private fun CreateEventForm.endAfter(start: ZonedDateTime, zone: ZoneId): Instant? = endTime?.let {
  val endDate = if (endsNextDay) start.toLocalDate().plusDays(1) else start.toLocalDate()
  endDate.atTime(it).atZone(zone).toInstant()
}

/**
 * [name] trimmed and cut to at most [MAX_LOCATION_NAME_LENGTH] characters. The cut never splits an
 * emoji (a surrogate pair, which Firestore would store as "?") and never leaves a trailing space.
 */
private fun shortenPlaceName(name: String): String {
  val cut = name.trim().take(MAX_LOCATION_NAME_LENGTH)
  val whole = if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
  return whole.trimEnd()
}

/** Empty means no limit; anything else must be a whole number (the minimum is a model rule). */
private fun parseCapacity(text: String): Result<Int?> {
  val trimmed = text.trim()
  if (trimmed.isEmpty()) return Result.success(null)
  return trimmed.toIntOrNull()?.let { Result.success(it) }
      ?: Result.failure(NumberFormatException(trimmed))
}

/**
 * The form as an event that fills in what is still missing (category, location, and a start at
 * [now] until the date and start time are picked), so the rules of the fields already filled in,
 * such as a past start, are checked before the rest of the form.
 */
private fun CreateEventForm.placeholderFor(now: Instant, zone: ZoneId): Event {
  val start = startIn(zone)
  return Event(
      title = title.trim(),
      description = description.trim(),
      category = EventCategory.OTHER,
      location = Coordinates(0.0, 0.0),
      startTime = start?.toInstant() ?: now,
      endTime = start?.let { endAfter(it, zone) },
      capacity = parseCapacity(capacityText).getOrNull(),
      isPrivate = isPrivate,
  )
}

/** The form error to show for a rule the repository refused an event for. */
internal fun EventValidationError.toFormError() =
    when (this) {
      EventValidationError.BLANK_TITLE -> CreateEventFormError.MISSING_TITLE
      EventValidationError.TITLE_TOO_LONG -> CreateEventFormError.TITLE_TOO_LONG
      EventValidationError.DESCRIPTION_TOO_LONG -> CreateEventFormError.DESCRIPTION_TOO_LONG
      EventValidationError.START_IN_PAST -> CreateEventFormError.START_IN_PAST
      EventValidationError.END_NOT_AFTER_START -> CreateEventFormError.END_SAME_AS_START
      EventValidationError.CAPACITY_TOO_SMALL -> CreateEventFormError.CAPACITY_TOO_SMALL
    }
