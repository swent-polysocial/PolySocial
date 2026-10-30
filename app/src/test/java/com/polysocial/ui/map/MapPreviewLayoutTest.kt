// Contributors: Claude (wrote these tests, #222).
package com.polysocial.ui.map

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.Event
import com.polysocial.model.event.validEvent
import com.polysocial.ui.event.CLUB_NAME
import com.polysocial.ui.event.CLUB_UID
import com.polysocial.ui.event.associationEvent
import com.polysocial.ui.theme.PolySocialTheme
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The map's event preview as in the Figma frames "Map · event preview" (132:550) and its
 * association variant (958:180): the time line, with the place and the association's name, sits in
 * the text column under the title, and the distance gets its own strip above the buttons (#222).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class MapPreviewLayoutTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val satellite =
      validEvent(title = "Warm-up").copy(id = "warm-up", locationName = "Satellite")

  private fun showPreviewOf(
      event: Event,
      names: Map<String, String> = emptyMap(),
      distanceMeters: Double? = null,
  ) {
    composeTestRule.setContent {
      PolySocialTheme {
        MapScreen(
            MapUiState(
                status = MapContentStatus.READY,
                events = listOf(event),
                selectedEvent = event,
                renderStatus = MapRenderStatus.READY,
                associationNames = names,
                locationState =
                    if (distanceMeters == null) MapLocationState.Idle
                    else MapLocationState.Available(event.location),
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

  private fun bounds(tag: String) =
      composeTestRule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

  private fun time(event: Event): String {
    val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(MAP_TIME_ZONE)
    return "${format.format(event.startTime)}–${format.format(event.endTime!!)}"
  }

  @Test
  fun theTimeLineNamesThePlace() {
    showPreviewOf(satellite)
    composeTestRule
        .onNodeWithTag(MapTags.TIME, useUnmergedTree = true)
        .assertTextEquals("${time(satellite)} · Satellite")
  }

  @Test
  fun anAssociationsNameComesAfterThePlace() {
    val party = associationEvent(satellite)
    showPreviewOf(party, mapOf(CLUB_UID to CLUB_NAME))
    composeTestRule
        .onNodeWithTag(MapTags.TIME, useUnmergedTree = true)
        .assertTextEquals("${time(party)} · Satellite · by $CLUB_NAME")
  }

  @Test
  fun withoutAPlaceNameTheTimeLineIsTheTimeAlone() {
    showPreviewOf(satellite.copy(locationName = " "))
    composeTestRule
        .onNodeWithTag(MapTags.TIME, useUnmergedTree = true)
        .assertTextEquals(time(satellite))
  }

  @Test
  fun theTimeLineSitsInTheTextColumnUnderTheTitle() {
    showPreviewOf(satellite)
    val icon = bounds(MapTags.CATEGORY_ICON)
    val title = bounds(MapTags.TITLE)
    val time = bounds(MapTags.TIME)
    // Figma: the text column starts at x = 80, 12 dp after the 52 dp tile; the time line with it.
    assertEquals(bounds(MapTags.PREVIEW).left + 80.dp, title.left)
    assertEquals(title.left, time.left)
    assertTrue("The time line is beside the icon", time.left > icon.right)
    assertTrue("The time line is not below the title", time.top >= title.bottom)
    // It ends before the close button.
    assertTrue(
        "The time line runs under the close button",
        time.right <= bounds(MapTags.CLOSE).left,
    )
  }

  @Test
  fun theDistanceSitsBetweenTheTimeLineAndTheButtons() {
    showPreviewOf(satellite, distanceMeters = 0.0)
    val distance = bounds(MapTags.DISTANCE)
    assertTrue(
        "The distance is not below the time line",
        distance.top >= bounds(MapTags.TIME).bottom,
    )
    assertTrue(
        "The distance is not above the buttons",
        distance.bottom <= bounds(MapTags.DETAILS).top,
    )
  }

  @Test
  fun withoutALocationFixThereIsNoDistanceStrip() {
    showPreviewOf(satellite)
    composeTestRule.onNodeWithTag(MapTags.DISTANCE, useUnmergedTree = true).assertDoesNotExist()
  }

  // On a narrow phone at 2x, "Find a group" takes two lines; native graphics give text its real
  // size, so a line cut off at the bottom shows as an overflow.
  @Config(qualifiers = "w320dp-h800dp-xxhdpi", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theButtonsAndTheDistanceGrowToShowEveryLine() {
    showPreviewOf(satellite, distanceMeters = 350.0)
    val texts =
        mapOf(
            "Details" to hasAnyAncestor(hasTestTag(MapTags.DETAILS)),
            "Find a group" to hasAnyAncestor(hasTestTag(MapTags.FIND_GROUP)),
            "The distance" to hasTestTag(MapTags.DISTANCE),
        )
    for ((name, text) in texts) {
      assertFalse("$name loses its last lines", textLayoutOf(text).didOverflowHeight)
    }
  }

  /** The layout of the text [matcher] finds, as drawn. */
  private fun textLayoutOf(matcher: SemanticsMatcher): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    composeTestRule.onNode(matcher, useUnmergedTree = true).performSemanticsAction(
        SemanticsActions.GetTextLayoutResult
    ) {
      it(layouts)
    }
    return layouts.single()
  }
}
