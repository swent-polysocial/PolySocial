// Contributors: Claude (wrote these tests, #128; the badge's position on the title, #250).
package com.polysocial.ui.event.list

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.Event
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
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

  private fun show(names: Map<String, String>, events: List<Event> = listOf(study, party)) {
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

  // Native graphics measure text like a phone; 360 dp is the Figma frame's width.
  @Config(qualifiers = "w360dp-h800dp-xxhdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun theBadge_isCenteredOnTheTitlesLine() {
    show(mapOf(CLUB_UID to CLUB_NAME))
    scrollTo("party")

    assertBadgeCenteredOnFirstLine("party", party.title, expectedLines = 1)
  }

  @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theBadgeIsCenteredOnTheTitlesFirstLine() {
    // At this size the title wraps: the badge belongs to its first line, not to the whole title.
    show(mapOf(CLUB_UID to CLUB_NAME))
    scrollTo("party")

    assertBadgeCenteredOnFirstLine("party", party.title, expectedLines = 2)
  }

  @Config(qualifiers = "w360dp-h800dp-xxhdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun theBadge_doesntMoveTheDetailsDown() {
    // The same title on a student's card and an association's: the taller badge sticks out of
    // the title's line instead of pushing the details further from the title.
    val twin = study.copy(id = "twin", title = party.title)
    show(mapOf(CLUB_UID to CLUB_NAME), listOf(twin, party))

    scrollTo("twin")
    val student = detailsBelowTitle("twin")
    scrollTo("party")
    assertEquals(student.value, detailsBelowTitle("party").value, 0.01f)
  }

  /**
   * The badge's center is within 0.5 dp of the center of the title's first line, like in Figma
   * "Events · association event", where both centers are at the same height.
   */
  private fun assertBadgeCenteredOnFirstLine(eventId: String, title: String, expectedLines: Int) {
    val titleNode = titleOf(eventId, title)
    val layout = textLayout(titleNode)
    assertEquals(expectedLines, layout.lineCount)
    val firstLine =
        with(composeTestRule.density) {
          titleNode.getBoundsInRoot().top +
              (layout.getLineTop(0) + layout.getLineBottom(0)).toDp() / 2
        }
    val badge = pill(EventsTags.verified(eventId)).getBoundsInRoot()
    val offset = (badge.top + badge.bottom) / 2 - firstLine
    assertTrue("The badge is $offset off the title's first line", abs(offset.value) <= 0.5f)
  }

  /** How far the details line of [eventId]'s card starts below the top of its title. */
  private fun detailsBelowTitle(eventId: String): Dp =
      node(EventsTags.details(eventId)).getBoundsInRoot().top -
          titleOf(eventId, party.title).getBoundsInRoot().top

  private fun titleOf(eventId: String, title: String) =
      composeTestRule.onNode(
          hasText(title) and hasAnyAncestor(hasTestTag(EventsTags.card(eventId))),
          useUnmergedTree = true,
      )

  private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    return layouts.single()
  }
}
