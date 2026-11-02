// Contributors: Claude (wrote these tests of the association's manage event, #131).
package com.polysocial.ui.event.detail

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
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
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.event.withCreator
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.AttendeeCountResult
import com.polysocial.model.registration.FakeRegistrationRepository
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
class ManageEventScreenTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun string(id: Int) = context.getString(id)

  private val noCount by lazy { string(R.string.manage_event_no_count) }

  private var backs = 0
  private var edits = 0
  private var cancelTaps = 0
  private var goingRetries = 0

  /** A verified association's public party, organized by the signed-in association. */
  private val party =
      validEvent(title = "Balélec warm-up")
          .copy(id = "party", category = EventCategory.PARTY, locationName = "Satellite")
          .withCreator("association", isVerifiedAssociation = true)

  /** Friday 2 October 2026, 20:00 to 01:00 the next day. */
  private fun managed(
      going: GoingCount = GoingCount.Count(86),
      isLive: Boolean = false,
      isOffline: Boolean = false,
      isPrivate: Boolean = false,
      locationName: String = "Satellite",
  ) =
      EventDetailUiState.Loaded(
          EventDetails(
              event = party.copy(isPrivate = isPrivate, locationName = locationName),
              date = LocalDate.of(2026, 10, 2),
              startTime = LocalTime.of(20, 0),
              endTime = LocalTime.of(1, 0),
              endDayOffset = 1,
          ),
          isOffline = isOffline,
          isOrganizer = true,
          going = going,
          isLive = isLive,
      )

  private fun show(state: EventDetailUiState.Loaded) {
    composeTestRule.setContent {
      PolySocialTheme {
        EventDetailScreen(
            state,
            onBack = { backs++ },
            onRetry = {},
            organizer =
                EventOrganizerActions(onEdit = { edits++ }, onCancelEvent = { cancelTaps++ }),
            onRetryGoing = { goingRetries++ },
        )
      }
    }
  }

  /** Lets the screen and the ViewModel finish, like EventDetailScreenTest's settle. */
  private fun settle() {
    repeat(2) {
      composeTestRule.waitForIdle()
      mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }
    composeTestRule.waitForIdle()
  }

  /** The number on the Going tile, which the tile merges into itself while it is tappable. */
  private fun goingCount() =
      composeTestRule.onNodeWithTag(ManageEventTags.GOING_COUNT, useUnmergedTree = true)

  private fun assertAbsent(tag: String) =
      composeTestRule.onAllNodesWithTag(tag).assertCountEquals(0)

  @Test
  fun anAssociationsOwnEvent_showsTheManageScreen_notTheStudentsDetail() {
    show(managed())

    composeTestRule.onNodeWithTag(ManageEventTags.SCREEN).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.manage_event_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(ManageEventTags.TITLE).assertTextEquals("Balélec warm-up")
    composeTestRule
        .onNodeWithTag(ManageEventTags.WHEN_WHERE)
        .assertTextEquals("Fri 2 Oct · 20:00 – 01:00 (+1 day) · Satellite")
    composeTestRule
        .onNodeWithTag(ManageEventTags.VISIBILITY)
        .assertTextEquals(string(R.string.association_events_public))
    goingCount().assertTextEquals("86")
    composeTestRule.onNodeWithText(string(R.string.manage_event_counts_only)).assertIsDisplayed()
    assertAbsent(ManageEventTags.LIVE)
    assertAbsent(ManageEventTags.OFFLINE_BANNER)
    // None of the students' detail: no Find a group, no "Organized by you".
    assertAbsent(EventDetailTags.FIND_GROUP)
    assertAbsent(EventDetailTags.ORGANIZED_BY_YOU)
  }

  @Test
  fun aRunningPrivateEventWithoutAPlace_showsLiveNowPrivateAndOnlyItsTimes() {
    show(managed(isLive = true, isPrivate = true, locationName = ""))

    composeTestRule
        .onNodeWithTag(ManageEventTags.LIVE)
        .assertTextEquals(string(R.string.manage_event_live))
    composeTestRule
        .onNodeWithTag(ManageEventTags.VISIBILITY)
        .assertTextEquals(string(R.string.association_events_private))
    composeTestRule
        .onNodeWithTag(ManageEventTags.WHEN_WHERE)
        .assertTextEquals("Fri 2 Oct · 20:00 – 01:00 (+1 day)")
  }

  @Test
  fun backEditAndCancelEvent_callTheirActions() {
    show(managed())

    composeTestRule.onNodeWithTag(ManageEventTags.BACK).performClick()
    composeTestRule.onNodeWithTag(ManageEventTags.EDIT).performClick()
    composeTestRule.onNodeWithTag(ManageEventTags.CANCEL_EVENT).performClick()

    assertEquals(1, backs)
    assertEquals(1, edits)
    assertEquals(1, cancelTaps)
  }

  @Test
  fun theCountsNotBuiltYet_theQrAndTheAlbum_sayTheyArentAvailable() {
    show(managed())
    val notAvailable = string(R.string.manage_event_not_available)

    listOf(
            ManageEventTags.LOOKING_FOR_GROUP,
            ManageEventTags.GROUPS_FORMED,
            ManageEventTags.CHECKED_IN,
            ManageEventTags.SHOW_FULL_SCREEN,
            ManageEventTags.DOWNLOAD_PDF,
            ManageEventTags.ALBUM,
        )
        .forEach { tag ->
          composeTestRule.onNodeWithTag(tag).performClick()
          composeTestRule.waitForIdle()
          // One message at a time: a new tap replaces the previous one.
          composeTestRule.onAllNodesWithText(notAvailable).assertCountEquals(1)
        }
    // The three counts that aren't built show no number.
    composeTestRule.onAllNodesWithText(noCount).assertCountEquals(3)
  }

  @Test
  fun aCountStillLoading_showsNoNumber_andTappingItDoesNothing() {
    show(managed(going = GoingCount.Loading))

    goingCount().assertTextEquals(noCount)
    composeTestRule.onNodeWithTag(ManageEventTags.GOING).performClick()

    assertEquals(0, goingRetries)
  }

  @Test
  fun aCountThatCouldntBeRead_saysSo_andTappingItCountsAgain() {
    show(managed(going = GoingCount.Unavailable))

    composeTestRule
        .onNode(hasContentDescription(string(R.string.manage_event_count_unavailable)))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(ManageEventTags.GOING).performClick()

    assertEquals(1, goingRetries)
  }

  @Test
  fun offline_showsTheOfflineBannerAboveCancelEvent() {
    show(managed(isOffline = true))

    composeTestRule.onNodeWithTag(ManageEventTags.OFFLINE_BANNER).assertIsDisplayed()
    composeTestRule.onNodeWithTag(ManageEventTags.CANCEL_EVENT).assertIsDisplayed()
  }

  // Through the route and the ViewModel

  private val events = FakeEventRepository()
  private val registrations = FakeRegistrationRepository()

  /** The route for [party], signed in as its association, against the fake repositories. */
  private fun showRoute(onCancelled: () -> Unit = {}) {
    events.currentUid = "association"
    events.seed(party)
    val viewModel =
        EventDetailViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to "party")),
            events,
            object : NetworkMonitor {
              override fun isOnline() = true
            },
            FakeAuthRepository(AuthUser("association", "association@example.com", true)),
            Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneId.of("Europe/Zurich")),
            FakeAssociationNames(),
            registrations,
        )
    composeTestRule.setContent {
      PolySocialTheme {
        EventDetailRoute(onBack = {}, onCancelled = onCancelled, viewModel = viewModel)
      }
    }
    settle()
  }

  @Test
  fun theGoingCount_comesFromTheRegistrations() {
    listOf("s1", "s2").forEach { registrations.seedRegistration("party", it) }

    showRoute()

    goingCount().assertTextEquals("2")
  }

  @Test
  fun aFailedCount_canBeTriedAgainByTappingIt() {
    registrations.seedRegistration("party", "s1")
    registrations.countFailure = AttendeeCountResult.NetworkError
    showRoute()
    goingCount().assertTextEquals(noCount)

    registrations.countFailure = null
    composeTestRule.onNodeWithTag(ManageEventTags.GOING).performClick()
    settle()

    goingCount().assertTextEquals("1")
  }

  @Test
  fun confirmingCancelEvent_deletesTheEventAndLeaves() {
    var cancelled = 0
    showRoute(onCancelled = { cancelled++ })

    composeTestRule.onNodeWithTag(ManageEventTags.CANCEL_EVENT).performClick()
    settle()
    composeTestRule.onNodeWithTag(EventDetailTags.CANCEL_SHEET).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventDetailTags.CONFIRM_CANCEL).performClick()
    settle()

    assertEquals(listOf("party"), events.deleteRequests)
    assertEquals(1, cancelled)
  }
}
