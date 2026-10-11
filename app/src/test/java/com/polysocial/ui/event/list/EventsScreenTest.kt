// Contributors: Claude (screen tests for the Events tab list, #105).
package com.polysocial.ui.event.list

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.LocalDate
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
  fun loading_showsTheSharedLoadingState() {
    show(EventsUiState.Loading)

    composeTestRule.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.loading_events)).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(C.Tag.screen_events).assertCountEquals(0)
  }

  @Test
  fun events_areListedUnderTheirDayHeadings() {
    val party = event("party", "Balélec warm-up", LocalDateTime.of(2026, 10, 11, 20, 0))
    val climbing = event("climbing", "Climbing", LocalDateTime.of(2026, 10, 12, 10, 0))
    val film = event("film", "Ciné-club: Dune", LocalDateTime.of(2026, 10, 14, 19, 30))
    show(
        EventsUiState.Events(
            listOf(
                EventDaySection(EventDay.Today, listOf(party)),
                EventDaySection(EventDay.Tomorrow, listOf(climbing)),
                EventDaySection(EventDay.On(LocalDate.of(2026, 10, 14)), listOf(film)),
            ),
            isOffline = false,
        )
    )

    composeTestRule.onNodeWithTag(C.Tag.screen_events).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.dayHeader(0)).assertTextEquals("Today")
    composeTestRule.onNodeWithTag(EventsTags.dayHeader(1)).assertTextEquals("Tomorrow")
    composeTestRule.onNodeWithTag(EventsTags.dayHeader(2)).assertTextEquals("Wed 14 Oct")
    composeTestRule.onNodeWithTag(EventsTags.card("party")).assertIsDisplayed()
    composeTestRule.onNodeWithText("Balélec warm-up").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.card("climbing")).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.card("film")).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.OFFLINE_BANNER).assertCountEquals(0)
  }

  @Test
  fun aCard_showsItsCategoryAndStartTime_andTheEndTimeWhenThereIsOne() {
    val withEnd =
        event(
            "with-end",
            "Party",
            LocalDateTime.of(2026, 10, 11, 20, 0),
            end = LocalDateTime.of(2026, 10, 11, 23, 30),
        )
    val withoutEnd =
        event(
            "without-end",
            "Climbing",
            LocalDateTime.of(2026, 10, 11, 9, 5),
            category = EventCategory.SPORTS,
        )
    show(
        EventsUiState.Events(
            listOf(EventDaySection(EventDay.Today, listOf(withoutEnd, withEnd))),
            isOffline = false,
        )
    )

    composeTestRule.onNodeWithText("Party · 20:00 – 23:30").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sports · 09:05").assertIsDisplayed()
  }

  @Test
  fun startTimes_areShownInTheGivenTimeZone() {
    // 18:00 UTC is 20:00 in Lausanne in October (summer time).
    val event =
        validEvent(
                startTime =
                    LocalDateTime.of(2026, 10, 11, 18, 0).atZone(ZoneId.of("UTC")).toInstant(),
                endTime = null,
            )
            .copy(id = "utc", category = EventCategory.STUDY)
    show(
        EventsUiState.Events(
            listOf(EventDaySection(EventDay.Today, listOf(event))),
            isOffline = false,
        )
    )

    composeTestRule.onNodeWithText("Study · 20:00").assertIsDisplayed()
  }

  @Test
  fun everyCategory_isNamedOnItsCard() {
    val start = LocalDateTime.of(2026, 10, 11, 18, 0)
    val events =
        EventCategory.entries.map { category ->
          event(category.name, category.name, start, category = category)
        }
    show(EventsUiState.Events(listOf(EventDaySection(EventDay.Today, events)), isOffline = false))

    listOf(
            R.string.event_category_party,
            R.string.event_category_sports,
            R.string.event_category_culture,
            R.string.event_category_study,
            R.string.event_category_other,
        )
        .forEach { label ->
          composeTestRule.onNode(hasText("${string(label)} · 18:00")).assertExists()
        }
  }

  @Test
  fun offlineEvents_showTheOfflineBannerAboveTheList() {
    val event = event("party", "Party", LocalDateTime.of(2026, 10, 11, 20, 0))
    show(
        EventsUiState.Events(
            listOf(EventDaySection(EventDay.Today, listOf(event))),
            isOffline = true,
        )
    )

    composeTestRule.onNodeWithTag(EventsTags.OFFLINE_BANNER).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.events_offline)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.card("party")).assertIsDisplayed()
  }

  @Test
  fun noEvents_showTheEmptyState() {
    show(EventsUiState.Empty(isOffline = false))

    composeTestRule.onNodeWithTag(EventsTags.EMPTY).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.events_empty_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.events_empty_body)).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.OFFLINE_BANNER).assertCountEquals(0)
    composeTestRule.onAllNodesWithTag(EventsTags.LIST).assertCountEquals(0)
  }

  @Test
  fun noEventsWhileOffline_showTheBannerAboveTheEmptyState() {
    show(EventsUiState.Empty(isOffline = true))

    composeTestRule.onNodeWithTag(EventsTags.OFFLINE_BANNER).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.EMPTY).assertIsDisplayed()
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
