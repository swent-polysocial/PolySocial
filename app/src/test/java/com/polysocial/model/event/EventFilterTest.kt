// Contributors: Claude (wrote these tests, #114).
package com.polysocial.model.event

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFilterTest {

  private val lausanne = ZoneId.of("Europe/Zurich")

  private fun event(id: String, title: String, category: EventCategory = EventCategory.OTHER) =
      validEvent(title = title).copy(id = id, category = category)

  private val cafe = event("cafe", "Café des langues", EventCategory.CULTURE)
  private val party = event("party", "Balélec warm-up", EventCategory.PARTY)
  private val climbing = event("climbing", "Climbing session", EventCategory.SPORTS)
  private val study = event("study", "Study together · Analysis III", EventCategory.STUDY)
  private val events = listOf(cafe, party, climbing, study)

  private fun ids(filter: EventFilter) = filterEvents(events, filter, lausanne).map { it.id }

  @Test
  fun theEmptyFilter_keepsEveryEventInTheSameOrder() {
    assertTrue(EventFilter().isEmpty)
    assertEquals(events, filterEvents(events, EventFilter(), lausanne))
  }

  @Test
  fun noEvents_giveNoEvents() {
    assertEquals(
        emptyList<Event>(),
        filterEvents(
            emptyList(),
            EventFilter(search = "party", categories = setOf(EventCategory.PARTY)),
            lausanne,
        ),
    )
  }

  @Test
  fun aSearch_keepsTheEventsWhoseTitleContainsIt() {
    assertEquals(listOf("climbing"), ids(EventFilter(search = "climb")))
    assertEquals(listOf("study"), ids(EventFilter(search = "analysis iii")))
  }

  @Test
  fun aSearch_ignoresCaseAndAccents() {
    assertEquals(listOf("cafe"), ids(EventFilter(search = "cafe")))
    assertEquals(listOf("cafe"), ids(EventFilter(search = "CAFÉ")))
    assertEquals(listOf("party"), ids(EventFilter(search = "balelec")))
  }

  @Test
  fun aSearch_ignoresSurroundingSpaces_andABlankSearchKeepsEverything() {
    assertEquals(listOf("party"), ids(EventFilter(search = "  warm-up ")))
    assertTrue(EventFilter(search = "   ").isEmpty)
    assertEquals(events, filterEvents(events, EventFilter(search = "   "), lausanne))
  }

  @Test
  fun aSearch_withNoMatch_keepsNothing() {
    assertEquals(emptyList<String>(), ids(EventFilter(search = "hackathon")))
  }

  @Test
  fun aSearch_onlyLooksAtTheTitle() {
    // Every test event has the same description, which mentions "exercise".
    assertEquals(emptyList<String>(), ids(EventFilter(search = "exercise")))
  }

  @Test
  fun oneCategory_keepsOnlyItsEvents() {
    assertEquals(listOf("climbing"), ids(EventFilter(categories = setOf(EventCategory.SPORTS))))
  }

  @Test
  fun severalCategories_combineWithOr() {
    assertFalse(EventFilter(categories = setOf(EventCategory.PARTY)).isEmpty)
    assertEquals(
        listOf("party", "study"),
        ids(EventFilter(categories = setOf(EventCategory.STUDY, EventCategory.PARTY))),
    )
  }

  @Test
  fun aSearchAndCategories_combineWithAnd() {
    val filter =
        EventFilter(search = "s", categories = setOf(EventCategory.SPORTS, EventCategory.CULTURE))

    // "Café des langues" and "Climbing session" both contain an "s"; "Study together" does too,
    // but isn't in these categories.
    assertEquals(listOf("cafe", "climbing"), ids(filter))
    assertEquals(
        emptyList<String>(),
        ids(EventFilter(search = "café", categories = setOf(EventCategory.SPORTS))),
    )
  }

  // ---- Dates ----

  /** Wednesday 14 October 2026. */
  private val today = LocalDate.of(2026, 10, 14)

  private fun startingAt(id: String, time: LocalDateTime, zone: ZoneId = lausanne) =
      validEvent(title = id, startTime = time.atZone(zone).toInstant()).copy(id = id)

  private fun day(date: LocalDate) = DateRange(date, date.plusDays(1))

  private fun idsOn(events: List<Event>, dates: DateRange, zone: ZoneId = lausanne) =
      filterEvents(events, EventFilter(dates = dates), zone).map { it.id }

  @Test
  fun aDay_keepsTheEventsStartingThatDay_fromMidnightToJustBeforeTheNextMidnight() {
    val events =
        listOf(
            startingAt("lastEvening", today.minusDays(1).atTime(23, 59)),
            startingAt("midnight", today.atStartOfDay()),
            startingAt("noon", today.atTime(12, 0)),
            startingAt("lateNight", today.atTime(23, 59, 59)),
            startingAt("nextMidnight", today.plusDays(1).atStartOfDay()),
        )

    assertFalse(EventFilter(dates = day(today)).isEmpty)
    assertEquals(listOf("midnight", "noon", "lateNight"), idsOn(events, day(today)))
  }

  @Test
  fun aWeek_includesItsFirstDay_andExcludesTheDayAfterItsEnd() {
    val thisWeek = DateRange(today, today.plusDays(7))
    val events =
        listOf(
            startingAt("first", today.atTime(9, 0)),
            startingAt("last", today.plusDays(6).atTime(22, 0)),
            startingAt("after", today.plusDays(7).atTime(9, 0)),
        )

    assertEquals(listOf("first", "last"), idsOn(events, thisWeek))
  }

  @Test
  fun theDay_isTakenInTheGivenTimeZone() {
    // 22:30 UTC on the 13th is already 00:30 on the 14th in Lausanne.
    val night = startingAt("night", today.minusDays(1).atTime(22, 30), ZoneOffset.UTC)

    assertEquals(listOf("night"), idsOn(listOf(night), day(today), lausanne))
    assertEquals(emptyList<String>(), idsOn(listOf(night), day(today), ZoneOffset.UTC))
  }

  @Test
  fun aDateRange_combinesWithTheOtherCriteria() {
    val sportsToday =
        startingAt("sportsToday", today.atTime(18, 0)).copy(category = EventCategory.SPORTS)
    val partyToday =
        startingAt("partyToday", today.atTime(20, 0)).copy(category = EventCategory.PARTY)
    val sportsTomorrow =
        startingAt("sportsTomorrow", today.plusDays(1).atTime(18, 0))
            .copy(category = EventCategory.SPORTS)
    val filter = EventFilter(categories = setOf(EventCategory.SPORTS), dates = day(today))

    assertEquals(
        listOf("sportsToday"),
        filterEvents(listOf(sportsToday, partyToday, sportsTomorrow), filter, lausanne).map {
          it.id
        },
    )
  }

  @Test
  fun aDateRange_mustHoldAtLeastOneDay() {
    assertThrows(IllegalArgumentException::class.java) { DateRange(today, today) }
    assertThrows(IllegalArgumentException::class.java) { DateRange(today, today.minusDays(1)) }
  }
}
