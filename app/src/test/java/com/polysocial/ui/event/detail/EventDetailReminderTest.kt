// Contributors: Claude Opus 5.5 (wrote these tests of the "Remind me" toggle, second PR of #123).
// Contributors: Claude Opus 5.5 (tests for a failed cancellation and a stale initial read racing
// a user action, #123's second PR review).
// Contributors: Claude Opus 5.5 (fixed GatedReminderScheduler to capture a true stale snapshot,
// and covered cancelling registration racing the initial read, #123's second PR, Mehdi's review).
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.Event
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.reminder.EventReminder
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.reminder.ReminderScheduler
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "Remind me 1 h before" on the event detail (second PR of #123). */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailReminderTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)
  private val events = FakeEventRepository()
  private val registrations =
      FakeRegistrationRepository(currentUid = STUDENT).apply {
        readableEventIds += EVENT_ID
        seedRegistration(EVENT_ID, STUDENT)
      }
  private val auth = FakeAuthRepository(AuthUser(STUDENT, "student@example.com", true))
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = true
      }
  private val reminders = FakeReminderScheduler(clock)

  private val event =
      validEvent(
              startTime = Instant.parse("2026-10-10T13:00:00Z"),
              endTime = Instant.parse("2026-10-10T17:00:00Z"),
          )
          .copy(
              id = EVENT_ID,
              createdBy = "organizer",
              organizerIds = listOf("organizer"),
              locationName = "Satellite, EPFL",
          )

  private fun viewModel(scheduler: ReminderScheduler = reminders) =
      EventDetailViewModel(
          SavedStateHandle(mapOf("eventId" to EVENT_ID)),
          events,
          network,
          auth,
          clock,
          FakeAssociationNames(),
          registrations,
          FakeGroupRepository(),
          FakePublicProfileRepository(),
          scheduler,
      )

  /** Opens [seeded] (default [event]), already registered, and collects its state live. */
  private fun TestScope.open(
      seeded: Event = event,
      scheduler: ReminderScheduler = reminders,
  ): EventDetailViewModel {
    events.seed(seeded)
    return viewModel(scheduler).also { vm ->
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
      runCurrent()
    }
  }

  private val EventDetailViewModel.loaded
    get() = uiState.value as EventDetailUiState.Loaded

  /**
   * [delegate], except [isScheduled] captures its answer right away, then waits for [gate] before
   * returning it: a slow initial read whose snapshot can go stale while it waits.
   */
  private class GatedReminderScheduler(
      private val delegate: ReminderScheduler,
      private val gate: CompletableDeferred<Unit>,
  ) : ReminderScheduler by delegate {
    override suspend fun isScheduled(eventId: String): Boolean {
      val snapshot = delegate.isScheduled(eventId)
      gate.await()
      return snapshot
    }
  }

  @Test
  fun aGoingStudent_startsWithTheReminderOff_whenNoneIsScheduled() = runTest {
    val vm = open()

    assertFalse(vm.loaded.reminderOn)
  }

  @Test
  fun reopeningTheScreen_restoresTheReminderOnState() = runTest {
    reminders.scheduled[EVENT_ID] = EventReminder(EVENT_ID, event.title, event.startTime, STUDENT)
    val vm = open()

    assertTrue(vm.loaded.reminderOn)
  }

  @Test
  fun turningItOn_schedulesWithTheEventsDetails() = runTest {
    val vm = open()

    vm.onReminderToggle(true)
    runCurrent()

    assertTrue(vm.loaded.reminderOn)
    assertEquals(
        EventReminder(EVENT_ID, event.title, event.startTime, STUDENT, event.locationName),
        reminders.scheduled[EVENT_ID],
    )
  }

  @Test
  fun turningItOff_cancelsTheReminder() = runTest {
    reminders.scheduled[EVENT_ID] = EventReminder(EVENT_ID, event.title, event.startTime, STUDENT)
    val vm = open()

    vm.onReminderToggle(false)
    runCurrent()

    assertFalse(vm.loaded.reminderOn)
    assertTrue(EVENT_ID !in reminders.scheduled)
  }

  @Test
  fun cancellingTheRegistration_alsoCancelsTheReminder() = runTest {
    reminders.scheduled[EVENT_ID] = EventReminder(EVENT_ID, event.title, event.startTime, STUDENT)
    val vm = open()
    assertTrue(vm.loaded.reminderOn)

    vm.onCancelRegistration()
    runCurrent()

    assertFalse(vm.loaded.reminderOn)
    assertTrue(EVENT_ID !in reminders.scheduled)
  }

  @Test
  fun theExplanationSheet_isShownOnRequest_andHiddenOnDismiss() = runTest {
    val vm = open()

    vm.onReminderExplanationRequested()
    runCurrent()
    assertTrue(vm.loaded.showReminderExplanation)

    vm.onReminderExplanationDismiss()
    runCurrent()
    assertFalse(vm.loaded.showReminderExplanation)
    // "Not now": nothing was scheduled.
    assertFalse(vm.loaded.reminderOn)
  }

  @Test
  fun continuing_hidesTheSheet_andAsksTheScreenToRequestThePermission() = runTest {
    val vm = open()

    vm.onReminderExplanationRequested()
    vm.onReminderExplanationContinue()
    runCurrent()

    assertFalse(vm.loaded.showReminderExplanation)
    assertTrue(vm.loaded.requestReminderPermission)
  }

  @Test
  fun theScreen_acknowledgesTheRequestedPermission() = runTest {
    val vm = open()
    vm.onReminderExplanationRequested()
    vm.onReminderExplanationContinue()

    vm.onReminderPermissionRequested()
    runCurrent()

    assertFalse(vm.loaded.requestReminderPermission)
  }

  @Test
  fun aGrantedPermission_schedulesTheReminder() = runTest {
    val vm = open()

    vm.onReminderPermissionResult(true)
    runCurrent()

    assertTrue(vm.loaded.reminderOn)
    assertFalse(vm.loaded.remindersBlocked)
    assertTrue(EVENT_ID in reminders.scheduled)
  }

  @Test
  fun aDeniedPermission_blocksTheToggle_andKeepsItOff() = runTest {
    val vm = open()

    vm.onReminderPermissionResult(false)
    runCurrent()

    assertFalse(vm.loaded.reminderOn)
    assertTrue(vm.loaded.remindersBlocked)
  }

  @Test
  fun aLaterGrant_clearsTheBlockedState() = runTest {
    val vm = open()
    vm.onReminderPermissionResult(false)
    runCurrent()
    assertTrue(vm.loaded.remindersBlocked)

    // The screen rechecks on resume after "Open settings" and reports the same way.
    vm.onReminderPermissionResult(true)
    runCurrent()

    assertFalse(vm.loaded.remindersBlocked)
    assertTrue(vm.loaded.reminderOn)
  }

  @Test
  fun anEventTooCloseToStart_doesNotTurnOn() = runTest {
    // The toggle doesn't check this itself; schedule() does, like the rest of the contract.
    val vm = open(seeded = event.copy(startTime = clock.instant().plusSeconds(1_800)))

    vm.onReminderToggle(true)
    runCurrent()

    assertFalse(vm.loaded.reminderOn)
  }

  @Test
  fun turningItOff_whenCancellationFails_keepsItOn() = runTest {
    reminders.scheduled[EVENT_ID] = EventReminder(EVENT_ID, event.title, event.startTime, STUDENT)
    reminders.fail = true
    val vm = open()
    runCurrent()
    assertTrue(vm.loaded.reminderOn)

    vm.onReminderToggle(false)
    runCurrent()

    // Cancelling failed, so the reminder may still fire: the switch must not claim Off.
    assertTrue(vm.loaded.reminderOn)
    assertTrue(EVENT_ID in reminders.scheduled)
  }

  @Test
  fun cancellingTheRegistration_whenReminderCleanupFails_keepsItOn_andOffersRetry() = runTest {
    reminders.scheduled[EVENT_ID] = EventReminder(EVENT_ID, event.title, event.startTime, STUDENT)
    reminders.fail = true
    val vm = open()
    runCurrent()
    assertTrue(vm.loaded.reminderOn)

    vm.onCancelRegistration()
    runCurrent()

    // The registration is cancelled either way (FakeRegistrationRepository isn't gated by
    // reminders.fail); only the local cleanup failed, so [reminderOn] stays accurate instead of
    // claiming Off, and Retry is offered.
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertTrue(vm.loaded.reminderOn)
    assertTrue(EVENT_ID in reminders.scheduled)
    assertEquals(AttendanceMessage.REMINDER_CLEANUP_FAILED, vm.attendanceFeedback.value?.message)

    // Retry (re-running onCancelRegistration, idempotent on the registration side) can still fix
    // it.
    reminders.fail = false
    vm.onCancelRegistration()
    runCurrent()
    assertFalse(vm.loaded.reminderOn)
    assertTrue(EVENT_ID !in reminders.scheduled)
  }

  @Test
  fun aStaleInitialRead_neverOverwritesAUserActionThatCameFirst() = runTest {
    val gate = CompletableDeferred<Unit>()
    val gated = GatedReminderScheduler(reminders, gate)
    val vm = open(scheduler = gated)
    // initReminderOnce's isScheduled() read is in flight, gated.

    vm.onReminderToggle(true)
    runCurrent()
    assertTrue(vm.loaded.reminderOn)

    // The stale read now answers false (nothing was scheduled when it started): it must not
    // overwrite the toggle the student already turned on.
    gate.complete(Unit)
    runCurrent()

    assertTrue(vm.loaded.reminderOn)
  }

  @Test
  fun aStaleInitialRead_neverResurrectsTheReminderAfterCancellingRegistration() = runTest {
    reminders.scheduled[EVENT_ID] = EventReminder(EVENT_ID, event.title, event.startTime, STUDENT)
    val gate = CompletableDeferred<Unit>()
    val gated = GatedReminderScheduler(reminders, gate)
    val vm = open(scheduler = gated)
    // initReminderOnce's isScheduled() read already captured "true", but is still gated.

    vm.onCancelRegistration()
    runCurrent()
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertFalse(vm.loaded.reminderOn)
    assertTrue(EVENT_ID !in reminders.scheduled)

    // The stale "true" snapshot must not resurrect the reminder now that cancelling the
    // registration already turned it off.
    gate.complete(Unit)
    runCurrent()

    assertFalse(vm.loaded.reminderOn)
  }

  private companion object {
    const val EVENT_ID = "event-1"
    const val STUDENT = "student-1"
  }
}
