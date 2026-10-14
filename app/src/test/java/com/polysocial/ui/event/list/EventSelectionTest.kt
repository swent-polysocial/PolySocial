// Contributors: Claude (wrote these tests, #115).
package com.polysocial.ui.event.list

import com.polysocial.model.event.DateRange
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventFilter
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class EventSelectionTest {

  /** Wednesday 14 October 2026. */
  private val wednesday = LocalDate.of(2026, 10, 14)
  private val sunday = LocalDate.of(2026, 10, 18)
  private val monday = LocalDate.of(2026, 10, 19)

  private fun datesOn(today: LocalDate, date: DateChoice) =
      EventSelection(date = date).toFilter(today).dates

  @Test
  fun theEmptySelection_isTheEmptyFilter() {
    assertEquals(EventFilter(), EventSelection().toFilter(wednesday))
  }

  @Test
  fun theSearchAndCategories_areKeptAsTheyAre() {
    val selection = EventSelection(search = "café", categories = setOf(EventCategory.CULTURE))

    assertEquals(
        EventFilter(search = "café", categories = setOf(EventCategory.CULTURE)),
        selection.toFilter(wednesday),
    )
  }

  @Test
  fun today_isTheCurrentDayOnly() {
    assertEquals(DateRange(wednesday, wednesday.plusDays(1)), datesOn(wednesday, DateChoice.Today))
  }

  @Test
  fun thisWeek_runsFromTodayUntilSunday() {
    assertEquals(DateRange(wednesday, monday), datesOn(wednesday, DateChoice.ThisWeek))
  }

  @Test
  fun thisWeek_onASunday_isJustThatSunday() {
    assertEquals(DateRange(sunday, monday), datesOn(sunday, DateChoice.ThisWeek))
  }

  @Test
  fun thisWeek_onAMonday_isTheWholeWeek() {
    assertEquals(DateRange(monday, monday.plusDays(7)), datesOn(monday, DateChoice.ThisWeek))
  }

  @Test
  fun aPickedDay_isThatDayOnly() {
    assertEquals(DateRange(sunday, monday), datesOn(wednesday, DateChoice.Day(sunday)))
  }

  @Test
  fun aDistance_isKeptInMeters() {
    assertEquals(
        EventFilter(maxDistanceMeters = 1_000.0),
        EventSelection(maxDistanceMeters = 1_000).toFilter(wednesday),
    )
  }

  @Test
  fun theActiveFilterCount_countsTheDateTheDistanceAndEachCategory() {
    assertEquals(0, EventSelection(search = "party").activeFilterCount)
    assertEquals(1, EventSelection(date = DateChoice.Day(sunday)).activeFilterCount)
    assertEquals(
        4,
        EventSelection(
                categories = setOf(EventCategory.PARTY, EventCategory.STUDY),
                date = DateChoice.Today,
                maxDistanceMeters = 500,
            )
            .activeFilterCount,
    )
  }
}
