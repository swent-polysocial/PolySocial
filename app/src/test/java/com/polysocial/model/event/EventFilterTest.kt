// Contributors: Claude (wrote these tests, #114).
package com.polysocial.model.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFilterTest {

  private fun event(id: String, title: String, category: EventCategory = EventCategory.OTHER) =
      validEvent(title = title).copy(id = id, category = category)

  private val cafe = event("cafe", "Café des langues", EventCategory.CULTURE)
  private val party = event("party", "Balélec warm-up", EventCategory.PARTY)
  private val climbing = event("climbing", "Climbing session", EventCategory.SPORTS)
  private val study = event("study", "Study together · Analysis III", EventCategory.STUDY)
  private val events = listOf(cafe, party, climbing, study)

  private fun ids(filter: EventFilter) = filterEvents(events, filter).map { it.id }

  @Test
  fun theEmptyFilter_keepsEveryEventInTheSameOrder() {
    assertTrue(EventFilter().isEmpty)
    assertEquals(events, filterEvents(events, EventFilter()))
  }

  @Test
  fun noEvents_giveNoEvents() {
    assertEquals(
        emptyList<Event>(),
        filterEvents(
            emptyList(),
            EventFilter(search = "party", categories = setOf(EventCategory.PARTY)),
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
    assertEquals(events, filterEvents(events, EventFilter(search = "   ")))
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
}
