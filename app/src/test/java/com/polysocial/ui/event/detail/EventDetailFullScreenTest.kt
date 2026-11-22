// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests of "Event full" on the event detail
// screen, #407).
package com.polysocial.ui.event.detail

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
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
import com.polysocial.model.event.Event
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.registration.RegisterResult
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Event full" on the event detail (#407), as in the Figma "Event detail · full" frame (`558:60`):
 * first the screen alone, then with its ViewModel after a registration the server refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class EventDetailFullScreenTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun string(id: Int) = context.getString(id)

  private val event =
      validEvent(
              startTime = Instant.parse("2026-10-10T13:00:00Z"),
              endTime = Instant.parse("2026-10-10T17:00:00Z"),
              capacity = 200,
          )
          .copy(
              id = EVENT_ID,
              createdBy = "organizer",
              organizerIds = listOf("organizer"),
              attendeeCount = 200,
          )

  private fun full(
      attendance: Attendance = Attendance.NOT_GOING,
      isOrganizer: Boolean = false,
      hasEnded: Boolean = false,
      event: Event = this.event,
  ) =
      EventDetailUiState.Loaded(
          EventDetails(
              event,
              LocalDate.of(2026, 10, 10),
              LocalTime.of(13, 0),
              LocalTime.of(17, 0),
              0,
          ),
          isOffline = false,
          isOrganizer = isOrganizer,
          hasEnded = hasEnded,
          attendance = attendance,
          isFull = true,
      )

  private val calls = mutableListOf<String>()

  private fun show(state: EventDetailUiState.Loaded) {
    composeTestRule.setContent {
      PolySocialTheme {
        EventDetailScreen(
            state,
            onBack = {},
            onRetry = {},
            attendance = EventAttendanceActions(onImGoing = { calls += "going" }),
        )
      }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  @Test
  fun notGoing_showsEventFullAndTheNote_insteadOfImGoing() {
    show(full())

    node(EventDetailTags.EVENT_FULL).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.event_detail_event_full)).assertIsDisplayed()
    node(EventDetailTags.FULL_NOTE).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.event_detail_full)).assertIsDisplayed()
    composeTestRule.onNodeWithText("All 200 places are taken.").assertIsDisplayed()
    node(EventDetailTags.IM_GOING).assertDoesNotExist()
  }

  @Test
  fun eventFull_cantBeTapped_andFindAGroupStaysGreyedOut() {
    show(full())

    // A status, like "✓ Going", not a disabled button.
    node(EventDetailTags.EVENT_FULL).assert(!hasClickAction())
    composeTestRule.onNodeWithText(string(R.string.event_detail_event_full)).performClick()
    assertTrue(calls.isEmpty())
    // Not going, so no group to find yet (#244).
    node(EventDetailTags.FIND_GROUP).assertIsDisplayed().assertIsNotEnabled()
  }

  @Test
  fun eventFull_takesImGoingsWidth() {
    show(full())

    val bounds = node(EventDetailTags.EVENT_FULL).fetchSemanticsNode().boundsInRoot
    val density = context.resources.displayMetrics.density
    assertEquals(137.5f, bounds.width / density, 0.5f)
  }

  @Test
  fun withoutACapacityOnTheEventShown_theNoteLeavesTheNumberOut() {
    // The server refused the registration as full, but the event shown has no capacity yet.
    show(full(event = event.copy(capacity = null)))

    composeTestRule
        .onNodeWithText(string(R.string.event_detail_all_places_taken_no_count))
        .assertIsDisplayed()
  }

  @Test
  fun aStudentGoing_keepsGoingAndCancel() {
    show(full(Attendance.GOING))

    node(EventDetailTags.GOING).assertIsDisplayed()
    node(EventDetailTags.CANCEL_REGISTRATION).assertIsDisplayed()
    node(EventDetailTags.EVENT_FULL).assertDoesNotExist()
    node(EventDetailTags.FULL_NOTE).assertDoesNotExist()
  }

  @Test
  fun anOrganizer_seesNoChange() {
    show(full(isOrganizer = true))

    node(EventDetailTags.FIND_GROUP).assertIsDisplayed()
    node(EventDetailTags.EVENT_FULL).assertDoesNotExist()
    node(EventDetailTags.FULL_NOTE).assertDoesNotExist()
  }

  @Test
  fun anEndedEvent_hasNoActionBar_soNoEventFull() {
    show(full(hasEnded = true))

    node(EventDetailTags.EVENT_FULL).assertDoesNotExist()
    node(EventDetailTags.FULL_NOTE).assertDoesNotExist()
  }

  @Test
  fun route_aRegistrationRefusedAsFull_showsEventFull_withoutASnackbar() {
    val registrations =
        FakeRegistrationRepository(currentUid = STUDENT).apply {
          readableEventIds += EVENT_ID
          registerFailure = RegisterResult.Full
        }
    val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)
    val viewModel =
        EventDetailViewModel(
            SavedStateHandle(mapOf("eventId" to EVENT_ID)),
            FakeEventRepository().apply { seed(event.copy(attendeeCount = 199)) },
            object : NetworkMonitor {
              override fun isOnline() = true
            },
            FakeAuthRepository(AuthUser(STUDENT, "student@example.com", true)),
            clock,
            FakeAssociationNames(),
            registrations,
            FakeGroupRepository(),
            FakePublicProfileRepository(),
            FakeReminderScheduler(clock),
        )
    composeTestRule.setContent {
      PolySocialTheme { EventDetailRoute(onBack = {}, viewModel = viewModel) }
    }
    settle()

    node(EventDetailTags.IM_GOING).performClick()
    settle()

    node(EventDetailTags.EVENT_FULL).assertIsDisplayed()
    node(EventDetailTags.IM_GOING).assertDoesNotExist()
    composeTestRule
        .onNodeWithText(string(R.string.event_detail_attendance_failed))
        .assertDoesNotExist()
  }

  /** Runs the ViewModel's coroutines, which wait on the test's main dispatcher. */
  private fun settle() {
    repeat(2) {
      composeTestRule.waitForIdle()
      mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }
    composeTestRule.waitForIdle()
  }

  private companion object {
    const val EVENT_ID = "event-1"
    const val STUDENT = "student-1"
  }
}
