// Contributors: Claude (event filter and search, #114).
package com.polysocial.model.event

import java.text.Normalizer
import java.util.Locale

/**
 * What the student filters the loaded events by (#114). Every criterion is optional: the empty
 * filter keeps every event.
 *
 * @property search Text the event's title must contain, ignoring case and accents ("cafe" finds
 *   "Café"). Blank means no search.
 * @property categories The categories to keep; an event matches if it is in any of them. Empty
 *   means every category.
 */
data class EventFilter(
    val search: String = "",
    val categories: Set<EventCategory> = emptySet(),
) {
  /** True when the filter keeps every event. */
  val isEmpty: Boolean
    get() = search.isBlank() && categories.isEmpty()
}

/**
 * The [events] that match [filter], in their original order. Different criteria combine with AND,
 * and several categories with OR.
 *
 * It filters events that are already loaded (from `getUpcomingPublicEvents`), so it never queries
 * Firestore and works offline on cached events.
 */
fun filterEvents(events: List<Event>, filter: EventFilter): List<Event> {
  if (filter.isEmpty) return events
  val search = filter.search.trim().normalizedForSearch()
  return events.filter { event ->
    (search.isEmpty() || event.title.normalizedForSearch().contains(search)) &&
        (filter.categories.isEmpty() || event.category in filter.categories)
  }
}

/** Lower case without accents, so a search ignores both. */
private fun String.normalizedForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)

private val COMBINING_MARKS = Regex("\\p{Mn}+")
