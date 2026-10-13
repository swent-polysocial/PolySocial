// Contributors: Claude (event filter and search, #114).
package com.polysocial.model.event

import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What the student filters the loaded events by (#114). Every criterion is optional: the empty
 * filter keeps every event.
 *
 * @property search Text the event's title must contain, ignoring case and accents ("cafe" finds
 *   "Café"). Blank means no search.
 * @property categories The categories to keep; an event matches if it is in any of them. Empty
 *   means every category.
 * @property dates The days the event must start on, or null for any day.
 */
data class EventFilter(
    val search: String = "",
    val categories: Set<EventCategory> = emptySet(),
    val dates: DateRange? = null,
) {
  /** True when the filter keeps every event. */
  val isEmpty: Boolean
    get() = search.isBlank() && categories.isEmpty() && dates == null
}

/**
 * The days from [start] (included) to [endExclusive] (excluded), so one day is `DateRange(day,
 * day.plusDays(1))`. The Events tab builds it from its date chips (Today, This week) or a picked
 * day (#115).
 */
data class DateRange(val start: LocalDate, val endExclusive: LocalDate) {
  init {
    require(start < endExclusive) { "A date range must hold at least one day" }
  }

  operator fun contains(date: LocalDate): Boolean = date >= start && date < endExclusive
}

/**
 * The [events] that match [filter], in their original order. Different criteria combine with AND,
 * and several categories with OR. An event's day is the day it starts on in [zone], the device's
 * time zone (from the injected `Clock`).
 *
 * It filters events that are already loaded (from `getUpcomingPublicEvents`), so it never queries
 * Firestore and works offline on cached events.
 */
fun filterEvents(events: List<Event>, filter: EventFilter, zone: ZoneId): List<Event> {
  if (filter.isEmpty) return events
  val search = filter.search.trim().normalizedForSearch()
  return events.filter { event ->
    (search.isEmpty() || event.title.normalizedForSearch().contains(search)) &&
        (filter.categories.isEmpty() || event.category in filter.categories) &&
        (filter.dates == null || event.startTime.atZone(zone).toLocalDate() in filter.dates)
  }
}

/** Lower case without accents, so a search ignores both. */
private fun String.normalizedForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)

private val COMBINING_MARKS = Regex("\\p{Mn}+")
