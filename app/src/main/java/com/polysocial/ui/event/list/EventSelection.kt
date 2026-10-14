// Contributors: Claude (the Events tab's search and filter selection, a picked day and a distance,
// #115).
package com.polysocial.ui.event.list

import com.polysocial.model.event.DateRange
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventFilter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The days the Events tab's date filter keeps. */
sealed interface DateChoice {
  data object Any : DateChoice

  data object Today : DateChoice

  /** From today until Sunday included (Figma, #113). */
  data object ThisWeek : DateChoice

  /** One day, picked among the next 14 days in the Filters sheet. */
  data class Day(val date: LocalDate) : DateChoice
}

/**
 * What the student searched for and picked on the Events tab. Unlike [EventFilter], the date is a
 * choice ("Today") rather than days, so it stays right when the date changes while the tab is open.
 */
data class EventSelection(
    val search: String = "",
    val categories: Set<EventCategory> = emptySet(),
    val date: DateChoice = DateChoice.Any,
    val maxDistanceMeters: Int? = null,
) {
  /** The filter for this selection, with the days counted from [today]. */
  fun toFilter(today: LocalDate): EventFilter =
      EventFilter(
          search = search,
          categories = categories,
          dates = date.toRange(today),
          maxDistanceMeters = maxDistanceMeters?.toDouble(),
      )

  /**
   * How many filters are on, for the badge on the Filters button: the date, the distance and each
   * category count as one. The search doesn't count, since its own field shows it.
   */
  val activeFilterCount: Int
    get() =
        (if (date != DateChoice.Any) 1 else 0) +
            (if (maxDistanceMeters != null) 1 else 0) +
            categories.size
}

/** The distances the Filters sheet offers, in meters (Figma, #113); "Any" is null. */
val DISTANCE_CHOICES_METERS = listOf(500, 1_000, 2_000)

private fun DateChoice.toRange(today: LocalDate): DateRange? =
    when (this) {
      DateChoice.Any -> null
      DateChoice.Today -> DateRange(today, today.plusDays(1))
      DateChoice.ThisWeek -> DateRange(today, today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)))
      is DateChoice.Day -> DateRange(date, date.plusDays(1))
    }
