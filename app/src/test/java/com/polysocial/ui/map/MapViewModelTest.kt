// Contributors: OpenAI Codex (map stream, selection and privacy regression tests for #50).
package com.polysocial.ui.map

import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import com.polysocial.model.map.RepositoryMapEventSource
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MapViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()
  private val result = MutableStateFlow<MapEventResult>(MapEventResult.Loading)
  private lateinit var vm: MapViewModel
  private val event = validEvent().copy(id = "one")

  @Before
  fun setUp() {
    vm = MapViewModel(MapEventSource { result })
  }

  private fun emit(value: MapEventResult) {
    result.value = value
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
  }

  @Test
  fun loadingThenPublicEventsShowsEachRecordAndSelection() {
    assertEquals(MapUiState(), vm.uiState.value)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    assertEquals(MapContentStatus.LOADING, vm.uiState.value.status)
    emit(MapEventResult.Events(listOf(event, event.copy(id = "two", title = "Culture session"))))
    assertEquals(2, vm.uiState.value.events.size)
    assertEquals(MapContentStatus.READY, vm.uiState.value.status)
    vm.selectEvent("two")
    assertEquals("Culture session", vm.uiState.value.selectedEvent?.title)
    vm.closePreview()
    assertNull(vm.uiState.value.selectedEvent)
  }

  @Test
  fun emptyAndUnavailableAreDifferentStates() {
    emit(MapEventResult.Events(emptyList()))
    assertEquals(MapContentStatus.EMPTY, vm.uiState.value.status)
    emit(MapEventResult.Unavailable)
    assertEquals(MapContentStatus.UNAVAILABLE, vm.uiState.value.status)
  }

  @Test
  fun privateEventsAndMalformedCoordinatesNeverReachMap() {
    val rejected =
        listOf(
            event.copy(isPrivate = true),
            event.copy(id = ""),
            event.copy(id = "latNaN", location = Coordinates(Double.NaN, 6.5)),
            event.copy(id = "latInfinity", location = Coordinates(Double.POSITIVE_INFINITY, 6.5)),
            event.copy(id = "latRange", location = Coordinates(91.0, 6.5)),
            event.copy(id = "latSouth", location = Coordinates(-91.0, 6.5)),
            event.copy(id = "lonNaN", location = Coordinates(46.5, Double.NaN)),
            event.copy(id = "lonInfinity", location = Coordinates(46.5, Double.NEGATIVE_INFINITY)),
            event.copy(id = "lonRange", location = Coordinates(46.5, -181.0)),
            event.copy(id = "lonEast", location = Coordinates(46.5, 181.0)),
        )
    emit(MapEventResult.Events(rejected))
    assertEquals(emptyList<Any>(), vm.uiState.value.events)
    assertEquals(MapContentStatus.EMPTY, vm.uiState.value.status)
    vm.selectEvent("latRange")
    assertNull(vm.uiState.value.selectedEvent)
  }

  @Test
  fun duplicateIdsAreOneMarkerAndBoundaryCoordinatesAreValid() {
    val bounds = event.copy(location = Coordinates(-90.0, 180.0))
    assertEquals(listOf(bounds), publicMapEvents(listOf(bounds, bounds.copy(title = "duplicate"))))
  }

  @Test
  fun eventUpdatesRefreshPreviewAndRemovalClosesIt() {
    emit(MapEventResult.Events(listOf(event)))
    vm.selectEvent(event.id)
    emit(MapEventResult.Events(listOf(event.copy(title = "Updated"))))
    assertEquals("Updated", vm.uiState.value.selectedEvent?.title)
    emit(MapEventResult.Events(emptyList()))
    assertNull(vm.uiState.value.selectedEvent)
  }

  @Test
  fun errorsRetainCachedMarkersAndRetryRestartsBothSourceAndRenderer() {
    emit(MapEventResult.Events(listOf(event)))
    vm.selectEvent(event.id)
    emit(MapEventResult.Error)
    vm.onRenderStatus(MapRenderStatus.ERROR)
    assertEquals(listOf(event), vm.uiState.value.events)
    assertEquals(event, vm.uiState.value.selectedEvent)
    assertEquals(MapContentStatus.ERROR, vm.uiState.value.status)
    assertEquals(MapRenderStatus.ERROR, vm.uiState.value.renderStatus)
    vm.retry()
    assertEquals(MapContentStatus.LOADING, vm.uiState.value.status)
    assertEquals(MapRenderStatus.LOADING, vm.uiState.value.renderStatus)
    assertEquals(1, vm.uiState.value.renderGeneration)
    emit(MapEventResult.Events(listOf(event)))
    vm.onRenderStatus(MapRenderStatus.READY)
    assertEquals(MapRenderStatus.READY, vm.uiState.value.renderStatus)
    assertEquals(MapContentStatus.READY, vm.uiState.value.status)
  }

  @Test
  fun thrownSourceAndFlowFailuresSurfaceWithoutCrashing() {
    val factoryFailure = MapViewModel(MapEventSource { error("test failure") })
    val flowFailure = MapViewModel(MapEventSource { flow { error("test failure") } })
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    assertEquals(MapContentStatus.ERROR, factoryFailure.uiState.value.status)
    assertEquals(MapContentStatus.ERROR, flowFailure.uiState.value.status)
  }

  @Test
  fun defaultBindingNeverManufacturesAnEmptySuccess() =
      kotlinx.coroutines.test.runTest {
        val repository = FakeEventRepository().apply { readFailure = true }
        assertEquals(
            MapEventResult.Error,
            RepositoryMapEventSource(repository).observePublicUpcomingEvents().first(),
        )
      }

  @Test
  fun retryCancelsPreviousSourceCollection() {
    var active = 0
    var starts = 0
    val source = MapEventSource {
      flow {
        starts++
        active++
        try {
          kotlinx.coroutines.awaitCancellation()
        } finally {
          active--
        }
      }
    }
    val model = MapViewModel(source)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    model.retry()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    assertEquals(2, starts)
    assertEquals(1, active)
  }
}
