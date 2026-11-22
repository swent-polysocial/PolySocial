// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of the Directions button through
// EventDetailRoute, #391, following EventDetailRouteTest).
package com.polysocial.ui.event.detail

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import com.polysocial.model.directions.FakeDirectionsLauncher
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** [EventDetailRoute] gives the Directions button its [DirectionsViewModel] (#391). */
@RunWith(AndroidJUnit4::class)
class EventDetailDirectionsRouteTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)
  private val launcher = FakeDirectionsLauncher()
  private val event =
      validEvent(startTime = Instant.parse("2026-10-10T13:00:00Z"))
          .copy(id = "event-1", createdBy = "organizer", organizerIds = listOf("organizer"))

  private fun show() {
    val events = FakeEventRepository().apply { seed(event) }
    val viewModel =
        EventDetailViewModel(
            SavedStateHandle(mapOf("eventId" to "event-1")),
            events,
            object : NetworkMonitor {
              override fun isOnline() = true
            },
            FakeAuthRepository(AuthUser("student", "student@example.com", true)),
            clock,
            FakeAssociationNames(),
            FakeRegistrationRepository(currentUid = "student"),
            FakeGroupRepository(),
            FakePublicProfileRepository(),
            FakeReminderScheduler(clock),
        )
    val directions = DirectionsViewModel(launcher)
    composeTestRule.setContent {
      PolySocialTheme {
        EventDetailRoute(
            onBack = {},
            viewModel = viewModel,
            directions = directions,
        )
      }
    }
    repeat(2) {
      composeTestRule.waitForIdle()
      mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }
    composeTestRule.waitForIdle()
  }

  @Test
  fun tappingDirections_opensThemToTheEventsCoordinates() {
    show()

    composeTestRule.onNodeWithTag(EventDetailTags.DIRECTIONS).performClick()

    assertEquals(listOf(event.location), launcher.opened)
    composeTestRule
        .onNodeWithText(context.getString(R.string.event_detail_directions_unavailable))
        .assertDoesNotExist()
  }

  @Test
  fun withNoApp_tappingDirectionsShowsTheMessageInsteadOfCrashing() {
    launcher.canOpen = false
    show()

    composeTestRule.onNodeWithTag(EventDetailTags.DIRECTIONS).performClick()
    composeTestRule.waitForIdle()

    composeTestRule
        .onNodeWithText(context.getString(R.string.event_detail_directions_unavailable))
        .assertIsDisplayed()
  }
}
