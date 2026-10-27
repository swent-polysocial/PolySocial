// Contributors: Claude (wrote these tests, #128; large font sizes after a test audit, #128).
package com.polysocial.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The map's event preview (#128, Figma "Map · event preview · association"): an association's event
 * shows the Verified badge after its category and "· by <name>" after its time; without the name,
 * only the badge; a student's event shows neither.
 */
@RunWith(AndroidJUnit4::class)
class MapAssociationNamesScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val party = associationEvent(validEvent(title = "Party")).copy(id = "party")
  private val study = studentEvent(validEvent(title = "Study")).copy(id = "study")

  private fun showPreviewOf(event: Event, names: Map<String, String>) {
    composeTestRule.setContent {
      PolySocialTheme {
        MapScreen(
            MapUiState(
                status = MapContentStatus.READY,
                events = listOf(party, study),
                selectedEvent = event,
                renderStatus = MapRenderStatus.READY,
                associationNames = names,
            ),
            onSelectEvent = {},
            onClosePreview = {},
            onViewDetails = {},
            onRetry = {},
            onRenderStatus = {},
            tokenConfigured = true,
            renderer = { _, _, _, _ -> },
        )
      }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  /** The Verified pill, read as one label like TalkBack does: its node holds its text. */
  private fun pill(tag: String) = composeTestRule.onNodeWithTag(tag)

  /** The preview's time line, whatever the time format: it ends with [suffix], or doesn't. */
  private fun timeEndsWith(suffix: String) =
      SemanticsMatcher("time ends with \"$suffix\"") { node ->
        node.config[SemanticsProperties.Text].joinToString("") { it.text }.endsWith(suffix)
      }

  private val timeHasNoName =
      SemanticsMatcher("time without a name") { node ->
        !node.config[SemanticsProperties.Text].joinToString("") { it.text }.contains(" · by ")
      }

  // The largest font size Android offers, with native graphics, which measure text like a phone.
  @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theBadgeStaysWhole() {
    // A category and a date, not "Tonight": the longest category line.
    showPreviewOf(party.copy(category = EventCategory.SPORTS), mapOf(CLUB_UID to CLUB_NAME))

    val badge = pill(MapTags.VERIFIED).assertIsDisplayed()
    // "Verified" on one line, so the pill is wider than its padding and as tall as one line.
    assertEquals(1, textLayoutInside(MapTags.VERIFIED).lineCount)
    badge.assertWidthIsAtLeast(36.dp)
    val screen = composeTestRule.onNodeWithTag(MapTags.PREVIEW).getBoundsInRoot()
    assertTrue("The badge ends past the preview", badge.getBoundsInRoot().right <= screen.right)
  }

  // Native graphics give text its real height, which the badge must not exceed in the layout.
  @Config(qualifiers = "w360dp-h800dp")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun theBadge_doesntMoveTheTitleOrGrowThePreview() {
    // The same event, as a student's and as an association's: only the badge differs.
    val asStudent = study.copy(title = party.title, category = party.category)
    var shown by mutableStateOf<Event>(asStudent)
    composeTestRule.setContent {
      PolySocialTheme {
        MapScreen(
            MapUiState(
                status = MapContentStatus.READY,
                events = listOf(party, asStudent),
                selectedEvent = shown,
                renderStatus = MapRenderStatus.READY,
                associationNames = mapOf(CLUB_UID to CLUB_NAME),
            ),
            onSelectEvent = {},
            onClosePreview = {},
            onViewDetails = {},
            onRetry = {},
            onRenderStatus = {},
            tokenConfigured = true,
            renderer = { _, _, _, _ -> },
        )
      }
    }
    val studentTitle = composeTestRule.onNodeWithTag(MapTags.TITLE).getBoundsInRoot()
    val studentPreview = composeTestRule.onNodeWithTag(MapTags.PREVIEW).getBoundsInRoot()

    shown = party
    composeTestRule.waitForIdle()

    pill(MapTags.VERIFIED).assertIsDisplayed()
    assertEquals(studentTitle, composeTestRule.onNodeWithTag(MapTags.TITLE).getBoundsInRoot())
    val preview = composeTestRule.onNodeWithTag(MapTags.PREVIEW).getBoundsInRoot()
    assertEquals(studentPreview.bottom - studentPreview.top, preview.bottom - preview.top)
  }

  /** The layout of the text inside the node [tag], as drawn. */
  private fun textLayoutInside(tag: String): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    composeTestRule
        .onNode(hasParent(hasTestTag(tag)) and hasText("Verified"), useUnmergedTree = true)
        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    return layouts.single()
  }

  @Test
  fun anAssociationsEvent_showsTheBadgeAndItsName() {
    showPreviewOf(party, mapOf(CLUB_UID to CLUB_NAME))

    pill(MapTags.VERIFIED).assertIsDisplayed().assertTextEquals("Verified")
    node(MapTags.TIME).assert(timeEndsWith(" · by $CLUB_NAME"))
  }

  @Test
  fun anAssociationsEvent_withoutItsName_showsTheBadgeOnly() {
    showPreviewOf(party, emptyMap())

    pill(MapTags.VERIFIED).assertIsDisplayed()
    node(MapTags.TIME).assertIsDisplayed().assert(timeHasNoName)
  }

  @Test
  fun aStudentsEvent_showsNeitherTheBadgeNorAName() {
    // Even with a name known for its creator, an event without the badge shows no name.
    showPreviewOf(study, mapOf(CLUB_UID to CLUB_NAME, STUDENT_UID to "Not an association"))

    pill(MapTags.VERIFIED).assertDoesNotExist()
    node(MapTags.TIME).assert(timeHasNoName)
  }
}
