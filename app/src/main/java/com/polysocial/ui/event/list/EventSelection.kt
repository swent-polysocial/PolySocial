// Contributors: Claude (the Events tab's search and filter selection, a picked day and a distance,
// #115; saved and restored under a prefix so the Map tab keeps its own, #116).
package com.polysocial.ui.event.list

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.event.DateRange
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventFilter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

/** The days the Events and Map tabs' date filter keeps. */
sealed interface DateChoice {
  data object Any : DateChoice

  data object Today : DateChoice

  /** From today until Sunday included (Figma, #113). */
  data object ThisWeek : DateChoice

  /** One day, picked among the next 14 days in the Filters sheet. */
  data class Day(val date: LocalDate) : DateChoice
}

/**
 * What the student searched for and picked on the Events or the Map tab (each keeps its own).
 * Unlike [EventFilter], the date is a choice ("Today") rather than days, so it stays right when the
 * date changes while the tab is open.
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

/**
 * Saves this selection in [savedState] under keys starting with [prefix], so it survives rotation
 * and process death; [restoreEventSelection] reads it back. Each tab uses its own prefix, since the
 * Events and Map tabs keep separate selections (#113).
 */
fun EventSelection.saveTo(savedState: SavedStateHandle, prefix: String) {
  savedState["${prefix}_search"] = search
  savedState["${prefix}_categories"] = ArrayList(categories.map { it.name })
  savedState["${prefix}_date"] = date.saved()
  savedState["${prefix}_distance"] = maxDistanceMeters
}

/**
 * The selection saved by [saveTo] under [prefix], or the empty selection. A value it doesn't
 * recognize (an unknown category, date or distance) is dropped instead of failing.
 */
fun restoreEventSelection(savedState: SavedStateHandle, prefix: String) =
    EventSelection(
        search = savedState.get<String>("${prefix}_search").orEmpty(),
        categories =
            savedState
                .get<ArrayList<String>>("${prefix}_categories")
                .orEmpty()
                .mapNotNull { name -> EventCategory.entries.firstOrNull { it.name == name } }
                .toSet(),
        date = restoreDate(savedState.get<String>("${prefix}_date")),
        maxDistanceMeters =
            savedState.get<Int>("${prefix}_distance")?.takeIf { it in DISTANCE_CHOICES_METERS },
    )

/** The date choice as text for the saved state: its name, or the picked day (ISO 8601). */
private fun DateChoice.saved(): String =
    when (this) {
      DateChoice.Any -> "ANY"
      DateChoice.Today -> "TODAY"
      DateChoice.ThisWeek -> "THIS_WEEK"
      is DateChoice.Day -> date.toString()
    }

/** The opposite of [saved]; anything it doesn't recognize means any day. */
private fun restoreDate(saved: String?): DateChoice =
    when (saved) {
      null,
      "ANY" -> DateChoice.Any
      "TODAY" -> DateChoice.Today
      "THIS_WEEK" -> DateChoice.ThisWeek
      else ->
          try {
            DateChoice.Day(LocalDate.parse(saved))
          } catch (_: DateTimeParseException) {
            DateChoice.Any
          }
    }
