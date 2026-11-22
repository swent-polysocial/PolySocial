// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of the Directions button and its
// message on the event detail screen, #391).
package com.polysocial.ui.event.detail

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.validEvent
import com.polysocial.ui.theme.PolySocialTheme
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Directions button under the place, and the message when no app can open it (#391). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class EventDetailDirectionsScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private val place = Coordinates(46.5191, 6.5668)

  private val event = validEvent().copy(id = "event-1", location = place)

  private fun loaded(hasEnded: Boolean = false) =
      EventDetailUiState.Loaded(
          EventDetails(
              event,
              LocalDate.of(2026, 10, 10),
              LocalTime.of(13, 0),
              LocalTime.of(17, 0),
              0,
          ),
          isOffline = false,
          isOrganizer = false,
          hasEnded = hasEnded,
      )

  private val opened = mutableListOf<Coordinates>()
  private val shown = mutableListOf<DirectionsUnavailable>()

  private fun show(
      state: EventDetailUiState = loaded(),
      unavailable: DirectionsUnavailable? = null,
  ) {
    composeTestRule.setContent {
      PolySocialTheme {
        EventDetailScreen(
            state,
            onBack = {},
            onRetry = {},
            directions = EventDirections({ opened += it }, unavailable, { shown += it }),
        )
      }
    }
  }

  @Test
  fun theButtonIsUnderThePlace_andOpensDirectionsToTheEventsCoordinates() {
    show()

    composeTestRule
        .onNodeWithText(context.getString(R.string.event_detail_directions))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventDetailTags.DIRECTIONS).performClick()

    assertEquals(listOf(place), opened)
  }

  @Test
  fun anEventThatHasEndedOffersNoDirections() {
    show(loaded(hasEnded = true))

    composeTestRule.onNodeWithTag(EventDetailTags.DIRECTIONS).assertDoesNotExist()
  }

  @Test
  fun withNoAppToOpenThem_aMessageIsShownOnce() {
    val message = DirectionsUnavailable(0)
    show(unavailable = message)

    composeTestRule
        .onNodeWithText(context.getString(R.string.event_detail_directions_unavailable))
        .assertIsDisplayed()
    composeTestRule.mainClock.advanceTimeBy(11_000)
    composeTestRule.waitForIdle()

    assertEquals(listOf(message), shown)
  }

  @Test
  fun withoutAFailure_noMessageIsShown() {
    show()

    composeTestRule
        .onNodeWithText(context.getString(R.string.event_detail_directions_unavailable))
        .assertDoesNotExist()
    assertEquals(emptyList<DirectionsUnavailable>(), shown)
  }
}
