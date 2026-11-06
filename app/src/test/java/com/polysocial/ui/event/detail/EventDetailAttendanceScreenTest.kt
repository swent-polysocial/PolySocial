// Contributors: Claude Opus 5.5 (wrote these tests of "I'm going" on the event detail screen,
// #122).
package com.polysocial.ui.event.detail

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.registration.RegisterResult
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "I'm going", "✓ Going" and Cancel registration on the event detail (#122), as in the Figma "Event
 * detail" and "Event detail · registered" frames: first the screen alone, then with its ViewModel
 * and a fake repository.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class EventDetailAttendanceScreenTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun string(id: Int) = context.getString(id)

  private val event =
      validEvent(
              startTime = Instant.parse("2026-10-10T13:00:00Z"),
              endTime = Instant.parse("2026-10-10T17:00:00Z"),
          )
          .copy(id = EVENT_ID, createdBy = "organizer", organizerIds = listOf("organizer"))

  private fun loaded(
      attendance: Attendance = Attendance.NOT_GOING,
      saving: Boolean = false,
      isOrganizer: Boolean = false,
      hasEnded: Boolean = false,
      yourGroup: YourGroup? = null,
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
          yourGroup = yourGroup,
          attendance = attendance,
          isSavingAttendance = saving,
      )

  private val calls = mutableListOf<String>()

  private val actions =
      EventAttendanceActions(
          onImGoing = { calls += "going" },
          onCancelRegistration = { calls += "cancel" },
          onMessageShown = { calls += "shown ${it.message}" },
      )

  private fun show(state: EventDetailUiState.Loaded, feedback: AttendanceFeedback? = null) {
    composeTestRule.setContent {
      PolySocialTheme {
        EventDetailScreen(
            state,
            onBack = {},
            onRetry = {},
            attendanceFeedback = feedback,
            attendance = actions,
        )
      }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  @Test
  fun notGoing_showsImGoingNextToFindAGroup_andTappingItSaysGoing() {
    show(loaded())

    node(EventDetailTags.IM_GOING).assertIsDisplayed().assertIsEnabled()
    composeTestRule.onNodeWithText(string(R.string.event_detail_im_going)).assertIsDisplayed()
    node(EventDetailTags.FIND_GROUP).assertIsDisplayed()
    node(EventDetailTags.REGISTERED_NOTE).assertDoesNotExist()

    node(EventDetailTags.IM_GOING).performClick()
    assertEquals(listOf("going"), calls)
  }

  @Test
  fun theTwoButtonsTakeFigmasWidths() {
    show(loaded())

    val going = node(EventDetailTags.IM_GOING).fetchSemanticsNode().boundsInRoot
    val findGroup = node(EventDetailTags.FIND_GROUP).fetchSemanticsNode().boundsInRoot
    // Figma: 137.5 and 172.5 dp, 10 dp apart, on a 360 dp screen with 20 dp margins.
    val density = context.resources.displayMetrics.density
    assertEquals(137.5f, going.width / density, 0.5f)
    assertEquals(172.5f, findGroup.width / density, 0.5f)
    assertEquals(10f, (findGroup.left - going.right) / density, 0.5f)
  }

  @Test
  fun going_showsGoingAndTheNote_whoseLinkCancels() {
    show(loaded(Attendance.GOING))

    node(EventDetailTags.GOING).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.event_detail_going)).assertIsDisplayed()
    node(EventDetailTags.IM_GOING).assertDoesNotExist()
    composeTestRule.onNodeWithText(string(R.string.event_detail_youre_going)).assertIsDisplayed()

    // "✓ Going" is a status once going, not a control: TalkBack shouldn't read it as a disabled
    // button, so it has no click action at all.
    node(EventDetailTags.GOING).assert(!hasClickAction())
    node(EventDetailTags.CANCEL_REGISTRATION).performClick()
    assertEquals(listOf("cancel"), calls)
  }

  @Test
  fun whileSaving_aProgressShowsAndNothingCanBeTapped() {
    show(loaded(Attendance.NOT_GOING, saving = true))

    node(EventDetailTags.ATTENDANCE_SAVING).assertIsDisplayed()
    node(EventDetailTags.IM_GOING).assertIsNotEnabled()
  }

  @Test
  fun whileCancelling_theLinkIsDisabled() {
    show(loaded(Attendance.GOING, saving = true))

    node(EventDetailTags.ATTENDANCE_SAVING).assertIsDisplayed()
    node(EventDetailTags.CANCEL_REGISTRATION).assertIsNotEnabled()
  }

  @Test
  fun beforeTheAttendanceIsKnown_imGoingCantBeTapped() {
    show(loaded(Attendance.UNKNOWN))

    node(EventDetailTags.IM_GOING).assertIsDisplayed().assertIsNotEnabled()
  }

  @Test
  fun anOrganizer_onlyGetsFindAGroup() {
    show(loaded(isOrganizer = true))

    node(EventDetailTags.FIND_GROUP).assertIsDisplayed()
    node(EventDetailTags.IM_GOING).assertDoesNotExist()
    node(EventDetailTags.GOING).assertDoesNotExist()
  }

  @Test
  fun inAGroup_goingTakesTheWholeBar_withoutFindAGroup() {
    show(loaded(Attendance.GOING, yourGroup = YourGroup("g", 4, emptyList(), STUDENT)))

    // Figma "Event detail · in a group": the card replaces Find a group, and "✓ Going" stays.
    node(EventDetailTags.FIND_GROUP).assertDoesNotExist()
    node(EventDetailTags.REGISTERED_NOTE).assertIsDisplayed()
    val going = node(EventDetailTags.GOING).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertEquals(320f, going.width / context.resources.displayMetrics.density, 0.5f)
  }

  @Test
  fun inAGroup_hasNoCancelLink() {
    // Cancelling would leave the student in the group without being registered; the link waits
    // for #244's "you'll also leave your group" sheet (#318).
    show(loaded(Attendance.GOING, yourGroup = YourGroup("g", 4, emptyList(), STUDENT)))

    composeTestRule.onNodeWithText(string(R.string.event_detail_youre_going)).assertIsDisplayed()
    node(EventDetailTags.CANCEL_REGISTRATION).assertDoesNotExist()
  }

  @Test
  fun anOrganizerInAGroup_hasNoActionBar() {
    show(loaded(isOrganizer = true, yourGroup = YourGroup("g", 4, emptyList(), "organizer")))

    node(EventDetailTags.FIND_GROUP).assertDoesNotExist()
    node(EventDetailTags.IM_GOING).assertDoesNotExist()
  }

  @Test
  fun anEndedEvent_notGoing_offersNeitherImGoingNorCancel() {
    show(loaded(hasEnded = true))

    node(EventDetailTags.GOING).assertDoesNotExist()
    node(EventDetailTags.IM_GOING).assertDoesNotExist()
    node(EventDetailTags.REGISTERED_NOTE).assertDoesNotExist()
  }

  @Test
  fun anEndedEvent_going_stillOffersCancel_withoutAnActionBar() {
    // The privacy notice promises a registration can be cancelled "even after the event", so the
    // note (and its link) stay even though "I'm going" and Find a group no longer do.
    show(loaded(Attendance.GOING, hasEnded = true))

    node(EventDetailTags.GOING).assertDoesNotExist()
    node(EventDetailTags.FIND_GROUP).assertDoesNotExist()
    node(EventDetailTags.REGISTERED_NOTE).assertIsDisplayed()

    node(EventDetailTags.CANCEL_REGISTRATION).performClick()
    assertEquals(listOf("cancel"), calls)
  }

  @Test
  fun aFailedRegistration_showsItsMessage_whoseRetryRegistersAgain() {
    show(loaded(), AttendanceFeedback(AttendanceMessage.REGISTER_OFFLINE, id = 0))

    composeTestRule
        .onNodeWithText(string(R.string.event_detail_register_offline))
        .assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.retry)).performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf("shown REGISTER_OFFLINE", "going"), calls)
  }

  @Test
  fun aFailedCancellation_showsItsMessage_whoseRetryCancelsAgain() {
    show(loaded(Attendance.GOING), AttendanceFeedback(AttendanceMessage.CANCEL_FAILED, id = 0))

    composeTestRule
        .onNodeWithText(string(R.string.event_detail_attendance_failed))
        .assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.retry)).performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf("shown CANCEL_FAILED", "cancel"), calls)
  }

  /** The screen with its real ViewModel, reading [registrations]. */
  private fun showRoute(registrations: FakeRegistrationRepository) {
    val events = FakeEventRepository().apply { seed(event) }
    val viewModel =
        EventDetailViewModel(
            SavedStateHandle(mapOf("eventId" to EVENT_ID)),
            events,
            object : NetworkMonitor {
              override fun isOnline() = true
            },
            FakeAuthRepository(AuthUser(STUDENT, "student@example.com", true)),
            Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC),
            FakeAssociationNames(),
            registrations,
            FakeGroupRepository(),
            FakePublicProfileRepository(),
        )
    composeTestRule.setContent {
      PolySocialTheme { EventDetailRoute(onBack = {}, viewModel = viewModel) }
    }
    settle()
  }

  /** Runs the ViewModel's coroutines, which wait on the test's main dispatcher. */
  private fun settle() {
    repeat(2) {
      composeTestRule.waitForIdle()
      mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }
    composeTestRule.waitForIdle()
  }

  private fun registrations() =
      FakeRegistrationRepository(currentUid = STUDENT).apply { readableEventIds += EVENT_ID }

  @Test
  fun route_imGoing_registers_andCancelRestoresImGoing() {
    val registrations = registrations()
    showRoute(registrations)

    node(EventDetailTags.IM_GOING).performClick()
    settle()

    node(EventDetailTags.GOING).assertIsDisplayed()
    assertEquals(setOf(STUDENT), registrations.attendeesOf(EVENT_ID))

    node(EventDetailTags.CANCEL_REGISTRATION).performClick()
    settle()

    node(EventDetailTags.IM_GOING).assertIsDisplayed()
    node(EventDetailTags.REGISTERED_NOTE).assertDoesNotExist()
    assertTrue(registrations.attendeesOf(EVENT_ID).isEmpty())
  }

  @Test
  fun route_offline_showsTheError_andTheStateDoesntChange() {
    val registrations = registrations().apply { registerFailure = RegisterResult.NetworkError }
    showRoute(registrations)

    node(EventDetailTags.IM_GOING).performClick()
    settle()

    composeTestRule
        .onNodeWithText(string(R.string.event_detail_register_offline))
        .assertIsDisplayed()
    node(EventDetailTags.IM_GOING).assertIsDisplayed().assertIsEnabled()
    assertTrue(registrations.attendeesOf(EVENT_ID).isEmpty())

    // Back online, Retry registers.
    registrations.registerFailure = null
    composeTestRule.onNodeWithText(string(R.string.retry)).performClick()
    settle()

    node(EventDetailTags.GOING).assertIsDisplayed()
  }

  @Test
  fun route_aFailedCancellation_keepsGoing() {
    val registrations =
        registrations().apply {
          seedRegistration(EVENT_ID, STUDENT)
          cancelFailure = CancelRegistrationResult.UnexpectedError
        }
    showRoute(registrations)

    node(EventDetailTags.CANCEL_REGISTRATION).performClick()
    settle()

    composeTestRule
        .onNodeWithText(string(R.string.event_detail_attendance_failed))
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(EventDetailTags.GOING)
        .assertTextEquals(string(R.string.event_detail_going))
    assertEquals(setOf(STUDENT), registrations.attendeesOf(EVENT_ID))
  }

  private companion object {
    const val EVENT_ID = "event-1"
    const val STUDENT = "student-1"
  }
}

/**
 * Retry while still offline, on its own (Mehdi's review of #317): the class above's
 * [MainDispatcherRule] default, a [kotlinx.coroutines.test.StandardTestDispatcher], drains the
 * ViewModel's `launch` with `advanceUntilIdle` before Compose ever reads the intermediate `null`,
 * so the regression this test exists to catch wasn't actually reproduced there — it passed even on
 * the unfixed code. [UnconfinedTestDispatcher] is closer to the app's `Dispatchers.Main.immediate`:
 * the ViewModel's two `attendanceFeedback` writes ("null", then the new failure) happen within the
 * same synchronous call, so Compose can conflate them into one unchanged value unless each failure
 * carries its own id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EventDetailOfflineRetryRegressionTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun string(id: Int) = context.getString(id)

  private val event =
      validEvent(
              startTime = Instant.parse("2026-10-10T13:00:00Z"),
              endTime = Instant.parse("2026-10-10T17:00:00Z"),
          )
          .copy(id = EVENT_ID, createdBy = "organizer", organizerIds = listOf("organizer"))

  private fun settle() {
    repeat(2) {
      composeTestRule.waitForIdle()
      mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }
    composeTestRule.waitForIdle()
  }

  @Test
  fun route_offlineRetryStillOffline_showsTheErrorAgain() {
    val registrations =
        FakeRegistrationRepository(currentUid = STUDENT).apply {
          readableEventIds += EVENT_ID
          registerFailure = RegisterResult.NetworkError
        }
    val events = FakeEventRepository().apply { seed(event) }
    val viewModel =
        EventDetailViewModel(
            SavedStateHandle(mapOf("eventId" to EVENT_ID)),
            events,
            object : NetworkMonitor {
              override fun isOnline() = true
            },
            FakeAuthRepository(AuthUser(STUDENT, "student@example.com", true)),
            Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC),
            FakeAssociationNames(),
            registrations,
            FakeGroupRepository(),
            FakePublicProfileRepository(),
        )
    composeTestRule.setContent {
      PolySocialTheme { EventDetailRoute(onBack = {}, viewModel = viewModel) }
    }
    settle()

    composeTestRule.onNodeWithTag(EventDetailTags.IM_GOING, useUnmergedTree = true).performClick()
    settle()
    composeTestRule
        .onNodeWithText(string(R.string.event_detail_register_offline))
        .assertIsDisplayed()

    // Still offline: the Retry fails the same way, but it must still show its own Snackbar.
    composeTestRule.onNodeWithText(string(R.string.retry)).performClick()
    settle()

    composeTestRule
        .onNodeWithText(string(R.string.event_detail_register_offline))
        .assertIsDisplayed()
  }

  private companion object {
    const val EVENT_ID = "event-1"
    const val STUDENT = "student-1"
  }
}
