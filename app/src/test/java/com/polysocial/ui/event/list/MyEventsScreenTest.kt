// Contributors: Claude Opus 5.5 (medium) (wrote these screen tests, #204).
package com.polysocial.ui.event.list

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.Event
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyEventsScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val lausanne = ZoneId.of("Europe/Zurich")

  /** TEST_NOW is 13:00 on 1 January 2026 in Lausanne. */
  private val today = LocalDate.of(2026, 1, 1)

  private fun chosen(id: String, title: String, startsIn: Duration, place: String = "Satellite") =
      validEvent(title = title, startTime = TEST_NOW.plus(startsIn))
          .copy(id = id, locationName = place)

  /** Started 30 minutes ago and ends in 90 minutes. */
  private val running = chosen("running", "Balélec warm-up", Duration.ofMinutes(-30))
  private val cineClub = chosen("cineClub", "Ciné-club: Dune", Duration.ofDays(60), place = "")
  private val lastYear = chosen("lastYear", "Study together", Duration.ofDays(-30))

  private var retries = 0
  private var browsed = 0
  private var messagesShown = 0
  private val opened = mutableListOf<String>()
  private val removed = mutableListOf<String>()

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  private fun show(state: MyEventsUiState) {
    composeTestRule.setContent {
      PolySocialTheme {
        MyEventsScreen(
            state,
            zone = lausanne,
            today = today,
            onRetry = { retries++ },
            onEventClick = { opened += it },
            onRemove = { removed += it },
            onBrowseUpcoming = { browsed++ },
            onMessageShown = { messagesShown++ },
        )
      }
    }
  }

  private fun loaded(
      upcoming: List<Event> = listOf(running, cineClub),
      past: List<Event> = listOf(lastYear),
      unavailableIds: List<String> = emptyList(),
      missingCount: Int = 0,
      isOffline: Boolean = false,
  ) =
      MyEventsUiState(
          MyEventsList.Events(upcoming, past, unavailableIds, missingCount, TEST_NOW, isOffline)
      )

  private fun scrollTo(tag: String) =
      composeTestRule.onNodeWithTag(MyEventsTags.LIST).performScrollToNode(hasTestTag(tag))

  @Test
  fun chosenEvents_showUnderUpcomingAndPast_withTheirDayTimeAndPlace() {
    show(loaded())

    composeTestRule.onNodeWithTag(MyEventsTags.UPCOMING_HEADER).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(MyEventsTags.details(running.id), useUnmergedTree = true)
        .assertTextEquals(
            string(
                R.string.association_events_card_details,
                string(R.string.events_today),
                string(R.string.events_time_range, "12:30", "14:30"),
                "Satellite",
            )
        )
    // Two months ahead, beyond the public 14 days, and without a place.
    composeTestRule
        .onNodeWithTag(MyEventsTags.details(cineClub.id), useUnmergedTree = true)
        .assertTextEquals(
            string(
                R.string.events_card_details,
                "Mon 2 Mar",
                string(R.string.events_time_range, "13:00", "15:00"),
            )
        )
    scrollTo(MyEventsTags.card(lastYear.id))
    composeTestRule.onNodeWithTag(MyEventsTags.PAST_HEADER).assertIsDisplayed()
    // Another year shows its year.
    composeTestRule
        .onNodeWithTag(MyEventsTags.details(lastYear.id), useUnmergedTree = true)
        .assertTextEquals(
            string(
                R.string.association_events_card_details,
                "Tue 2 Dec 2025",
                string(R.string.events_time_range, "13:00", "15:00"),
                "Satellite",
            )
        )
  }

  @Test
  fun onlyARunningEvent_isLive() {
    show(loaded())

    composeTestRule
        .onNodeWithTag(MyEventsTags.live(running.id), useUnmergedTree = true)
        .assertIsDisplayed()
    composeTestRule
        .onAllNodesWithTag(MyEventsTags.live(cineClub.id), useUnmergedTree = true)
        .assertCountEquals(0)
  }

  @Test
  fun withoutPastEvents_thereIsNoPastHeader() {
    show(loaded(past = emptyList()))

    composeTestRule.onAllNodesWithTag(MyEventsTags.PAST_HEADER).assertCountEquals(0)
  }

  @Test
  fun tappingACard_opensItsEvent() {
    show(loaded())

    composeTestRule.onNodeWithTag(MyEventsTags.card(cineClub.id)).performClick()

    assertEquals(listOf(cineClub.id), opened)
  }

  @Test
  fun anUnavailableEvent_showsNoDetail_andRemoveRemovesIt() {
    show(loaded(past = emptyList(), unavailableIds = listOf("gone")))

    scrollTo(MyEventsTags.unavailable("gone"))
    composeTestRule.onNodeWithText(string(R.string.my_events_unavailable_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(MyEventsTags.remove("gone")).performClick()

    assertEquals(listOf("gone"), removed)
  }

  @Test
  fun anEventBeingRemoved_cannotBeRemovedTwice() {
    show(loaded(unavailableIds = listOf("gone")).copy(removing = setOf("gone")))

    scrollTo(MyEventsTags.remove("gone"))
    composeTestRule.onNodeWithTag(MyEventsTags.remove("gone")).assertIsNotEnabled()
  }

  @Test
  fun offline_showsTheBanner_andTheEventsNotSaved() {
    show(loaded(past = emptyList(), missingCount = 2, isOffline = true))

    composeTestRule.onNodeWithTag(MyEventsTags.OFFLINE_BANNER).assertIsDisplayed()
    scrollTo(MyEventsTags.MISSING)
    composeTestRule
        .onNodeWithText(context.resources.getQuantityString(R.plurals.my_events_not_saved, 2, 2))
        .assertIsDisplayed()
  }

  @Test
  fun online_eventsThatDidNotLoad_areNotCalledUnsaved() {
    show(loaded(past = emptyList(), missingCount = 1))

    composeTestRule.onAllNodesWithTag(MyEventsTags.OFFLINE_BANNER).assertCountEquals(0)
    scrollTo(MyEventsTags.MISSING)
    composeTestRule
        .onNodeWithText(context.resources.getQuantityString(R.plurals.my_events_not_loaded, 1, 1))
        .assertIsDisplayed()
  }

  @Test
  fun loading_showsTheLoadingState() {
    show(MyEventsUiState(MyEventsList.Loading))

    composeTestRule.onNodeWithText(string(R.string.my_events_loading)).assertIsDisplayed()
  }

  @Test
  fun nothingPlanned_offersToBrowseUpcomingEvents() {
    show(MyEventsUiState(MyEventsList.Empty))

    composeTestRule.onNodeWithText(string(R.string.my_events_empty_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(MyEventsTags.BROWSE).performClick()

    assertEquals(1, browsed)
  }

  @Test
  fun offlineWithNothingSaved_offersRetry() {
    show(MyEventsUiState(MyEventsList.OfflineNothingSaved))

    composeTestRule.onNodeWithText(string(R.string.my_events_offline_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.error_retry).performClick()

    assertEquals(1, retries)
  }

  @Test
  fun anError_offersRetry() {
    show(MyEventsUiState(MyEventsList.Error))

    composeTestRule.onNodeWithText(string(R.string.my_events_error_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.error_retry).performClick()

    assertEquals(1, retries)
  }

  @Test
  fun aFailedRemove_isShownOnce_inASnackbar() {
    var state by mutableStateOf(loaded(message = MyEventsMessage.REMOVE_OFFLINE))
    composeTestRule.setContent {
      PolySocialTheme {
        MyEventsScreen(
            state,
            zone = lausanne,
            today = today,
            onRetry = {},
            onEventClick = {},
            onRemove = {},
            onBrowseUpcoming = {},
            onMessageShown = {
              messagesShown++
              state = state.copy(message = null)
            },
        )
      }
    }

    composeTestRule
        .onAllNodesWithText(string(R.string.my_events_remove_offline))
        .assertCountEquals(1)
    composeTestRule.mainClock.advanceTimeBy(10_000)
    composeTestRule.waitForIdle()

    assertEquals(1, messagesShown)
    composeTestRule
        .onAllNodesWithText(string(R.string.my_events_remove_offline))
        .assertCountEquals(0)
  }

  private fun loaded(message: MyEventsMessage) = loaded().copy(message = message)
}
