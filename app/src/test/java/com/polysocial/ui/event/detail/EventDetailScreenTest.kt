// Contributors: Claude (wrote these tests of the event detail screen, #112; Retry, #194).
package com.polysocial.ui.event.detail

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventDetailScreenTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun string(id: Int) = context.getString(id)

  private var backs = 0

  private var retries = 0

  /** A student's party, with every optional field filled in. */
  private val party =
      validEvent(title = "Balélec warm-up", capacity = 200)
          .copy(
              id = "party",
              category = EventCategory.PARTY,
              description = "Music and snacks before the festival.",
              locationName = "Satellite",
          )

  /** Friday 2 October 2026, 20:00, with the given end. */
  private fun details(
      event: Event = party,
      endTime: LocalTime? = null,
      endDayOffset: Int = 0,
  ) =
      EventDetails(
          event = event,
          date = LocalDate.of(2026, 10, 2),
          startTime = LocalTime.of(20, 0),
          endTime = endTime,
          endDayOffset = endDayOffset,
      )

  private fun show(state: EventDetailUiState) {
    composeTestRule.setContent {
      PolySocialTheme { EventDetailScreen(state, onBack = { backs++ }, onRetry = { retries++ }) }
    }
  }

  private fun showLoaded(details: EventDetails = details(), isOffline: Boolean = false) =
      show(EventDetailUiState.Loaded(details, isOffline))

  /**
   * Lets the screen subscribe to the ViewModel and the ViewModel read the repository, alternating
   * Compose's idling with the test Main dispatcher until both are done.
   */
  private fun settle() {
    repeat(2) {
      composeTestRule.waitForIdle()
      mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }
    composeTestRule.waitForIdle()
  }

  private fun assertAbsent(tag: String) =
      composeTestRule.onAllNodesWithTag(tag).assertCountEquals(0)

  @Test
  fun aLoadedEvent_showsItsFields() {
    showLoaded()

    composeTestRule.onNodeWithTag(EventDetailTags.SCREEN).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(EventDetailTags.CATEGORY)
        .assertTextEquals(string(R.string.event_category_party))
    composeTestRule.onNodeWithTag(EventDetailTags.TITLE).assertTextEquals("Balélec warm-up")
    composeTestRule.onNodeWithTag(EventDetailTags.DATE).assertTextEquals("Friday 2 October")
    composeTestRule.onNodeWithTag(EventDetailTags.LOCATION).assertTextEquals("Satellite")
    composeTestRule.onNodeWithTag(EventDetailTags.CAPACITY).assertTextEquals("Up to 200 people")
    composeTestRule
        .onNodeWithTag(EventDetailTags.DESCRIPTION)
        .assertTextEquals("Music and snacks before the festival.")
    composeTestRule.onNodeWithTag(EventDetailTags.FIND_GROUP).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.event_detail_find_group)).assertExists()
  }

  @Test
  fun theCategoryPill_namesTheEventsCategory() {
    showLoaded(details(party.copy(category = EventCategory.SPORTS)))

    composeTestRule
        .onNodeWithTag(EventDetailTags.CATEGORY)
        .assertTextEquals(string(R.string.event_category_sports))
  }

  @Test
  fun anAssociationEvent_showsTheVerifiedBadge() {
    showLoaded(details(party.copy(isAssociationEvent = true)))

    composeTestRule
        .onNodeWithTag(EventDetailTags.VERIFIED_BADGE)
        .assertIsDisplayed()
        .assertTextEquals(string(R.string.event_detail_verified))
  }

  @Test
  fun aStudentsEvent_hasNoVerifiedBadge() {
    showLoaded(details(party.copy(isAssociationEvent = false)))

    assertAbsent(EventDetailTags.VERIFIED_BADGE)
    composeTestRule.onAllNodesWithText(string(R.string.event_detail_verified)).assertCountEquals(0)
  }

  @Test
  fun withoutAnEndTime_onlyTheStartTimeShows() {
    showLoaded(details(endTime = null))

    composeTestRule.onNodeWithTag(EventDetailTags.TIME).assertTextEquals("20:00")
  }

  @Test
  fun anEndOnTheSameDay_showsTheTimeRange() {
    showLoaded(details(endTime = LocalTime.of(23, 0)))

    composeTestRule.onNodeWithTag(EventDetailTags.TIME).assertTextEquals("20:00 – 23:00")
  }

  @Test
  fun anOvernightEnd_isMarkedPlusOneDay() {
    showLoaded(details(endTime = LocalTime.of(1, 0), endDayOffset = 1))

    composeTestRule.onNodeWithTag(EventDetailTags.TIME).assertTextEquals("20:00 – 01:00 (+1 day)")
  }

  @Test
  fun anEndTwoDaysLater_isMarkedPlusTwoDays() {
    showLoaded(details(endTime = LocalTime.of(18, 30), endDayOffset = 2))

    composeTestRule.onNodeWithTag(EventDetailTags.TIME).assertTextEquals("20:00 – 18:30 (+2 days)")
  }

  @Test
  fun aBlankPlaceName_showsPlaceOnTheMap() {
    showLoaded(details(party.copy(locationName = "")))

    composeTestRule
        .onNodeWithTag(EventDetailTags.LOCATION)
        .assertTextEquals(string(R.string.event_detail_unnamed_location))
  }

  @Test
  fun noCapacityAndNoDescription_leaveOutTheirRows() {
    showLoaded(details(party.copy(capacity = null, description = "  ")))

    assertAbsent(EventDetailTags.CAPACITY)
    assertAbsent(EventDetailTags.DESCRIPTION)
    composeTestRule.onAllNodes(hasText("Up to", substring = true)).assertCountEquals(0)
  }

  @Test
  fun onlineEvent_hasNoOfflineBanner() {
    showLoaded(isOffline = false)

    assertAbsent(EventDetailTags.OFFLINE_BANNER)
  }

  @Test
  fun anEventFromTheCacheWhileOffline_showsTheOfflineBanner() {
    showLoaded(isOffline = true)

    composeTestRule.onNodeWithTag(EventDetailTags.OFFLINE_BANNER).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.event_detail_offline)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventDetailTags.TITLE).assertTextEquals("Balélec warm-up")
  }

  @Test
  fun findAGroup_showsANotAvailableYetSnackbar() {
    showLoaded()
    val message = string(R.string.event_detail_find_group_unavailable)
    composeTestRule.onAllNodesWithText(message).assertCountEquals(0)

    composeTestRule.onNodeWithTag(EventDetailTags.FIND_GROUP).performClick()

    composeTestRule.onNodeWithText(message).assertIsDisplayed()
  }

  @Test
  fun back_onALoadedEvent_callsOnBack() {
    showLoaded()

    composeTestRule.onNodeWithTag(EventDetailTags.BACK).assertIsDisplayed().performClick()

    assertEquals(1, backs)
  }

  @Test
  fun actionsNotBuiltYet_areLeftOut() {
    showLoaded(details(party.copy(isAssociationEvent = true)))

    listOf("I'm going", "Remind", "Share", "⋯", "More").forEach { text ->
      composeTestRule
          .onAllNodes(
              SemanticsMatcher("text or description contains \"$text\"") { node ->
                hasText(text, substring = true, ignoreCase = true).matches(node) ||
                    hasContentDescription(text, substring = true, ignoreCase = true).matches(node)
              },
              useUnmergedTree = true,
          )
          .assertCountEquals(0)
    }
  }

  @Test
  fun loading_showsTheLoadingStateAndAWorkingBack() {
    show(EventDetailUiState.Loading)

    composeTestRule.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.event_detail_loading)).assertIsDisplayed()
    assertAbsent(EventDetailTags.TITLE)
    assertAbsent(EventDetailTags.FIND_GROUP)

    composeTestRule.onNodeWithTag(EventDetailTags.BACK).performClick()

    assertEquals(1, backs)
  }

  @Test
  fun notFound_showsItsMessage_andBothBackButtonsCallOnBack() {
    show(EventDetailUiState.NotFound)

    composeTestRule.onNodeWithTag(EventDetailTags.NOT_FOUND).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.event_detail_not_found_title))
        .assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.event_detail_not_found_body)).assertIsDisplayed()
    assertAbsent(EventDetailTags.FIND_GROUP)
    assertAbsent(EventDetailTags.OFFLINE_NOT_SAVED)

    composeTestRule.onNodeWithTag(EventDetailTags.NOT_FOUND_BACK).assertIsDisplayed().performClick()
    assertEquals(1, backs)
    composeTestRule.onNodeWithTag(EventDetailTags.BACK).performClick()
    assertEquals(2, backs)
  }

  @Test
  fun offlineNotSaved_showsItsMessage_withoutTheBackButtonInTheMessage() {
    show(EventDetailUiState.OfflineNotSaved)

    composeTestRule.onNodeWithTag(EventDetailTags.OFFLINE_NOT_SAVED).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.event_detail_offline_not_saved_title))
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.event_detail_offline_not_saved_body))
        .assertIsDisplayed()
    assertAbsent(EventDetailTags.NOT_FOUND_BACK)
    assertAbsent(EventDetailTags.NOT_FOUND)

    composeTestRule.onNodeWithTag(EventDetailTags.BACK).performClick()

    assertEquals(1, backs)
  }

  @Test
  fun offlineNotSaved_retryCallsOnRetry() {
    show(EventDetailUiState.OfflineNotSaved)

    composeTestRule
        .onNodeWithTag(EventDetailTags.RETRY)
        .assertIsDisplayed()
        .assertTextEquals(string(R.string.retry))
        .performClick()

    assertEquals(1, retries)
    assertEquals(0, backs)
  }

  @Test
  fun notFound_hasNoRetry() {
    show(EventDetailUiState.NotFound)

    assertAbsent(EventDetailTags.RETRY)
  }

  @Test
  fun theRoute_whenTheServerDoesntAnswer_showsRetry_whichLoadsTheEvent() {
    val repository = FakeEventRepository()
    // Online, but the event isn't cached and the server never answers this read.
    repository.readFromCache = true
    val network =
        object : NetworkMonitor {
          override fun isOnline() = true
        }
    val clock = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneId.of("Europe/Zurich"))
    val viewModel =
        EventDetailViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to "party")),
            repository,
            network,
            clock,
        )
    composeTestRule.setContent {
      PolySocialTheme { EventDetailRoute(onBack = { backs++ }, viewModel = viewModel) }
    }
    // Lets the server timeout pass in virtual time.
    settle()
    composeTestRule.onNodeWithTag(EventDetailTags.OFFLINE_NOT_SAVED).assertIsDisplayed()

    // The server is reachable again: a new read gets the event.
    repository.readFromCache = false
    repository.events += party
    composeTestRule.onNodeWithTag(EventDetailTags.RETRY).performClick()
    settle()

    composeTestRule.onNodeWithTag(EventDetailTags.TITLE).assertTextEquals("Balélec warm-up")
  }

  @Test
  fun theRoute_showsTheRepositorysEvent_thenNotFoundOnceItIsDeleted() {
    val repository = FakeEventRepository()
    // 18:00 to 23:00 UTC is 20:00 to 01:00 the next day in Lausanne (summer time).
    repository.seed(
        party.copy(
            isAssociationEvent = true,
            startTime = Instant.parse("2026-10-02T18:00:00Z"),
            endTime = Instant.parse("2026-10-02T23:00:00Z"),
        )
    )
    val network =
        object : NetworkMonitor {
          override fun isOnline() = true
        }
    val clock = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneId.of("Europe/Zurich"))
    val viewModel =
        EventDetailViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to "party")),
            repository,
            network,
            clock,
        )
    composeTestRule.setContent {
      PolySocialTheme { EventDetailRoute(onBack = { backs++ }, viewModel = viewModel) }
    }
    composeTestRule.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    settle()

    composeTestRule.onNodeWithTag(EventDetailTags.TITLE).assertTextEquals("Balélec warm-up")
    composeTestRule.onNodeWithTag(EventDetailTags.DATE).assertTextEquals("Friday 2 October")
    composeTestRule.onNodeWithTag(EventDetailTags.TIME).assertTextEquals("20:00 – 01:00 (+1 day)")
    composeTestRule.onNodeWithTag(EventDetailTags.LOCATION).assertTextEquals("Satellite")
    composeTestRule.onNodeWithTag(EventDetailTags.VERIFIED_BADGE).assertIsDisplayed()

    repository.remove("party")
    settle()

    composeTestRule.onNodeWithTag(EventDetailTags.NOT_FOUND).assertIsDisplayed()
    assertAbsent(EventDetailTags.TITLE)
    composeTestRule.onNodeWithTag(EventDetailTags.NOT_FOUND_BACK).performClick()
    assertEquals(1, backs)
  }
}
