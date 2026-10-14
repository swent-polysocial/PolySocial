// Contributors: Claude (the Events tab's search and filter selection, #115).
package com.polysocial.ui.event.list

import com.polysocial.model.event.DateRange
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventFilter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The days the Events tab's date chips keep. */
enum class DateChoice {
  ANY,
  TODAY,

  /** From today until Sunday included (Figma, #113). */
  THIS_WEEK,
}

/**
 * What the student searched for and picked on the Events tab. Unlike [EventFilter], the date is a
 * choice ("Today") rather than days, so it stays right when the date changes while the tab is open.
 */
data class EventSelection(
    val search: String = "",
    val categories: Set<EventCategory> = emptySet(),
    val date: DateChoice = DateChoice.ANY,
) {
  /** The filter for this selection, with the days counted from [today]. */
  fun toFilter(today: LocalDate): EventFilter =
      EventFilter(search = search, categories = categories, dates = date.toRange(today))
}

private fun DateChoice.toRange(today: LocalDate): DateRange? =
    when (this) {
      DateChoice.ANY -> null
      DateChoice.TODAY -> DateRange(today, today.plusDays(1))
      DateChoice.THIS_WEEK -> DateRange(today, today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)))
    }
