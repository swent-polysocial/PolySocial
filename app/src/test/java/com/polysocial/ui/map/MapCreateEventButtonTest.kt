// Contributors: Claude (UI tests for the "+" among the map overlays, #110).
package com.polysocial.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.validEvent
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The map places the "+" as in the Figma "Map" frames: bottom right, with the location button just
 * above it, and hidden while an event card is open.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class MapCreateEventButtonTest {
  @get:Rule val composeRule = createComposeRule()

  private var createEventClicks = 0

  private val loaded =
      MapUiState(status = MapContentStatus.READY, renderStatus = MapRenderStatus.READY)

  /** Pixels are whole numbers, so a dp position can be off by a fraction. */
  private fun assertDp(expected: Dp, actual: Dp) = assertEquals(expected.value, actual.value, 0.5f)

  private fun show(state: MapUiState = loaded, withCreateEvent: Boolean = true) {
    composeRule.setContent {
      PolySocialTheme {
        // The Figma frame's size above the bottom bar.
        Box(Modifier.requiredSize(360.dp, 728.dp)) {
          MapScreen(
              state = state,
              onSelectEvent = {},
              onClosePreview = {},
              onViewDetails = {},
              onRetry = {},
              onRenderStatus = {},
              onCreateEvent = if (withCreateEvent) ({ createEventClicks++ }) else null,
              tokenConfigured = true,
              renderer = { _, _, _, _ -> },
          )
        }
      }
    }
  }

  @Test
  fun plusOpensCreateEvent() {
    show()

    composeRule.onNodeWithTag(C.Tag.create_event_button).assertIsDisplayed().performClick()

    assertEquals(1, createEventClicks)
  }

  @Test
  fun plusSitsBottomRightWithTheLocationButtonAboveIt() {
    show()

    val plus = composeRule.onNodeWithTag(C.Tag.create_event_button).getUnclippedBoundsInRoot()
    val location = composeRule.onNodeWithTag(MapTags.LOCATION_REFRESH).getUnclippedBoundsInRoot()
    // Figma: "+" at x 288, y 652 (56 dp); location button at x 292, y 592 (48 dp).
    assertDp(288.dp, plus.left)
    assertDp(652.dp, plus.top)
    assertDp(56.dp, plus.right - plus.left)
    assertDp(292.dp, location.left)
    assertDp(592.dp, location.top)
    assertDp(12.dp, plus.top - location.bottom)
  }

  @Test
  fun plusIsHiddenWhileAnEventCardIsOpen() {
    show(loaded.copy(events = listOf(validEvent()), selectedEvent = validEvent()))

    composeRule.onNodeWithTag(MapTags.PREVIEW).assertIsDisplayed()
    composeRule.onAllNodesWithTag(C.Tag.create_event_button).assertCountEquals(0)
  }

  @Test
  fun withoutAnActionTheMapHasNoPlusAndKeepsItsLocationButtonLow() {
    show(withCreateEvent = false)

    composeRule.onAllNodesWithTag(C.Tag.create_event_button).assertCountEquals(0)
    val location = composeRule.onNodeWithTag(MapTags.LOCATION_REFRESH).getUnclippedBoundsInRoot()
    assertDp(728.dp - 12.dp, location.bottom)
  }
}
