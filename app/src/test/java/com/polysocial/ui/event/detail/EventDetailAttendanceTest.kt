// Contributors: Claude Opus 5.5 (wrote these tests of "I'm going" on the event detail, #122).
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.registration.MyRegistrationsResult
import com.polysocial.model.registration.RegisterResult
import com.polysocial.model.registration.RegistrationRepository
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

/** "I'm going" and Cancel registration on the event detail (#122). */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailAttendanceTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)
  private val events = FakeEventRepository()
  private val registrations =
      FakeRegistrationRepository(currentUid = STUDENT).apply { readableEventIds += EVENT_ID }
  private val auth = FakeAuthRepository(AuthUser(STUDENT, "student@example.com", true))
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = true
      }

  private val event =
      validEvent(
              startTime = Instant.parse("2026-10-10T13:00:00Z"),
              endTime = Instant.parse("2026-10-10T17:00:00Z"),
          )
          .copy(id = EVENT_ID, createdBy = "organizer", organizerIds = listOf("organizer"))

  private fun viewModel(registrations: RegistrationRepository = this.registrations) =
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
      )

  /** Opens the detail of [event] and collects its state like the screen does. */
  private fun TestScope.open(
      registrations: RegistrationRepository = this@EventDetailAttendanceTest.registrations
  ): EventDetailViewModel {
    events.seed(event)
    return viewModel(registrations).also { vm ->
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
      runCurrent()
    }
  }

  private val EventDetailViewModel.loaded
    get() = uiState.value as EventDetailUiState.Loaded

  /** [registrations], except that [register] and [cancel] wait for [gate] first. */
  private fun waitingFor(gate: CompletableDeferred<Unit>, calls: MutableList<String>) =
      object : RegistrationRepository by registrations {
        override suspend fun register(eventId: String): RegisterResult {
          calls += "register"
          gate.await()
          return registrations.register(eventId)
        }

        override suspend fun cancel(eventId: String): CancelRegistrationResult {
          calls += "cancel"
          gate.await()
          return registrations.cancel(eventId)
        }
      }

  @Test
  fun aStudentWhoIsNotRegistered_isOfferedImGoing() = runTest {
    val vm = open()

    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertFalse(vm.loaded.isSavingAttendance)
  }

  @Test
  fun aStudentAlreadyRegistered_isShownGoing() = runTest {
    registrations.seedRegistration(EVENT_ID, STUDENT)

    assertEquals(Attendance.GOING, open().loaded.attendance)
  }

  @Test
  fun beforeTheRegistrationsAnswer_theAttendanceIsUnknown() = runTest {
    val silent =
        object : RegistrationRepository by registrations {
          override fun observeMyRegistrations(): Flow<MyRegistrationsResult> = flow {
            awaitCancellation()
          }
        }

    assertEquals(Attendance.UNKNOWN, open(silent).loaded.attendance)
  }

  @Test
  fun imGoing_registersTheStudent_andShowsGoing() = runTest {
    val vm = open()

    vm.onImGoing()
    runCurrent()

    assertEquals(setOf(STUDENT), registrations.attendeesOf(EVENT_ID))
    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertNull(vm.attendanceMessage.value)
  }

  @Test
  fun cancelRegistration_removesIt_andOffersImGoingAgain() = runTest {
    registrations.seedRegistration(EVENT_ID, STUDENT)
    val vm = open()

    vm.onCancelRegistration()
    runCurrent()

    assertTrue(registrations.attendeesOf(EVENT_ID).isEmpty())
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertNull(vm.attendanceMessage.value)
  }

  @Test
  fun whileSaving_theStateSaysSo_andAnotherTapIsIgnored() = runTest {
    val gate = CompletableDeferred<Unit>()
    val calls = mutableListOf<String>()
    val vm = open(waitingFor(gate, calls))

    vm.onImGoing()
    runCurrent()
    assertTrue(vm.loaded.isSavingAttendance)
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)

    vm.onImGoing()
    vm.onCancelRegistration()
    runCurrent()
    assertEquals(listOf("register"), calls)

    gate.complete(Unit)
    runCurrent()
    assertFalse(vm.loaded.isSavingAttendance)
    assertEquals(Attendance.GOING, vm.loaded.attendance)
  }

  @Test
  fun registeringOffline_changesNothing_andRetryRegisters() = runTest {
    registrations.registerFailure = RegisterResult.NetworkError
    val vm = open()

    vm.onImGoing()
    runCurrent()

    assertEquals(AttendanceMessage.REGISTER_OFFLINE, vm.attendanceMessage.value)
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertFalse(vm.loaded.isSavingAttendance)
    assertTrue(registrations.attendeesOf(EVENT_ID).isEmpty())

    // The Snackbar's Retry taps "I'm going" again, once back online.
    registrations.registerFailure = null
    vm.onImGoing()
    runCurrent()

    assertNull(vm.attendanceMessage.value)
    assertEquals(Attendance.GOING, vm.loaded.attendance)
  }

  @Test
  fun aRefusedOrFailedRegistration_isNeverShownAsGoing() = runTest {
    for (failure in
        listOf(
            RegisterResult.UnexpectedError,
            RegisterResult.NotFound,
            RegisterResult.NotSignedIn,
        )) {
      registrations.registerFailure = failure
      val vm = open()

      vm.onImGoing()
      runCurrent()

      assertEquals("$failure", AttendanceMessage.REGISTER_FAILED, vm.attendanceMessage.value)
      assertEquals("$failure", Attendance.NOT_GOING, vm.loaded.attendance)
    }
  }

  @Test
  fun cancellingOffline_keepsTheRegistration() = runTest {
    registrations.seedRegistration(EVENT_ID, STUDENT)
    registrations.cancelFailure = CancelRegistrationResult.NetworkError
    val vm = open()

    vm.onCancelRegistration()
    runCurrent()

    assertEquals(AttendanceMessage.CANCEL_OFFLINE, vm.attendanceMessage.value)
    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertEquals(setOf(STUDENT), registrations.attendeesOf(EVENT_ID))
  }

  @Test
  fun aFailedCancellation_keepsTheRegistration() = runTest {
    registrations.seedRegistration(EVENT_ID, STUDENT)
    registrations.cancelFailure = CancelRegistrationResult.UnexpectedError
    val vm = open()

    vm.onCancelRegistration()
    runCurrent()

    assertEquals(AttendanceMessage.CANCEL_FAILED, vm.attendanceMessage.value)
    assertEquals(Attendance.GOING, vm.loaded.attendance)
  }

  @Test
  fun aNewSave_clearsTheLastMessage_andAShownMessageIsCleared() = runTest {
    registrations.registerFailure = RegisterResult.NetworkError
    val vm = open()
    vm.onImGoing()
    runCurrent()

    // A message the screen already replaced isn't cleared by the older one's end.
    vm.onAttendanceMessageShown(AttendanceMessage.REGISTER_FAILED)
    assertEquals(AttendanceMessage.REGISTER_OFFLINE, vm.attendanceMessage.value)

    vm.onAttendanceMessageShown(AttendanceMessage.REGISTER_OFFLINE)
    assertNull(vm.attendanceMessage.value)
  }

  @Test
  fun unreadableRegistrations_stillOfferImGoing_whichThenShowsGoing() = runTest {
    registrations.readFailure = true
    val vm = open()

    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)

    vm.onImGoing()
    runCurrent()

    // The registrations' read has ended, so the saved answer stays.
    assertEquals(Attendance.GOING, vm.loaded.attendance)
  }

  @Test
  fun aRegistrationChangedElsewhere_showsLive() = runTest {
    val vm = open()

    registrations.seedRegistration(EVENT_ID, STUDENT)
    runCurrent()
    assertEquals(Attendance.GOING, vm.loaded.attendance)

    registrations.cancel(EVENT_ID)
    runCurrent()
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
  }

  @Test
  fun anOrganizer_isNotOfferedImGoing() = runTest {
    auth.user = AuthUser("organizer", "organizer@example.com", true)
    registrations.currentUid = "organizer"
    registrations.seedRegistration(EVENT_ID, "organizer")
    val vm = open()

    assertTrue(vm.loaded.isOrganizer)
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
  }

  private companion object {
    const val EVENT_ID = "event-1"
    const val STUDENT = "student-1"
  }
}
