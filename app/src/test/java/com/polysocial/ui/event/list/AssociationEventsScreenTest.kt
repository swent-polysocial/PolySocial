// Contributors: Claude (wrote these screen tests for the association's Events tab, #132).
package com.polysocial.ui.event.list

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.Event
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.event.withCreator
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
class AssociationEventsScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val lausanne = ZoneId.of("Europe/Zurich")

  /** TEST_NOW is 13:00 on 1 January 2026 in Lausanne. */
  private val today = LocalDate.of(2026, 1, 1)

  private fun own(id: String, title: String, startsIn: Duration, isPrivate: Boolean = false) =
      validEvent(title = title, startTime = TEST_NOW.plus(startsIn))
          .copy(id = id, isPrivate = isPrivate, locationName = "Satellite")
          .withCreator("association", isVerifiedAssociation = true)

  /** Started 30 minutes ago and ends in 90 minutes. */
  private val running = own("running", "Balélec warm-up", Duration.ofMinutes(-30))
  private val cineClub = own("cineClub", "Ciné-club: Dune", Duration.ofDays(1))
  private val study = own("study", "Committee meeting", Duration.ofDays(3), isPrivate = true)
  private val lastWeek = own("lastWeek", "Welcome apéro", Duration.ofDays(-7))

  private var retries = 0
  private val opened = mutableListOf<String>()

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  private fun show(state: AssociationEventsState) {
    composeTestRule.setContent {
      PolySocialTheme {
        AssociationEventsScreen(
            state,
            zone = lausanne,
            today = today,
            onRetry = { retries++ },
            onEventClick = { opened += it },
        )
      }
    }
  }

  private fun loaded(
      upcoming: List<Event> = listOf(running, cineClub, study),
      past: List<Event> = listOf(lastWeek),
      isOffline: Boolean = false,
  ) = AssociationEventsState.Events(upcoming, past, TEST_NOW, isOffline)

  @Test
  fun upcoming_isSelectedFirstAndShowsTheUpcomingEventsWithTheCounts() {
    show(loaded())

    composeTestRule
        .onNodeWithTag(AssociationEventsTags.UPCOMING_TAB)
        .assertIsSelected()
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.association_events_upcoming, 3))
        .assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.association_events_past, 1)).assertIsDisplayed()
    for (event in listOf(running, cineClub, study)) {
      composeTestRule.onNodeWithTag(AssociationEventsTags.card(event.id)).assertIsDisplayed()
    }
    composeTestRule.onAllNodesWithTag(AssociationEventsTags.card(lastWeek.id)).assertCountEquals(0)
    composeTestRule.onNodeWithTag(C.Tag.screen_events).assertIsDisplayed()
  }

  @Test
  fun aCard_showsTheDayTimeAndPlace() {
    show(loaded())

    composeTestRule.onNodeWithText("Today · 12:30 · Satellite").assertIsDisplayed()
    composeTestRule.onNodeWithText("Tomorrow · 13:00 · Satellite").assertIsDisplayed()
  }

  @Test
  fun aCardWithoutAPlace_showsTheDayAndTime() {
    show(loaded(upcoming = listOf(cineClub.copy(locationName = ""))))

    composeTestRule.onNodeWithText("Tomorrow · 13:00").assertIsDisplayed()
  }

  @Test
  fun theChip_isLiveWhileTheEventRunsOtherwiseItsVisibility() {
    show(loaded())

    composeTestRule
        .onNodeWithTag(AssociationEventsTags.chip(running.id, "LIVE"), useUnmergedTree = true)
        .assertTextEquals(string(R.string.association_events_live))
    composeTestRule
        .onNodeWithTag(AssociationEventsTags.chip(cineClub.id, "PUBLIC"), useUnmergedTree = true)
        .assertTextEquals(string(R.string.association_events_public))
    composeTestRule
        .onNodeWithTag(AssociationEventsTags.chip(study.id, "PRIVATE"), useUnmergedTree = true)
        .assertTextEquals(string(R.string.association_events_private))
  }

  @Test
  fun past_showsThePastEvents() {
    show(loaded())

    composeTestRule.onNodeWithTag(AssociationEventsTags.PAST_TAB).performClick()

    composeTestRule.onNodeWithTag(AssociationEventsTags.PAST_TAB).assertIsSelected()
    composeTestRule.onNodeWithTag(AssociationEventsTags.UPCOMING_TAB).assertIsNotSelected()
    composeTestRule.onNodeWithTag(AssociationEventsTags.card(lastWeek.id)).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(AssociationEventsTags.card(running.id)).assertCountEquals(0)
  }

  @Test
  fun drafts_showsANotAvailableYetSnackbarAndKeepsTheTab() {
    show(loaded())
    val message = string(R.string.association_events_drafts_unavailable)
    composeTestRule.onAllNodesWithText(message).assertCountEquals(0)

    composeTestRule.onNodeWithTag(AssociationEventsTags.DRAFTS_TAB).performClick()

    composeTestRule.onNodeWithText(message).assertIsDisplayed()
    composeTestRule.onNodeWithTag(AssociationEventsTags.DRAFTS_TAB).assertIsNotSelected()
    composeTestRule.onNodeWithTag(AssociationEventsTags.UPCOMING_TAB).assertIsSelected()
  }

  @Test
  fun tappingACard_opensThatEvent() {
    show(loaded())

    composeTestRule.onNodeWithTag(AssociationEventsTags.card(cineClub.id)).performClick()

    assertEquals(listOf(cineClub.id), opened)
  }

  @Test
  fun theSelectedTab_survivesRecreation() {
    val restoration = StateRestorationTester(composeTestRule)
    restoration.setContent {
      PolySocialTheme {
        AssociationEventsScreen(loaded(), lausanne, today, onRetry = {}, onEventClick = {})
      }
    }
    composeTestRule.onNodeWithTag(AssociationEventsTags.PAST_TAB).performClick()

    restoration.emulateSavedInstanceStateRestore()

    composeTestRule.onNodeWithTag(AssociationEventsTags.PAST_TAB).assertIsSelected()
  }

  @Test
  fun noUpcomingOrPastEvents_showTheirEmptyStates() {
    show(loaded(upcoming = emptyList(), past = emptyList()))

    composeTestRule
        .onNodeWithText(string(R.string.association_events_no_upcoming_title))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(AssociationEventsTags.PAST_TAB).performClick()
    composeTestRule
        .onNodeWithText(string(R.string.association_events_no_past_title))
        .assertIsDisplayed()
  }

  @Test
  fun cachedEventsOffline_showTheOfflineBanner() {
    show(loaded(isOffline = true))

    composeTestRule.onNodeWithTag(EventsTags.OFFLINE_BANNER).assertIsDisplayed()
  }

  @Test
  fun loading_showsTheLoadingState() {
    show(AssociationEventsState.Loading)

    composeTestRule.onNodeWithText(string(R.string.loading_events)).assertIsDisplayed()
  }

  @Test
  fun anError_offersRetry() {
    show(AssociationEventsState.Error)

    composeTestRule.onNodeWithTag(EventsTags.ERROR).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.RETRY).performClick()

    assertEquals(1, retries)
  }
}
