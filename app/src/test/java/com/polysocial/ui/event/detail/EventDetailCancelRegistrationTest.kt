// Contributors: Claude (Sonnet 5.5, Anthropic) wrote these tests of cancelling a registration from
// a group, and of the sheet that asks first, #318.
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GroupWriteResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** "Cancel registration" for a student in a group leaves it first, after a confirmation (#318). */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailCancelRegistrationTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Europe/Zurich"))
  private val events = FakeEventRepository()
  private val registrations =
      FakeRegistrationRepository(currentUid = ME).apply {
        readableEventIds += EVENT
        seedRegistration(EVENT, ME)
      }
  private val groups = FakeGroupRepository(currentUid = ME).apply { readableEventIds += EVENT }

  private fun TestScope.open(): EventDetailViewModel {
    events.seed(validEvent().copy(id = EVENT, title = "Balélec warm-up"))
    val vm =
        EventDetailViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT)),
            events,
            object : NetworkMonitor {
              override fun isOnline() = true
            },
            FakeAuthRepository(AuthUser(ME, "fictional.student@epfl.ch", true)),
            clock,
            FakeAssociationNames(),
            registrations,
            groups,
            FakePublicProfileRepository(),
            FakeReminderScheduler(clock),
        )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    runCurrent()
    return vm
  }

  private val EventDetailViewModel.loaded
    get() = uiState.value as EventDetailUiState.Loaded

  @Test
  fun notInAGroup_cancellingCancelsRightAway_withoutTheSheet() = runTest {
    val vm = open()
    assertEquals(Attendance.GOING, vm.loaded.attendance)

    vm.onCancelRegistration()
    runCurrent()

    assertEquals(CancelRegistrationState.Hidden, vm.registrationSheet.value)
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
  }

  @Test
  fun inAGroup_cancellingOnlyAsks_andChangesNothing() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    val vm = open()

    vm.onCancelRegistration()
    runCurrent()

    assertEquals(CancelRegistrationState.Confirming, vm.registrationSheet.value)
    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertEquals(listOf("lea", ME), groups.groupsOf(EVENT).single().memberUids)
  }

  @Test
  fun keepGoing_closesTheSheet_andChangesNothing() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    val vm = open()
    vm.onCancelRegistration()

    vm.onKeepRegistration()
    runCurrent()

    assertEquals(CancelRegistrationState.Hidden, vm.registrationSheet.value)
    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertEquals(listOf("lea", ME), groups.groupsOf(EVENT).single().memberUids)
  }

  @Test
  fun confirming_leavesTheGroup_thenCancelsTheRegistration_theOthersStay() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    val vm = open()
    vm.onCancelRegistration()

    vm.onConfirmCancelRegistration()
    runCurrent()

    assertEquals(CancelRegistrationState.Hidden, vm.registrationSheet.value)
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertNull(vm.loaded.yourGroup)
    assertEquals(listOf("lea"), groups.groupsOf(EVENT).single().memberUids)
  }

  @Test
  fun theLastMember_confirming_deletesTheGroup() = runTest {
    groups.seedGroup(EVENT, listOf(ME))
    val vm = open()
    vm.onCancelRegistration()

    vm.onConfirmCancelRegistration()
    runCurrent()

    assertEquals(emptyList<Any>(), groups.groupsOf(EVENT))
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
  }

  @Test
  fun offline_theGroupIsntLeft_nothingChanges_andTryAgainWorks() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    groups.writeFailure = GroupWriteResult.NetworkError
    val vm = open()
    vm.onCancelRegistration()

    vm.onConfirmCancelRegistration()
    runCurrent()

    assertEquals(
        CancelRegistrationState.Failed(CancelRegistrationState.Failed.Reason.OFFLINE),
        vm.registrationSheet.value,
    )
    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertEquals(listOf("lea", ME), groups.groupsOf(EVENT).single().memberUids)

    groups.writeFailure = null
    vm.onConfirmCancelRegistration()
    runCurrent()

    assertEquals(CancelRegistrationState.Hidden, vm.registrationSheet.value)
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
  }

  @Test
  fun anUnexpectedFailure_keepsTheSheetWithItsReason_andKeepGoingStillCloses() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    groups.writeFailure = GroupWriteResult.UnexpectedError
    val vm = open()
    vm.onCancelRegistration()

    vm.onConfirmCancelRegistration()
    runCurrent()
    assertEquals(
        CancelRegistrationState.Failed(CancelRegistrationState.Failed.Reason.UNEXPECTED),
        vm.registrationSheet.value,
    )

    vm.onKeepRegistration()
    assertEquals(CancelRegistrationState.Hidden, vm.registrationSheet.value)
    assertEquals(Attendance.GOING, vm.loaded.attendance)
  }

  @Test
  fun ifOnlyTheCancellationFails_theStudentIsOutOfTheGroupButStillRegistered_withRetry() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    registrations.cancelFailure = CancelRegistrationResult.NetworkError
    val vm = open()
    vm.onCancelRegistration()

    vm.onConfirmCancelRegistration()
    runCurrent()

    // Never in a group without being registered: the group went first.
    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertNull(vm.loaded.yourGroup)
    assertEquals(AttendanceMessage.CANCEL_OFFLINE, vm.attendanceFeedback.value?.message)

    // The Snackbar's Retry cancels straight away: there is no group left to leave.
    registrations.cancelFailure = null
    vm.onCancelRegistration()
    runCurrent()
    assertEquals(CancelRegistrationState.Hidden, vm.registrationSheet.value)
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
  }

  @Test
  fun confirmingWithoutTheSheet_doesNothing() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    val vm = open()

    vm.onConfirmCancelRegistration()
    runCurrent()

    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertEquals(listOf("lea", ME), groups.groupsOf(EVENT).single().memberUids)
  }

  private companion object {
    const val EVENT = "party"
    const val ME = "me"
  }
}
