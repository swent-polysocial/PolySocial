// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests of "Event full" on the event
// detail, #407).
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
import com.polysocial.model.registration.RegisterResult
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * When the event detail says an event is full (#407): from the event's own read, also from the
 * offline cache, and after a registration the server refused as full.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailFullTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)
  private val events = FakeEventRepository()
  private val registrations =
      FakeRegistrationRepository(currentUid = STUDENT).apply { readableEventIds += EVENT_ID }
  private val auth = FakeAuthRepository(AuthUser(STUDENT, "student@example.com", true))
  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private fun event(capacity: Int? = 8, attendeeCount: Int = 0) =
      validEvent(
              startTime = Instant.parse("2026-10-10T13:00:00Z"),
              endTime = Instant.parse("2026-10-10T17:00:00Z"),
              capacity = capacity,
          )
          .copy(
              id = EVENT_ID,
              createdBy = ORGANIZER,
              organizerIds = listOf(ORGANIZER),
              attendeeCount = attendeeCount,
          )

  /** Opens the detail of [event] and collects its state like the screen does. */
  private fun TestScope.open(event: Event): EventDetailViewModel {
    events.seed(event)
    return EventDetailViewModel(
            SavedStateHandle(mapOf("eventId" to EVENT_ID)),
            events,
            network,
            auth,
            clock,
            FakeAssociationNames(),
            registrations,
            FakeGroupRepository(),
            FakePublicProfileRepository(),
            FakeReminderScheduler(clock),
        )
        .also { vm ->
          backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
          runCurrent()
        }
  }

  /** The event as its organizer or the other students changed it, delivered by its live read. */
  private fun TestScope.change(event: Event) {
    events.remove(EVENT_ID)
    events.seed(event)
    runCurrent()
  }

  private val EventDetailViewModel.loaded
    get() = uiState.value as EventDetailUiState.Loaded

  @Test
  fun anEventAtItsCapacity_isFull() = runTest {
    assertTrue(open(event(capacity = 8, attendeeCount = 8)).loaded.isFull)
  }

  @Test
  fun anEventWithPlacesLeft_orWithoutACapacity_isNotFull() = runTest {
    assertFalse(open(event(capacity = 8, attendeeCount = 7)).loaded.isFull)
    events.remove(EVENT_ID)
    assertFalse(open(event(capacity = null, attendeeCount = 40)).loaded.isFull)
  }

  @Test
  fun aCapacityLoweredBelowTheNumberGoing_isFull() = runTest {
    assertTrue(open(event(capacity = 8, attendeeCount = 12)).loaded.isFull)
  }

  @Test
  fun theEventFillingUpOrAPlaceFreeing_showsLive() = runTest {
    val vm = open(event(attendeeCount = 7))

    change(event(attendeeCount = 8))
    assertTrue(vm.loaded.isFull)

    change(event(attendeeCount = 7))
    assertFalse(vm.loaded.isFull)
  }

  @Test
  fun offline_theCachedEventSaysItIsFull() = runTest {
    online = false
    events.readFromCache = true

    val state = open(event(attendeeCount = 8)).loaded

    assertTrue(state.isOffline)
    assertTrue(state.isFull)
  }

  @Test
  fun aRegistrationRefusedAsFull_showsFull_withoutAMessage_andNotGoing() = runTest {
    // The event shown (here from the cache) still had a place, but the server's had none.
    registrations.registerFailure = RegisterResult.Full
    val vm = open(event(attendeeCount = 7))
    assertFalse(vm.loaded.isFull)

    vm.onImGoing()
    runCurrent()

    assertTrue(vm.loaded.isFull)
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertFalse(vm.loaded.isSavingAttendance)
    assertNull(vm.attendanceFeedback.value)
    assertTrue(registrations.attendeesOf(EVENT_ID).isEmpty())
  }

  @Test
  fun afterARefusal_theEventsNextVersionDecides() = runTest {
    registrations.registerFailure = RegisterResult.Full
    val vm = open(event(attendeeCount = 7))
    vm.onImGoing()
    runCurrent()

    // The read catches up with the server: still full.
    change(event(attendeeCount = 8))
    assertTrue(vm.loaded.isFull)

    // Someone cancels: "I'm going" is offered again.
    change(event(attendeeCount = 7))
    assertFalse(vm.loaded.isFull)
  }

  @Test
  fun aStudentGoing_keepsGoing_onAFullEvent() = runTest {
    registrations.seedRegistration(EVENT_ID, STUDENT)

    val state = open(event(attendeeCount = 8)).loaded

    // The screen keeps "✓ Going" and Cancel registration for them.
    assertTrue(state.isFull)
    assertEquals(Attendance.GOING, state.attendance)
  }

  private companion object {
    const val EVENT_ID = "event-1"
    const val STUDENT = "student-1"
    const val ORGANIZER = "organizer"
  }
}
