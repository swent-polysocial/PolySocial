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
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.GroupWriteResult
import com.polysocial.model.group.MyGroupResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

  private fun TestScope.open(
      groupRepository: GroupRepository = groups,
      online: Boolean = true,
  ): EventDetailViewModel {
    events.seed(validEvent().copy(id = EVENT, title = "Balélec warm-up"))
    val vm =
        EventDetailViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT)),
            events,
            object : NetworkMonitor {
              override fun isOnline() = online
            },
            FakeAuthRepository(AuthUser(ME, "fictional.student@epfl.ch", true)),
            clock,
            FakeAssociationNames(),
            registrations,
            groupRepository,
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

  @Test
  fun aLeaveThatThrows_isAnUnexpectedFailure_notACrash() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    val vm =
        open(
            object : GroupRepository by groups {
              override suspend fun leaveGroup(eventId: String, groupId: String): GroupWriteResult =
                  throw IllegalStateException("a stored field had the wrong type")
            }
        )
    vm.onCancelRegistration()

    vm.onConfirmCancelRegistration()
    runCurrent()

    assertEquals(
        CancelRegistrationState.Failed(CancelRegistrationState.Failed.Reason.UNEXPECTED),
        vm.registrationSheet.value,
    )
    assertEquals(Attendance.GOING, vm.loaded.attendance)
  }

  @Test
  fun aCancelledLeave_putsTheSheetBackAsItWas() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    val vm =
        open(
            object : GroupRepository by groups {
              override suspend fun leaveGroup(eventId: String, groupId: String): GroupWriteResult =
                  throw CancellationException("the screen went away")
            }
        )
    vm.onCancelRegistration()

    vm.onConfirmCancelRegistration()
    runCurrent()

    // Not stuck on "Leaving": the confirmation is back, and nothing was cancelled.
    assertEquals(CancelRegistrationState.Confirming, vm.registrationSheet.value)
    assertEquals(Attendance.GOING, vm.loaded.attendance)
  }

  @Test
  fun theGroupDisappearsWhileTheSheetIsOpen_confirmStillCancelsTheRegistration() = runTest {
    val group = groups.seedGroup(EVENT, listOf("lea", ME))
    val vm = open()
    vm.onCancelRegistration()
    groups.leaveGroup(EVENT, group.id)
    runCurrent()
    assertNull(vm.loaded.yourGroup)

    vm.onConfirmCancelRegistration()
    runCurrent()

    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertEquals(CancelRegistrationState.Hidden, vm.registrationSheet.value)
  }

  @Test
  fun retryingAfterAFailure_whenTheGroupIsGoneMeanwhile_cancelsWithoutLeaving() = runTest {
    val group = groups.seedGroup(EVENT, listOf("lea", ME))
    groups.writeFailure = GroupWriteResult.NetworkError
    val vm = open()
    vm.onCancelRegistration()
    vm.onConfirmCancelRegistration()
    runCurrent()
    assertEquals(
        CancelRegistrationState.Failed(CancelRegistrationState.Failed.Reason.OFFLINE),
        vm.registrationSheet.value,
    )

    // The failure is only on the next write; the group was left from another device.
    groups.writeFailure = null
    groups.leaveGroup(EVENT, group.id)
    groups.writeFailure = GroupWriteResult.NetworkError
    runCurrent()
    assertNull(vm.loaded.yourGroup)

    vm.onConfirmCancelRegistration()
    runCurrent()

    // No leave was needed, so the failing group write never ran.
    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertEquals(CancelRegistrationState.Hidden, vm.registrationSheet.value)
  }

  @Test
  fun theGroupCantBeRead_cancellingDoesNotCancel_thenRetryAfterTheReadRecovers() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    groups.readFailure = FakeGroupRepository.ReadFailure.NETWORK
    val vm = open()
    assertNull(vm.loaded.yourGroup)

    vm.onCancelRegistration()
    runCurrent()

    // The student is still in a group, so the failed read must not look like "no group".
    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertEquals(
        CancelRegistrationState.Failed(CancelRegistrationState.Failed.Reason.UNEXPECTED),
        vm.registrationSheet.value,
    )
    assertEquals(listOf("lea", ME), groups.groupsOf(EVENT).single().memberUids)

    // Retry restarts the read. Once it works, the group is known and Retry leaves it, then cancels.
    groups.readFailure = null
    vm.onConfirmCancelRegistration()
    runCurrent()
    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertEquals(listOf("lea", ME), vm.loaded.yourGroup?.members?.map { it.uid })
    vm.onConfirmCancelRegistration()
    runCurrent()

    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertEquals(listOf("lea"), groups.groupsOf(EVENT).single().memberUids)
  }

  @Test
  fun theGroupIsStillBeingRead_cancellingDoesNotCancel() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    val vm =
        open(
            object : GroupRepository by groups {
              override fun observeMyGroup(eventId: String) = flowOf(MyGroupResult.Loading)
            }
        )

    vm.onCancelRegistration()
    runCurrent()

    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertEquals(
        CancelRegistrationState.Failed(CancelRegistrationState.Failed.Reason.UNEXPECTED),
        vm.registrationSheet.value,
    )
  }

  @Test
  fun retryingWhileTheGroupStillCantBeRead_staysFailed_andLeavesNothingCancelled() = runTest {
    groups.seedGroup(EVENT, listOf("lea", ME))
    groups.readFailure = FakeGroupRepository.ReadFailure.UNEXPECTED
    val vm = open()
    vm.onCancelRegistration()
    runCurrent()

    vm.onConfirmCancelRegistration()
    runCurrent()

    assertEquals(
        CancelRegistrationState.Failed(CancelRegistrationState.Failed.Reason.UNEXPECTED),
        vm.registrationSheet.value,
    )
    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertEquals(listOf("lea", ME), groups.groupsOf(EVENT).single().memberUids)
  }

  @Test
  fun anEmptyAnswerFromTheCache_isNotAbsence_untilTheServerAnswers() = runTest {
    val group = groups.seedGroup(EVENT, listOf("lea", ME))
    val serverAnswer = MutableSharedFlow<MyGroupResult>()
    val vm =
        open(
            object : GroupRepository by groups {
              override fun observeMyGroup(eventId: String) = flow {
                // The cache has nothing yet: they joined from another device.
                emit(MyGroupResult.Success(null, fromCache = true))
                emitAll(serverAnswer)
              }
            }
        )
    assertNull(vm.loaded.yourGroup)

    vm.onCancelRegistration()
    runCurrent()

    // Nothing is cancelled on the cache's word: the student is still in a group.
    assertEquals(Attendance.GOING, vm.loaded.attendance)
    assertEquals(
        CancelRegistrationState.Failed(CancelRegistrationState.Failed.Reason.UNEXPECTED),
        vm.registrationSheet.value,
    )
    assertEquals(listOf("lea", ME), groups.groupsOf(EVENT).single().memberUids)

    // The server answers with the group: Retry now leaves it, then cancels.
    serverAnswer.emit(MyGroupResult.Success(group, fromCache = false))
    runCurrent()
    vm.onConfirmCancelRegistration()
    runCurrent()

    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertEquals(listOf("lea"), groups.groupsOf(EVENT).single().memberUids)
  }

  @Test
  fun anEmptyAnswerFromTheServer_isAbsence_andCancelsRightAway() = runTest {
    val vm =
        open(
            object : GroupRepository by groups {
              override fun observeMyGroup(eventId: String) =
                  flowOf(MyGroupResult.Success(null, fromCache = false))
            }
        )

    vm.onCancelRegistration()
    runCurrent()

    assertEquals(Attendance.NOT_GOING, vm.loaded.attendance)
    assertEquals(CancelRegistrationState.Hidden, vm.registrationSheet.value)
  }

  @Test
  fun offlineWithAnEmptyCache_cancelExplainsTheLimit_insteadOfTheGroupSheet() = runTest {
    groups.readFromCache = true
    registrations.cancelFailure = CancelRegistrationResult.NetworkError
    val vm = open(online = false)
    assertNull(vm.loaded.yourGroup)

    vm.onCancelRegistration()
    runCurrent()

    // The cancellation can't happen offline, so it says so; nothing changed and no sheet opened.
    assertEquals(CancelRegistrationState.Hidden, vm.registrationSheet.value)
    assertEquals(AttendanceMessage.CANCEL_OFFLINE, vm.attendanceFeedback.value?.message)
    assertEquals(Attendance.GOING, vm.loaded.attendance)
  }

  private companion object {
    const val EVENT = "party"
    const val ME = "me"
  }
}
