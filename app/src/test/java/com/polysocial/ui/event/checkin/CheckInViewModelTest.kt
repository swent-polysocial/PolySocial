// Contributors: OpenAI Codex (GPT-6.2 Sol, high; tested check-in for #469 under Lucas Pinto
// Ricardo’s supervision and approval).
package com.polysocial.ui.event.checkin

import com.polysocial.model.checkin.CheckInRepository
import com.polysocial.model.checkin.CheckInResult
import com.polysocial.model.checkin.CheckInStatus
import com.polysocial.model.checkin.FakeCheckInRepository
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.location.LocationService
import com.polysocial.model.location.PreciseLocationResult
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.ui.event.checkin.CheckInState.Stage
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckInViewModelTest {
  @get:Rule val mainDispatcher = MainDispatcherRule()

  private val start = Instant.parse("2026-11-25T19:00:00Z")
  private val checkedInAt = start.plusSeconds(12)
  private val event =
      validEvent().copy(id = "venue-event", startTime = start, endTime = start.plusSeconds(3600))
  private val uid = "test-student"
  private val target = CheckInTarget(event, uid)
  private val network = FakeNetworkMonitor()
  private val goodFix = PreciseLocationResult.Available(event.location, 100.0)
  private val location = FakeLocationService(preciseResult = goodFix)
  private val repository =
      FakeCheckInRepository(currentUid = uid, now = checkedInAt).apply {
        readableEvents += event
        seedRegistration(event.id, uid)
      }

  private fun viewModel(
      location: LocationService = this.location,
      repository: CheckInRepository = this.repository,
      clock: Clock = Clock.fixed(start, ZoneOffset.UTC),
  ) = CheckInViewModel(location, repository, network, clock)

  @Test
  fun enteringDoesNotReadLocationAndOnlyAPreciseGrantAfterATapStartsTheRead() = runTest {
    val vm = viewModel()
    assertFalse(vm.onLocationAction(true))
    vm.onPermissionResult(precise = true, approximate = true)
    vm.open(target)
    runCurrent()
    vm.onPermissionResult(precise = true, approximate = true)
    assertEquals(Stage.READY, vm.state.value)
    assertEquals(0, location.preciseRequestCount)
    assertNull(repository.checkInOf(event.id, uid))

    assertTrue(vm.onLocationAction(false))
    assertFalse(vm.onLocationAction(false))
    assertEquals(Stage.AWAITING_PERMISSION, vm.state.value)
    vm.onPermissionResult(precise = false, approximate = false)
    assertEquals(Stage.DENIED, vm.state.value)
    assertTrue(vm.onLocationAction(false))
    vm.onPermissionResult(precise = false, approximate = true)
    assertEquals(Stage.APPROXIMATE_ONLY, vm.state.value)
    assertEquals(0, location.preciseRequestCount)

    assertTrue(vm.onLocationAction(false))
    vm.onPermissionResult(precise = true, approximate = true)
    runCurrent()
    assertEquals(CheckInState.Confirmed(checkedInAt), vm.state.value)
    assertEquals(checkedInAt, repository.checkInOf(event.id, uid))
    assertEquals(1, location.preciseRequestCount)
    assertEquals(0, location.requestCount)
  }

  @Test
  fun failuresFromTheLocationServiceAndInaccurateOrDistantFixesNeverWrite() = runTest {
    val vm = viewModel()
    vm.open(target)
    runCurrent()
    val outcomes =
        listOf(
            PreciseLocationResult.PermissionDenied to Stage.DENIED,
            PreciseLocationResult.Unavailable to Stage.NO_FIX,
            PreciseLocationResult.TimedOut to Stage.TIMED_OUT,
            goodFix.copy(accuracyMeters = 100.01) to Stage.TOO_FAR,
            goodFix.copy(coordinates = Coordinates(0.0, 0.0)) to Stage.TOO_FAR,
        )
    outcomes.forEach { (fix, expected) ->
      location.preciseResult = fix
      assertFalse(vm.onLocationAction(true))
      runCurrent()
      assertEquals(expected, vm.state.value)
      assertNull(repository.checkInOf(event.id, uid))
    }
    assertEquals(outcomes.size, location.preciseRequestCount)
  }

  @Test
  fun aPreviouslyConfirmedCheckInShowsItsStoredTimeEvenAfterClosing() = runTest {
    repository.seedCheckIn(event.id, uid, checkedInAt)
    val vm = viewModel(clock = Clock.fixed(event.endTime!!, ZoneOffset.UTC))
    vm.open(target)
    runCurrent()
    assertEquals(CheckInState.Confirmed(checkedInAt), vm.state.value)
    assertFalse(vm.onLocationAction(true))
    assertEquals(0, location.preciseRequestCount)
  }

  @Test
  fun confirmationWaitsForBothTheCommittedWriteAndItsTimestamp() = runTest {
    val committed = CompletableDeferred<CheckInResult>()
    val updates = MutableStateFlow<CheckInStatus>(CheckInStatus.NotCheckedIn)
    val gated =
        object : CheckInRepository by repository {
          override suspend fun checkIn(eventId: String) = committed.await()

          override fun observeCheckIn(eventId: String, uid: String) = updates
        }
    val vm = viewModel(repository = gated)
    vm.open(target)
    runCurrent()
    vm.onLocationAction(true)
    runCurrent()
    assertEquals(Stage.SAVING, vm.state.value)
    assertFalse(vm.onLocationAction(true))
    committed.complete(CheckInResult.CheckedIn)
    runCurrent()
    assertEquals(Stage.SAVING, vm.state.value)
    updates.value = CheckInStatus.CheckedIn(checkedInAt)
    runCurrent()
    assertEquals(CheckInState.Confirmed(checkedInAt), vm.state.value)
    assertEquals(1, location.preciseRequestCount)
  }

  @Test
  fun anOfflineTapDoesNotReadLocationAndRetryCanCheckIn() = runTest {
    val vm = viewModel()
    vm.open(target)
    runCurrent()
    network.online = false
    assertFalse(vm.onLocationAction(false))
    assertEquals(Stage.OFFLINE, vm.state.value)
    assertEquals(0, location.preciseRequestCount)
    assertNull(repository.checkInOf(event.id, uid))
    network.online = true
    vm.onLocationAction(true)
    runCurrent()
    assertEquals(CheckInState.Confirmed(checkedInAt), vm.state.value)
  }

  @Test
  fun closingBoundaryOrConnectionLossDuringTheFixPreventsWriting() = runTest {
    val clock =
        object : Clock() {
          var now = start

          override fun instant() = now

          override fun getZone() = ZoneOffset.UTC

          override fun withZone(zone: java.time.ZoneId) = Clock.fixed(now, zone)
        }
    var fix = CompletableDeferred<PreciseLocationResult>()
    val gated =
        object : LocationService by location {
          override suspend fun currentPreciseLocation() = fix.await()
        }
    val vm = viewModel(location = gated, clock = clock)
    vm.open(target)
    runCurrent()
    vm.onLocationAction(true)
    runCurrent()
    network.online = false
    fix.complete(goodFix)
    runCurrent()
    assertEquals(Stage.OFFLINE, vm.state.value)

    network.online = true
    fix = CompletableDeferred()
    vm.onLocationAction(true)
    runCurrent()
    clock.now = event.endTime!!
    fix.complete(goodFix)
    runCurrent()
    assertEquals(Stage.READY, vm.state.value)
    assertFalse(vm.onLocationAction(false))
    assertNull(repository.checkInOf(event.id, uid))
  }

  @Test
  fun everyRepositoryRefusalKeepsTheStudentUnconfirmed() = runTest {
    val failures =
        listOf(
            CheckInResult.NetworkError to Stage.OFFLINE,
            CheckInResult.NotRegistered to Stage.REGISTRATION_REQUIRED,
            CheckInResult.OutsideWindow to Stage.WINDOW_CHANGED,
            CheckInResult.UnexpectedError to Stage.SAVE_ERROR,
            CheckInResult.NotFound to Stage.EVENT_UNAVAILABLE,
            CheckInResult.NotSignedIn to Stage.EVENT_UNAVAILABLE,
        )
    failures.forEach { (result, expected) ->
      val vm = viewModel()
      repository.checkInFailure = result
      vm.open(target)
      runCurrent()
      vm.onLocationAction(true)
      runCurrent()
      assertEquals(result.toString(), expected, vm.state.value)
      assertNull(repository.checkInOf(event.id, uid))
      vm.close()
    }
  }

  @Test
  fun unreadableStatusRetriesWithoutLocationAndCanShowExistingCheckIn() = runTest {
    repository.unreadableUids += uid
    val vm = viewModel()
    vm.open(target)
    runCurrent()
    assertEquals(Stage.STATUS_ERROR, vm.state.value)
    repository.unreadableUids.clear()
    repository.seedCheckIn(event.id, uid, checkedInAt)
    assertFalse(vm.onLocationAction(false))
    runCurrent()
    assertEquals(CheckInState.Confirmed(checkedInAt), vm.state.value)
    assertEquals(0, location.preciseRequestCount)
  }

  @Test
  fun aMissingTimestampAfterAnAcknowledgedWriteStopsLoadingAndRetriesTheRead() = runTest {
    val updates = MutableStateFlow<CheckInStatus>(CheckInStatus.NotCheckedIn)
    val delayedStatus =
        object : CheckInRepository by repository {
          override fun observeCheckIn(eventId: String, uid: String) = updates
        }
    val vm = viewModel(repository = delayedStatus)
    vm.open(target)
    runCurrent()
    vm.onLocationAction(true)
    runCurrent()
    advanceTimeBy(10_000)
    runCurrent()
    assertEquals(Stage.STATUS_ERROR, vm.state.value)
    assertEquals(checkedInAt, repository.checkInOf(event.id, uid))

    updates.value = CheckInStatus.CheckedIn(checkedInAt)
    vm.onLocationAction(false)
    runCurrent()
    assertEquals(CheckInState.Confirmed(checkedInAt), vm.state.value)
    assertEquals(1, location.preciseRequestCount)
  }

  @Test
  fun leavingTheForegroundCancelsTheFixAndLateResultsCannotWrite() = runTest {
    val fix = CompletableDeferred<PreciseLocationResult>()
    val gated =
        object : LocationService by location {
          override suspend fun currentPreciseLocation() = fix.await()
        }
    val vm = viewModel(location = gated)
    vm.open(target)
    runCurrent()
    vm.onLocationAction(true)
    runCurrent()
    vm.open(CheckInTarget(event.copy(attendeeCount = 2), uid))
    assertEquals(Stage.CHECKING_LOCATION, vm.state.value)
    assertFalse(vm.onLocationAction(true))
    vm.onStop()
    fix.complete(goodFix)
    runCurrent()
    assertEquals(Stage.READY, vm.state.value)
    assertNull(repository.checkInOf(event.id, uid))
    vm.close()
    vm.onStop()
    assertEquals(Stage.LOADING, vm.state.value)
  }

  @Test
  fun aStatusFlowThatEndsWithoutReadingDoesNotLeaveLoadingOnScreen() = runTest {
    val emptyStatus =
        object : CheckInRepository by repository {
          override fun observeCheckIn(eventId: String, uid: String) = emptyFlow<CheckInStatus>()
        }
    val vm = viewModel(repository = emptyStatus)
    vm.open(target)
    runCurrent()
    assertEquals(Stage.STATUS_ERROR, vm.state.value)
  }
}
