// Contributors: Claude (wrote these tests, #128).
package com.polysocial.ui.event.list

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.validEvent
import com.polysocial.ui.event.CLUB_NAME
import com.polysocial.ui.event.CLUB_UID
import com.polysocial.ui.event.STUDENT_UID
import com.polysocial.ui.event.associationEvent
import com.polysocial.ui.event.studentEvent
import com.polysocial.ui.theme.PolySocialTheme
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Events tab's cards (#128, Figma "Events · association event"): an association's event shows
 * the Verified badge next to its title and "· by <name>" at the end of its details; without the
 * name, only the badge; a student's event shows neither.
 */
@RunWith(AndroidJUnit4::class)
class EventsAssociationNamesScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val lausanne = ZoneId.of("Europe/Zurich")

  /** Sunday 11 October 2026, 12:00 in Lausanne. */
  private val today = LocalDate.of(2026, 10, 11)
  private val clock = Clock.fixed(today.atTime(12, 0).atZone(lausanne).toInstant(), lausanne)

  private fun at(hour: Int) = LocalDateTime.of(today, java.time.LocalTime.of(hour, 0))

  private val party =
      associationEvent(
              validEvent(
                  title = "Balélec warm-up",
                  startTime = at(20).atZone(lausanne).toInstant(),
                  endTime = null,
              )
          )
          .copy(id = "party", category = EventCategory.PARTY)
  private val study =
      studentEvent(
              validEvent(
                  title = "Study session",
                  startTime = at(18).atZone(lausanne).toInstant(),
                  endTime = null,
              )
          )
          .copy(id = "study", category = EventCategory.STUDY)

  private fun show(names: Map<String, String>) {
    val events = listOf(study, party)
    composeTestRule.setContent {
      PolySocialTheme {
        EventsScreen(
            EventsUiState(
                list =
                    EventsListState.Events(
                        groupEventsByDay(events, clock),
                        today,
                        isOffline = false,
                    ),
                associationNames = names,
            ),
            zone = lausanne,
            today = today,
            onRetry = {},
            onClearFilters = {},
            onSearchChange = {},
            onSelectDate = {},
            onToggleCategory = {},
            onClearSheetFilters = {},
            onSelectDistance = {},
            onTurnOnLocation = {},
            onEventClick = {},
        )
      }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  /** The Verified pill, read as one label like TalkBack does: its node holds its text. */
  private fun pill(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun scrollTo(eventId: String) {
    composeTestRule
        .onNodeWithTag(EventsTags.LIST)
        .performScrollToNode(hasTestTag(EventsTags.card(eventId)))
  }

  @Test
  fun anAssociationsEvent_showsTheBadgeAndItsName() {
    show(mapOf(CLUB_UID to CLUB_NAME))
    scrollTo("party")

    pill(EventsTags.verified("party")).assertIsDisplayed().assertTextEquals("Verified")
    node(EventsTags.details("party")).assertTextEquals("Party · 20:00 · by $CLUB_NAME")
  }

  @Test
  fun anAssociationsEvent_withoutItsName_showsTheBadgeOnly() {
    show(emptyMap())
    scrollTo("party")

    pill(EventsTags.verified("party")).assertIsDisplayed().assertTextEquals("Verified")
    node(EventsTags.details("party")).assertTextEquals("Party · 20:00")
  }

  @Test
  fun aStudentsEvent_showsNeitherTheBadgeNorAName() {
    // Even with a name known for its creator (say an association whose verification was revoked),
    // an event without the badge shows no name.
    show(mapOf(CLUB_UID to CLUB_NAME, STUDENT_UID to "Not an association"))
    scrollTo("study")

    pill(EventsTags.verified("study")).assertDoesNotExist()
    node(EventsTags.details("study")).assertTextEquals("Study · 18:00")
  }
}
