// Contributors: OpenAI Codex (map state, selection, day labels and failure handling for #50;
// one-off location permission and distance state for #51).
// Contributors: Claude (the names of the associations with events on the map, #128; the search
// and filters on the loaded events, #116).
package com.polysocial.ui.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.AssociationNames
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.filterEvents
import com.polysocial.model.location.LocationResult
import com.polysocial.model.location.LocationService
import com.polysocial.model.location.straightLineDistanceMeters
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import com.polysocial.ui.event.list.DateChoice
import com.polysocial.ui.event.list.EventSelection
import com.polysocial.ui.event.list.EventsLocationState
import com.polysocial.ui.event.list.restoreEventSelection
import com.polysocial.ui.event.list.saveTo
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

  /** There are events, but none matches the search and filters (#116). */
  NO_MATCH,
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

  data object OpenSettings : MapLocationState
}

data class MapUiState(
    val status: MapContentStatus = MapContentStatus.LOADING,
    /** The events with a marker: the loaded ones that match [selection]. */
    val events: List<Event> = emptyList(),
    val selectedEvent: Event? = null,
    val renderStatus: MapRenderStatus = MapRenderStatus.LOADING,
    val renderGeneration: Int = 0,
    val selectedEventIsTonight: Boolean = false,
    val selectedEventIsToday: Boolean = false,
    val locationState: MapLocationState = MapLocationState.Idle,
    /** The names read so far of the associations with events on the map, by UID (#128). */
    val associationNames: Map<String, String> = emptyMap(),
    /** The search and filters, separate from the Events tab's (#113). */
    val selection: EventSelection = EventSelection(),
) {
  /**
   * The location as the shared Filters sheet shows it: the distance chips work only with a fix, and
   * a fix being read shows as such; otherwise they are off, with *Turn on*.
   */
  val filterLocation: EventsLocationState
    get() =
        when (locationState) {
          is MapLocationState.Available -> EventsLocationState.Available(locationState.coordinates)
          MapLocationState.Locating,
          MapLocationState.Waiting -> EventsLocationState.Locating
          else -> EventsLocationState.Off
        }

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
 * The search and filters (#116) run [filterEvents] on the events already loaded, so a change needs
 * no new request and works offline; they are kept in [savedState], so they survive rotation. A
 * distance applies only with a location fix, and a lost permission or fix clears it, as on the
 * Events tab. Coming back to the map with a distance picked reads the location again, once. The
 * name of each association with events on the map is read once, through the shared
 * [associationNames], so its preview shows it (#128); a name that can't be read is left out, and
 * tried again when its preview opens.
 */
@HiltViewModel
class MapViewModel
@Inject
constructor(
    private val source: MapEventSource,
    private val locationService: LocationService,
    private val clock: Clock,
    private val associationNames: AssociationNames,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
  private val mutableState =
      MutableStateFlow(MapUiState(selection = restoreEventSelection(savedState, SAVED_PREFIX)))
  val uiState: StateFlow<MapUiState> = mutableState.asStateFlow()
  private var observation: Job? = null
  private var locationRequest: Job? = null
  private var enteredMap = false
  private var mapActive = false
  private var readAfterPermissionResult = false

  /** The public events last loaded, before the search and filters; null until loaded. */
  private var loadedEvents: List<Event>? = null

  /** The associations whose names the map asked for, so each is asked for once. */
  private val requestedNames = mutableSetOf<String>()

  init {
    viewModelScope.launch {
      associationNames.names.collect { names ->
        mutableState.update { it.copy(associationNames = names) }
      }
    }
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
        (location == MapLocationState.Idle &&
            (readAfterPermissionResult || selection().maxDistanceMeters != null)) ||
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
      else -> {
        readAfterPermissionResult = true
        setLocation(MapLocationState.Idle)
      }
    }
  }

  /**
   * An explicit student action can retry; returning to this tab does not repeat a prompt or fix.
   */
  fun turnOnLocation(canRequestPermission: Boolean = true) {
    if (locationService.hasPermission()) readLocation()
    else
        setLocation(
            if (canRequestPermission) MapLocationState.RequestPermission
            else MapLocationState.OpenSettings
        )
  }

  /** Consumes the settings action before Android pauses the route. */
  fun onSettingsOpened() = setLocation(MapLocationState.Denied)

  /** Cancels a pending device request when the route leaves the foreground. */
  fun onMapInactive() {
    mapActive = false
    locationRequest?.cancel()
    if (
        mutableState.value.locationState == MapLocationState.Locating ||
            mutableState.value.locationState is MapLocationState.Available
    ) {
      setLocation(MapLocationState.Idle)
    }
  }

  private fun setLocation(location: MapLocationState) {
    mutableState.update { it.copy(locationState = location) }
    val lost =
        location == MapLocationState.Denied ||
            location == MapLocationState.Unavailable ||
            location == MapLocationState.OpenSettings
    // A distance can't be measured anymore: clear it rather than silently ignore it.
    if (lost && selection().maxDistanceMeters != null) {
      select(selection().copy(maxDistanceMeters = null))
    } else {
      showMatching()
    }
  }

  fun setSearch(search: String) = select(selection().copy(search = search))

  /** Adds [category] to the categories shown, or removes it if it is already there. */
  fun toggleCategory(category: EventCategory) {
    val categories = selection().categories
    val toggled = if (category in categories) categories - category else categories + category
    select(selection().copy(categories = toggled))
  }

  fun selectDate(date: DateChoice) = select(selection().copy(date = date))

  /** Keeps the events within [meters] of the student, or any distance if null; needs a fix. */
  fun selectDistance(meters: Int?) {
    if (meters != null && mutableState.value.locationState !is MapLocationState.Available) return
    select(selection().copy(maxDistanceMeters = meters))
  }

  /** The Filters sheet's *Clear*: every filter, but not the search. */
  fun clearFilters() = select(EventSelection(search = selection().search))

  /** *All* and *Clear filters*: the search and every filter, so every marker shows again. */
  fun clearSearchAndFilters() = select(EventSelection())

  /** The Filters sheet's day strip starts on this date. */
  val today: LocalDate
    get() = LocalDate.now(clock)

  private fun selection() = mutableState.value.selection

  private fun select(selection: EventSelection) {
    selection.saveTo(savedState, SAVED_PREFIX)
    mutableState.update { it.copy(selection = selection) }
    showMatching()
  }

  /**
   * Shows the loaded events that match the selection, and closes the preview of an event that no
   * longer does. Before the events are loaded, the state stays as it is. [freshEvents] means
   * [loadedEvents] just arrived, which ends an error or loading state, in the same update so no
   * collector sees the new status with the old markers.
   */
  private fun showMatching(freshEvents: Boolean = false) {
    val loaded = loadedEvents ?: return
    mutableState.update { current ->
      val previous = if (freshEvents) current.copy(status = MapContentStatus.READY) else current
      val userLocation = (previous.locationState as? MapLocationState.Available)?.coordinates
      val events =
          filterEvents(loaded, previous.selection.toFilter(today), clock.zone, userLocation)
      val selected = events.firstOrNull { it.id == previous.selectedEvent?.id }
      previous.copy(
          status =
              when {
                // Still loading after Retry, or failed: the cached markers are filtered too.
                previous.status == MapContentStatus.ERROR ||
                    previous.status == MapContentStatus.LOADING -> previous.status
                loaded.isEmpty() -> MapContentStatus.EMPTY
                events.isEmpty() -> MapContentStatus.NO_MATCH
                else -> MapContentStatus.READY
              },
          events = events,
          selectedEvent = selected,
          selectedEventIsTonight = isTonight(selected),
          selectedEventIsToday = isToday(selected),
      )
    }
  }

  private fun readLocation() {
    if (!mapActive || mutableState.value.locationState == MapLocationState.Locating) return
    readAfterPermissionResult = false
    locationRequest?.cancel()
    setLocation(MapLocationState.Locating)
    locationRequest = viewModelScope.launch {
      val result =
          try {
            locationService.currentLocation()
          } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
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

  fun selectEvent(id: String) {
    val event = mutableState.value.events.firstOrNull { it.id == id }
    // A name that couldn't be read (say the map opened offline) is tried again when its preview
    // opens; the shared names never read a known one twice.
    if (
        event != null &&
            event.isAssociationEvent &&
            event.createdBy !in mutableState.value.associationNames
    ) {
      viewModelScope.launch { associationNames.load(event.createdBy) }
    }
    showPreview(id)
  }

  private fun showPreview(id: String) = mutableState.update {
    val selected = it.events.firstOrNull { event -> event.id == id }
    it.copy(
        selectedEvent = selected,
        selectedEventIsTonight = isTonight(selected),
        selectedEventIsToday = isToday(selected),
    )
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
            when (result) {
              MapEventResult.Error ->
                  mutableState.update { it.copy(status = MapContentStatus.ERROR) }
              is MapEventResult.Events -> {
                val events = publicMapEvents(result.events)
                loadNamesOf(events)
                loadedEvents = events
                showMatching(freshEvents = true)
              }
            }
          }
    }
  }

  /** Asks once for the name of each association with an event in [events]. */
  private fun loadNamesOf(events: List<Event>) {
    for (uid in events.filter { it.isAssociationEvent }.map { it.createdBy }.toSet()) {
      if (requestedNames.add(uid)) viewModelScope.launch { associationNames.load(uid) }
    }
  }

  private fun isToday(event: Event?): Boolean =
      event != null &&
          event.startTime.atZone(MAP_TIME_ZONE).toLocalDate() ==
              LocalDate.now(clock.withZone(MAP_TIME_ZONE))

  private fun isTonight(event: Event?): Boolean =
      event != null && isToday(event) && event.startTime.atZone(MAP_TIME_ZONE).hour >= 18
}

/** The Map tab's saved-state keys start with it (map_search...), apart from the Events tab's. */
private const val SAVED_PREFIX = "map"

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
