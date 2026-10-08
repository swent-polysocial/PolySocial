// Contributors: OpenAI Codex (optional Map permission and one-off location lifecycle tests for
// #51).
package com.polysocial.ui.map

import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.location.LocationResult
import com.polysocial.model.location.LocationService
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MapLocationViewModelTest {
  @get:Rule val dispatcherRule = MainDispatcherRule()
  private val event = validEvent().copy(id = "event", location = Coordinates(0.0, 1.0))
  private val source = MapEventSource { flowOf(MapEventResult.Events(listOf(event))) }
  private val service = FakeLocationService()

  private fun model(location: LocationService = service): MapViewModel =
      MapViewModel(source, location)

  private fun settle() = dispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  @Test
  fun firstEntry_offersPromptWithoutReadingLocation() {
    val vm = model()
    vm.onMapEntered()
    assertEquals(MapLocationState.RequestPermission, vm.uiState.value.locationState)
    assertEquals(0, service.requestCount)
    assertFalse(service.permissionRequested)
    vm.onPermissionRequested()
    assertTrue(service.permissionRequested)
    assertEquals(MapLocationState.Waiting, vm.uiState.value.locationState)
  }

  @Test
  fun deniedPermission_doesNotBlockEventsOrRepeatPromptAfterReentryOrRecreation() {
    val vm = model()
    vm.onMapEntered()
    vm.onPermissionRequested()
    vm.onPermissionResult()
    settle()
    assertEquals(MapLocationState.Denied, vm.uiState.value.locationState)
    assertEquals(listOf(event), vm.uiState.value.events)
    vm.selectEvent(event.id)
    assertEquals(event, vm.uiState.value.selectedEvent)
    assertNull(vm.uiState.value.selectedDistanceMeters)
    vm.onMapInactive()
    assertNull(vm.uiState.value.selectedDistanceMeters)
    vm.onMapEntered()
    assertEquals(MapLocationState.Denied, vm.uiState.value.locationState)
    val recreated = model()
    recreated.onMapEntered()
    assertEquals(MapLocationState.Denied, recreated.uiState.value.locationState)
    assertEquals(0, service.requestCount)
  }

  @Test
  fun grantedPermission_computesDistanceForCurrentSelectionWithoutRepeatingFixOnTabSwitch() {
    service.permissionGranted = true
    service.result = LocationResult.Available(Coordinates(0.0, 0.0))
    val vm = model()
    vm.onMapEntered()
    assertEquals(MapLocationState.Locating, vm.uiState.value.locationState)
    settle()
    assertNull(vm.uiState.value.selectedDistanceMeters)
    vm.selectEvent(event.id)
    assertEquals(111_194.9266, vm.uiState.value.selectedDistanceMeters!!, 0.001)
    vm.onMapInactive()
    vm.onMapEntered()
    settle()
    assertEquals(1, service.requestCount)
    vm.closePreview()
    assertNull(vm.uiState.value.selectedDistanceMeters)
  }

  @Test
  fun grantingApproximatePermission_afterPromptStartsOneFix() {
    val vm = model()
    vm.onMapEntered()
    vm.onPermissionRequested()
    service.permissionGranted = true
    service.result = LocationResult.Available(event.location)
    vm.onPermissionResult()
    vm.onMapEntered()
    settle()
    assertEquals(MapLocationState.Available(event.location), vm.uiState.value.locationState)
    assertEquals(1, service.requestCount)
  }

  @Test
  fun unavailableFix_keepsEventsAndOnlyRetriesAfterExplicitAction() {
    service.permissionGranted = true
    val vm = model()
    vm.onMapEntered()
    settle()
    assertEquals(MapLocationState.Unavailable, vm.uiState.value.locationState)
    assertEquals(listOf(event), vm.uiState.value.events)
    vm.onMapInactive()
    vm.onMapEntered()
    settle()
    assertEquals(1, service.requestCount)
    service.result = LocationResult.Available(event.location)
    vm.turnOnLocation()
    settle()
    assertEquals(MapLocationState.Available(event.location), vm.uiState.value.locationState)
    assertEquals(2, service.requestCount)
  }

  @Test
  fun explicitTurnOn_canRequestAgainAfterDenial() {
    service.markPermissionRequested()
    val vm = model()
    vm.onMapEntered()
    vm.turnOnLocation()
    assertEquals(MapLocationState.RequestPermission, vm.uiState.value.locationState)
    assertEquals(0, service.requestCount)
  }

  @Test
  fun permissionRevokedOnResume_clearsDistanceAndKeepsSelectedEvent() {
    service.permissionGranted = true
    service.result = LocationResult.Available(Coordinates(0.0, 0.0))
    val vm = model()
    vm.onMapEntered()
    settle()
    vm.selectEvent(event.id)
    vm.onMapInactive()
    service.permissionGranted = false
    vm.onMapEntered()
    assertEquals(MapLocationState.Denied, vm.uiState.value.locationState)
    assertNull(vm.uiState.value.selectedDistanceMeters)
    assertEquals(event, vm.uiState.value.selectedEvent)
  }

  @Test
  fun permissionGrantedInSettings_afterEarlierDenialReadsFixOnResume() {
    service.markPermissionRequested()
    val vm = model()
    vm.onMapEntered()
    vm.onMapInactive()
    service.permissionGranted = true
    service.result = LocationResult.Available(event.location)
    vm.onMapEntered()
    settle()
    assertEquals(MapLocationState.Available(event.location), vm.uiState.value.locationState)
  }

  @Test
  fun backgroundingMap_cancelsPendingFixAndDoesNotRestartAutomatically() {
    service.permissionGranted = true
    var cancelled = false
    var starts = 0
    val pending =
        object : LocationService by service {
          override suspend fun currentLocation(): LocationResult {
            starts++
            try {
              awaitCancellation()
            } finally {
              cancelled = true
            }
          }
        }
    val vm = model(pending)
    vm.onMapEntered()
    settle()
    assertEquals(MapLocationState.Locating, vm.uiState.value.locationState)
    vm.onMapInactive()
    settle()
    assertTrue(cancelled)
    assertEquals(MapLocationState.Idle, vm.uiState.value.locationState)
    vm.onMapEntered()
    settle()
    assertEquals(1, starts)
  }

  @Test
  fun permissionResultWhileBackgrounded_doesNotReadUntilMapResumes() {
    val vm = model()
    vm.onMapEntered()
    vm.onPermissionRequested()
    vm.onMapInactive()
    service.permissionGranted = true
    vm.onPermissionResult()
    settle()
    assertEquals(MapLocationState.Idle, vm.uiState.value.locationState)
    assertEquals(0, service.requestCount)
    vm.onMapEntered()
    settle()
    assertEquals(1, service.requestCount)
  }

  @Test
  fun deviceFailureAndRevokedPermissionDuringFix_areNonBlocking() {
    service.permissionGranted = true
    val failing =
        object : LocationService by service {
          override suspend fun currentLocation(): LocationResult = error("device failure")
        }
    val failureVm = model(failing)
    failureVm.onMapEntered()
    settle()
    assertEquals(MapLocationState.Unavailable, failureVm.uiState.value.locationState)
    assertEquals(listOf(event), failureVm.uiState.value.events)
    service.result = LocationResult.PermissionDenied
    val deniedVm = model()
    deniedVm.onMapEntered()
    settle()
    assertEquals(MapLocationState.Denied, deniedVm.uiState.value.locationState)
  }

  @Test
  fun leavingForeground_discardsCoordinateSnapshot() {
    service.permissionGranted = true
    service.result = LocationResult.Available(event.location)
    val vm = model()
    vm.onMapEntered()
    settle()
    vm.selectEvent(event.id)
    assertEquals(0.0, vm.uiState.value.selectedDistanceMeters!!, 0.0)
    vm.onMapInactive()
    assertEquals(MapLocationState.Idle, vm.uiState.value.locationState)
    assertNull(vm.uiState.value.selectedDistanceMeters)
    assertEquals(event, vm.uiState.value.selectedEvent)
  }

  @Test
  fun permanentDenialOffersSettingsAndConsumesTheAction() {
    service.markPermissionRequested()
    val vm = model()
    vm.onMapEntered()
    vm.turnOnLocation(canRequestPermission = false)
    assertEquals(MapLocationState.OpenSettings, vm.uiState.value.locationState)
    assertEquals(0, service.requestCount)
    vm.onSettingsOpened()
    assertEquals(MapLocationState.Denied, vm.uiState.value.locationState)
    vm.onMapInactive()
    service.permissionGranted = true
    service.result = LocationResult.Available(event.location)
    vm.onMapEntered()
    settle()
    assertEquals(1, service.requestCount)
    assertEquals(MapLocationState.Available(event.location), vm.uiState.value.locationState)
  }
}
