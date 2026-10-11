// Contributors: Claude (screen tests for the Events tab list, #105).
package com.polysocial.ui.event.list

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventsScreenTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val lausanne = ZoneId.of("Europe/Zurich")

  private fun string(id: Int) = context.getString(id)

  private fun event(
      id: String,
      title: String,
      start: LocalDateTime,
      end: LocalDateTime? = null,
      category: EventCategory = EventCategory.PARTY,
  ) =
      validEvent(
              title = title,
              startTime = start.atZone(lausanne).toInstant(),
              endTime = end?.atZone(lausanne)?.toInstant(),
          )
          .copy(id = id, category = category)

  private var retries = 0

  private fun show(state: EventsUiState) {
    composeTestRule.setContent {
      PolySocialTheme { EventsScreen(state, zone = lausanne, onRetry = { retries++ }) }
    }
  }

  @Test
  fun anError_showsTheMessageAndRetryCallsBack() {
    show(EventsUiState.Error)

    composeTestRule.onNodeWithTag(EventsTags.ERROR).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.events_error)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.RETRY).performClick()

    assertEquals(1, retries)
  }

  @Test
  fun theRoute_showsTheViewModelsEvents_andRetryReloadsThemAfterAnError() {
    val repository = FakeEventRepository()
    val clock = Clock.fixed(repository.now, lausanne)
    val tomorrow =
        validEvent(title = "Tomorrow's event", startTime = repository.now.plusSeconds(24 * 3600))
            .copy(id = "tomorrow")
    repository.seed(tomorrow)
    repository.readFailure = true
    val viewModel = EventsViewModel(repository, clock)
    composeTestRule.setContent { PolySocialTheme { EventsRoute(viewModel = viewModel) } }
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeTestRule.onNodeWithTag(EventsTags.ERROR).assertIsDisplayed()

    repository.readFailure = false
    composeTestRule.onNodeWithTag(EventsTags.RETRY).performClick()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    composeTestRule.onNodeWithTag(EventsTags.card("tomorrow")).assertIsDisplayed()
    composeTestRule.onNodeWithText("Tomorrow's event").assertIsDisplayed()
  }
}
