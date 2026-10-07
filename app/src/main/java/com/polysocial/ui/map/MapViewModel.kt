// Contributors: OpenAI Codex (map state, selection and failure handling for #50;
// one-off location permission and distance state for #51).
package com.polysocial.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.location.LocationResult
import com.polysocial.model.location.LocationService
import com.polysocial.model.location.straightLineDistanceMeters
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MapContentStatus {
  LOADING,
  READY,
  EMPTY,
  ERROR,
}

enum class MapRenderStatus {
  LOADING,
  READY,
  ERROR,
}

/** Location remains optional and never changes the event-loading or map-rendering state. */
sealed interface MapLocationState {
  data object Idle : MapLocationState

  data object RequestPermission : MapLocationState

  data object Waiting : MapLocationState

  data object Locating : MapLocationState

  data class Available(val coordinates: Coordinates) : MapLocationState

  data object Denied : MapLocationState

  data object Unavailable : MapLocationState
}

data class MapUiState(
    val status: MapContentStatus = MapContentStatus.LOADING,
    val events: List<Event> = emptyList(),
    val selectedEvent: Event? = null,
    val renderStatus: MapRenderStatus = MapRenderStatus.LOADING,
    val renderGeneration: Int = 0,
    val selectedEventIsTonight: Boolean = false,
    val locationState: MapLocationState = MapLocationState.Idle,
) {
  /** Distance to the selected event, calculated outside Compose with pure haversine math. */
  val selectedDistanceMeters: Double?
    get() {
      val event = selectedEvent ?: return null
      val origin = (locationState as? MapLocationState.Available)?.coordinates ?: return null
      return straightLineDistanceMeters(origin, event.location)
    }
}

/**
 * Observes events, protects the public map and retains cached markers during transient failures.
 */
@HiltViewModel
class MapViewModel
@Inject
constructor(
    private val source: MapEventSource,
    private val locationService: LocationService,
    private val clock: Clock,
) : ViewModel() {
  private val mutableState = MutableStateFlow(MapUiState())
  val uiState: StateFlow<MapUiState> = mutableState.asStateFlow()
  private var observation: Job? = null
  private var locationRequest: Job? = null
  private var enteredMap = false
  private var mapActive = false

  init {
    observeEvents()
  }

  /**
   * Called only while the Map route is resumed; a permission revocation immediately hides badges.
   */
  fun onMapEntered() {
    mapActive = true
    if (!enteredMap) {
      enteredMap = true
      when {
        locationService.hasPermission() -> readLocation()
        locationService.shouldRequestPermission() -> setLocation(MapLocationState.RequestPermission)
        else -> setLocation(MapLocationState.Denied)
      }
      return
    }
    val location = mutableState.value.locationState
    if (!locationService.hasPermission()) {
      if (location != MapLocationState.RequestPermission && location != MapLocationState.Waiting) {
        locationRequest?.cancel()
        setLocation(MapLocationState.Denied)
      }
    } else if (
        location == MapLocationState.Idle ||
            location == MapLocationState.Denied ||
            location == MapLocationState.Waiting
    ) {
      readLocation()
    }
  }

  /** Persist before launching Android's dialog, including when it is dismissed or interrupted. */
  fun onPermissionRequested() {
    locationService.markPermissionRequested()
    setLocation(MapLocationState.Waiting)
  }

  /** Rechecks actual Android grants: approximate permission is enough for a distance badge. */
  fun onPermissionResult() {
    when {
      !locationService.hasPermission() -> {
        locationRequest?.cancel()
        setLocation(MapLocationState.Denied)
      }
      mapActive -> readLocation()
      else -> setLocation(MapLocationState.Idle)
    }
  }

  /**
   * An explicit student action can retry; returning to this tab does not repeat a prompt or fix.
   */
  fun turnOnLocation() {
    if (locationService.hasPermission()) readLocation()
    else setLocation(MapLocationState.RequestPermission)
  }

  /** Cancels a pending device request when the route leaves the foreground. */
  fun onMapInactive() {
    mapActive = false
    locationRequest?.cancel()
    if (
        mutableState.value.locationState == MapLocationState.Locating ||
            mutableState.value.locationState is MapLocationState.Available
    ) {
      setLocation(MapLocationState.Unavailable)
    }
  }

  private fun setLocation(location: MapLocationState) = mutableState.update {
    it.copy(locationState = location)
  }

  private fun readLocation() {
    if (!mapActive || mutableState.value.locationState == MapLocationState.Locating) return
    locationRequest?.cancel()
    setLocation(MapLocationState.Locating)
    locationRequest = viewModelScope.launch {
      val result =
          try {
            locationService.currentLocation()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            LocationResult.Unavailable
          }
      setLocation(
          when {
            !locationService.hasPermission() -> MapLocationState.Denied
            result is LocationResult.Available -> MapLocationState.Available(result.coordinates)
            result == LocationResult.PermissionDenied -> MapLocationState.Denied
            else -> MapLocationState.Unavailable
          }
      )
    }
  }

  fun selectEvent(id: String) = mutableState.update {
    val selected = it.events.firstOrNull { event -> event.id == id }
    it.copy(selectedEvent = selected, selectedEventIsTonight = isTonight(selected))
  }

  fun closePreview() = mutableState.update { it.copy(selectedEvent = null) }

  fun onRenderStatus(status: MapRenderStatus) = mutableState.update {
    it.copy(renderStatus = status)
  }

  fun retry() {
    val restartEvents = mutableState.value.status == MapContentStatus.ERROR
    mutableState.update {
      val restartRenderer = it.renderStatus == MapRenderStatus.ERROR
      it.copy(
          status = if (restartEvents) MapContentStatus.LOADING else it.status,
          renderStatus = if (restartRenderer) MapRenderStatus.LOADING else it.renderStatus,
          renderGeneration = it.renderGeneration + if (restartRenderer) 1 else 0,
      )
    }
    if (restartEvents) observeEvents()
  }

  private fun observeEvents() {
    observation?.cancel()
    observation = viewModelScope.launch {
      flow { emitAll(source.observePublicUpcomingEvents()) }
          .catch { emit(MapEventResult.Error) }
          .collect { result ->
            mutableState.update { previous ->
              when (result) {
                MapEventResult.Error -> previous.copy(status = MapContentStatus.ERROR)
                is MapEventResult.Events -> {
                  val events = publicMapEvents(result.events)
                  val selected = events.firstOrNull { it.id == previous.selectedEvent?.id }
                  previous.copy(
                      status =
                          if (events.isEmpty()) MapContentStatus.EMPTY else MapContentStatus.READY,
                      events = events,
                      selectedEvent = selected,
                      selectedEventIsTonight = isTonight(selected),
                  )
                }
              }
            }
          }
    }
  }

  private fun isTonight(event: Event?): Boolean =
      event != null &&
          event.startTime.atZone(MAP_TIME_ZONE).toLocalDate() ==
              LocalDate.now(clock.withZone(MAP_TIME_ZONE)) &&
          event.startTime.atZone(MAP_TIME_ZONE).hour >= 18
}

internal val MAP_TIME_ZONE: ZoneId = ZoneId.of("Europe/Zurich")

/** Rejects private and malformed records before coordinates reach the native renderer. */
internal fun publicMapEvents(events: List<Event>): List<Event> =
    events
        .filter {
          !it.isPrivate &&
              it.id.isNotBlank() &&
              it.location.latitude.isFinite() &&
              it.location.latitude in -90.0..90.0 &&
              it.location.longitude.isFinite() &&
              it.location.longitude in -180.0..180.0
        }
        .distinctBy { it.id }
