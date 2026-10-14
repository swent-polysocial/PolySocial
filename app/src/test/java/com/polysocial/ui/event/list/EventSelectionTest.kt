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
    assertEquals(DateRange(wednesday, wednesday.plusDays(1)), datesOn(wednesday, DateChoice.TODAY))
  }

  @Test
  fun thisWeek_runsFromTodayUntilSunday() {
    assertEquals(DateRange(wednesday, monday), datesOn(wednesday, DateChoice.THIS_WEEK))
  }

  @Test
  fun thisWeek_onASunday_isJustThatSunday() {
    assertEquals(DateRange(sunday, monday), datesOn(sunday, DateChoice.THIS_WEEK))
  }

  @Test
  fun thisWeek_onAMonday_isTheWholeWeek() {
    assertEquals(DateRange(monday, monday.plusDays(7)), datesOn(monday, DateChoice.THIS_WEEK))
  }
}
