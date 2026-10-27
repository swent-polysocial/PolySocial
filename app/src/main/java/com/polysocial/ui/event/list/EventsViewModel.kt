// Contributors: Claude (state of the Events tab's list, #105; offline only when the device is,
// after review; search and filters, and the one-off location and its permission for the distance
// filter, #115; the names of the associations whose events it lists, #128).
package com.polysocial.ui.event.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.AssociationNames
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.PublicEventsResult
import com.polysocial.model.event.filterEvents
import com.polysocial.model.location.LocationResult
import com.polysocial.model.location.LocationService
import com.polysocial.model.network.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the Events tab shows: its list, the search and filters it is narrowed by, and where the
 * student is for the distance filter.
 */
data class EventsUiState(
    val list: EventsListState = EventsListState.Loading,
    val selection: EventSelection = EventSelection(),
    val location: EventsLocationState = EventsLocationState.NotRead,
    /** The names read so far of the associations whose events are listed, by UID (#128). */
    val associationNames: Map<String, String> = emptyMap(),
)

/**
 * The student's location, which only the distance filter needs. It is read once, when the student
 * picks a distance, and kept in memory only (nLPD): never saved, sent or logged.
 */
sealed interface EventsLocationState {
  /**
   * Not read yet: no distance has been picked, or the tab left the foreground while reading it
   * ([EventsViewModel.onPause]).
   */
  data object NotRead : EventsLocationState

  /** Reading it; until it arrives, the picked distance is not applied yet. */
  data object Locating : EventsLocationState

  data class Available(val coordinates: Coordinates) : EventsLocationState

  /**
   * The permission is not granted, or there was no fix (location turned off, or a timeout). The
   * distance filters are unavailable, and no distance is selected.
   */
  data object Off : EventsLocationState
}

/** What the screen must do after *Turn on* ([EventsViewModel.turnOnLocation]). */
enum class LocationAction {
  /** Nothing: the permission is granted, and the location is being read again. */
  NONE,

  /** Show Android's permission dialog, then call [EventsViewModel.onPermissionResult]. */
  REQUEST_PERMISSION,

  /** Android won't show the dialog again, so open the app's settings. */
  OPEN_SETTINGS,
}

/** The Events tab's list. */
sealed interface EventsListState {
  /** The first result has not arrived yet. */
  data object Loading : EventsListState

  /** There are no upcoming public events. */
  data class Empty(val isOffline: Boolean) : EventsListState

  /** There are upcoming public events, but none match the search and filters. */
  data class NoMatch(val isOffline: Boolean) : EventsListState

  /**
   * The upcoming public events that match the search and filters, grouped by day. [today] is the
   * current date, so the screen can name today's and tomorrow's sections.
   */
  data class Events(
      val sections: List<EventDaySection>,
      val today: LocalDate,
      val isOffline: Boolean,
  ) : EventsListState

  /** The events could not be loaded; the screen offers Retry. */
  data object Error : EventsListState
}

/**
 * Observes the upcoming public events from [EventRepository] and groups the ones matching the
 * student's [EventSelection] by day for the Events tab. The list updates whenever the events or the
 * selection change; filtering runs on the loaded events (`filterEvents`), so it needs no new
 * request and works offline. `isOffline` is true when the events come from the offline cache and
 * [network] says the device is offline, so the screen can say they may be out of date. The cache
 * alone isn't enough: Firestore's first snapshot comes from it even online, until the server
 * answers.
 *
 * The selection is kept in [savedState], so it survives rotation and the process being recreated.
 *
 * The distance filter needs the student's location: [locationService] reads it once, the first time
 * a distance is picked, and only if the permission is already granted. Without it, the location is
 * [EventsLocationState.Off] and the distance is dropped, so the selection never holds a distance
 * that can't apply. Only *Turn on* asks for the permission ([turnOnLocation]); [onResume] notices a
 * permission granted or revoked in the settings. The location is only read in the foreground:
 * [onPause] cancels a read still running, and [onResume] starts it again.
 *
 * After an error the repository stops emitting, so the list stays in [EventsListState.Error] until
 * [retry].
 *
 * The cards of an association's events show its name (#128): it is read once per association
 * through the shared [associationNames], whatever the events or the selection do, and a name that
 * can't be read is left out until events arrive from the server again.
 *
 * @param clock gives the current date and time zone for the day sections and the date filters, so
 *   tests can fix them.
 */
@HiltViewModel
class EventsViewModel
@Inject
constructor(
    private val repository: EventRepository,
    private val network: NetworkMonitor,
    private val locationService: LocationService,
    private val clock: Clock,
    private val savedState: SavedStateHandle,
    private val associationNames: AssociationNames,
) : ViewModel() {
  private val _uiState = MutableStateFlow(EventsUiState(selection = restoreSelection()))
  val uiState: StateFlow<EventsUiState> = _uiState.asStateFlow()

  /** The time zone of the day headings, in which the screen also shows the start times. */
  val zone: ZoneId
    get() = clock.zone

  /** The current date, from which the Filters sheet lists the next 14 days. */
  val today: LocalDate
    get() = LocalDate.now(clock)

  /** The events last loaded; null while loading or after an error. */
  private var loaded: Loaded? = null
  private var observation: Job? = null
  private var locationRequest: Job? = null

  /** The associations whose names this tab asked for, so each is asked for once. */
  private val requestedNames = mutableSetOf<String>()

  init {
    viewModelScope.launch {
      associationNames.names.collect { names ->
        _uiState.value = _uiState.value.copy(associationNames = names)
      }
    }
    when {
      !locationService.hasPermission() -> locationOff()
      selection().maxDistanceMeters != null -> readLocation()
    }
    observeEvents()
  }

  /** Loads the events again after an error. Does nothing in any other state. */
  fun retry() {
    if (_uiState.value.list != EventsListState.Error) return
    _uiState.value = _uiState.value.copy(list = EventsListState.Loading)
    observeEvents()
  }

  /** Searches the event titles for [search]. */
  fun setSearch(search: String) = select(selection().copy(search = search))

  /** Adds [category] to the selected categories, or removes it if it was selected. */
  fun toggleCategory(category: EventCategory) {
    val categories = selection().categories
    select(
        selection()
            .copy(
                categories =
                    if (category in categories) categories - category else categories + category
            )
    )
  }

  /** Keeps only the events starting on [date]. */
  fun selectDate(date: DateChoice) = select(selection().copy(date = date))

  /**
   * Keeps only the events within [meters] of the student, or any distance if null. The first
   * distance picked reads the location; while the location is off, a distance can't be picked.
   */
  fun selectDistance(meters: Int?) {
    if (meters != null && location() == EventsLocationState.Off) return
    select(selection().copy(maxDistanceMeters = meters))
  }

  /**
   * *Turn on* for the distance filters. With the permission, it reads the location again (it was
   * unavailable). Without it, the screen asks for it if Android still can, which it can the first
   * time or when [showRationale] (Android's `shouldShowRequestPermissionRationale`) is true, and
   * opens the app's settings otherwise.
   */
  fun turnOnLocation(showRationale: Boolean): LocationAction =
      when {
        locationService.hasPermission() -> {
          readLocation()
          LocationAction.NONE
        }
        locationService.shouldRequestPermission() || showRationale -> {
          locationService.markPermissionRequested()
          LocationAction.REQUEST_PERMISSION
        }
        else -> LocationAction.OPEN_SETTINGS
      }

  /** After the permission dialog: if granted, the distance filters can be used. */
  fun onPermissionResult() = onResume()

  /**
   * When the tab comes back to the foreground, maybe from the settings: a newly granted permission
   * turns the distance filters back on, and a revoked one turns them off. A read cancelled by
   * [onPause] starts again.
   */
  fun onResume() {
    val granted = locationService.hasPermission()
    when {
      granted && location() == EventsLocationState.Off ->
          show(selection(), EventsLocationState.NotRead)
      granted &&
          location() == EventsLocationState.NotRead &&
          selection().maxDistanceMeters != null -> readLocation()
      !granted && location() != EventsLocationState.Off -> {
        locationRequest?.cancel()
        locationOff()
      }
    }
  }

  /**
   * When the tab leaves the foreground: a location read still running is cancelled, since the
   * location is only read in the foreground. The picked distance stays, and [onResume] reads again.
   */
  fun onPause() {
    if (location() != EventsLocationState.Locating) return
    locationRequest?.cancel()
    show(selection(), EventsLocationState.NotRead)
  }

  /** Clears every filter but keeps the search: the Filters sheet's *Clear*. */
  fun clearFilters() = select(EventSelection(search = selection().search))

  /** Clears the search and every filter, showing all the events again. */
  fun clearSearchAndFilters() = select(EventSelection())

  private fun selection() = _uiState.value.selection

  private fun location() = _uiState.value.location

  private fun select(selection: EventSelection) {
    savedState[KEY_SEARCH] = selection.search
    savedState[KEY_CATEGORIES] = ArrayList(selection.categories.map { it.name })
    savedState[KEY_DATE] = selection.date.saved()
    savedState[KEY_DISTANCE] = selection.maxDistanceMeters
    show(selection, location())
    when {
      selection.maxDistanceMeters != null && location() == EventsLocationState.NotRead ->
          readLocation()
      selection.maxDistanceMeters == null && location() == EventsLocationState.Locating -> {
        locationRequest?.cancel()
        show(selection, EventsLocationState.NotRead)
      }
    }
  }

  /** Shows [selection] and [location], and the list they give. */
  private fun show(selection: EventSelection, location: EventsLocationState) {
    val list = loaded?.let { listFor(it, selection, location) } ?: _uiState.value.list
    _uiState.value = _uiState.value.copy(list = list, selection = selection, location = location)
  }

  /** Reads the location once; on any failure, the location is off. */
  private fun readLocation() {
    if (!locationService.hasPermission()) return locationOff()
    show(selection(), EventsLocationState.Locating)
    locationRequest = viewModelScope.launch {
      val result =
          try {
            locationService.currentLocation()
          } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            LocationResult.Unavailable
          }
      if (result is LocationResult.Available) {
        show(selection(), EventsLocationState.Available(result.coordinates))
      } else {
        locationOff()
      }
    }
  }

  private fun locationOff() {
    savedState[KEY_DISTANCE] = null
    show(selection().copy(maxDistanceMeters = null), EventsLocationState.Off)
  }

  private fun restoreSelection() =
      EventSelection(
          search = savedState.get<String>(KEY_SEARCH).orEmpty(),
          categories =
              savedState
                  .get<ArrayList<String>>(KEY_CATEGORIES)
                  .orEmpty()
                  .mapNotNull { name -> EventCategory.entries.firstOrNull { it.name == name } }
                  .toSet(),
          date = restoreDate(savedState.get<String>(KEY_DATE)),
          maxDistanceMeters =
              savedState.get<Int>(KEY_DISTANCE)?.takeIf { it in DISTANCE_CHOICES_METERS },
      )

  private fun observeEvents() {
    observation?.cancel()
    loaded = null
    observation = viewModelScope.launch {
      repository.getUpcomingPublicEvents().collect { result ->
        val list =
            when (result) {
              PublicEventsResult.Error -> {
                loaded = null
                EventsListState.Error
              }
              is PublicEventsResult.Events -> {
                loadNamesOf(result.events, retryUnknown = !result.fromCache)
                val events = Loaded(result.events, result.fromCache && !network.isOnline())
                loaded = events
                listFor(events, selection(), location())
              }
            }
        _uiState.value = _uiState.value.copy(list = list)
      }
    }
  }

  private fun listFor(
      loaded: Loaded,
      selection: EventSelection,
      location: EventsLocationState,
  ): EventsListState {
    val isOffline = loaded.isOffline
    if (loaded.events.isEmpty()) return EventsListState.Empty(isOffline)
    val today = LocalDate.now(clock)
    val userLocation = (location as? EventsLocationState.Available)?.coordinates
    val matching = filterEvents(loaded.events, selection.toFilter(today), clock.zone, userLocation)
    return if (matching.isEmpty()) EventsListState.NoMatch(isOffline)
    else EventsListState.Events(groupEventsByDay(matching, clock), today, isOffline)
  }

  /**
   * Asks once for the name of each association with an event in [events]. With [retryUnknown]
   * (events fresh from the server, so the device is online), names still unknown, for example
   * because the tab opened offline, are asked for again; a known name never is.
   */
  private fun loadNamesOf(events: List<Event>, retryUnknown: Boolean) {
    for (uid in events.filter { it.isAssociationEvent }.map { it.createdBy }.toSet()) {
      val retry = retryUnknown && uid !in _uiState.value.associationNames
      if (requestedNames.add(uid) || retry) viewModelScope.launch { associationNames.load(uid) }
    }
  }

  /** Events from the repository, and whether they are an offline copy. */
  private class Loaded(val events: List<Event>, val isOffline: Boolean)

  /** The date choice as text for the saved state: its name, or the picked day (ISO 8601). */
  private fun DateChoice.saved(): String =
      when (this) {
        DateChoice.Any -> "ANY"
        DateChoice.Today -> "TODAY"
        DateChoice.ThisWeek -> "THIS_WEEK"
        is DateChoice.Day -> date.toString()
      }

  /** The opposite of [saved]; anything it doesn't recognize means any day. */
  private fun restoreDate(saved: String?): DateChoice =
      when (saved) {
        null,
        "ANY" -> DateChoice.Any
        "TODAY" -> DateChoice.Today
        "THIS_WEEK" -> DateChoice.ThisWeek
        else ->
            try {
              DateChoice.Day(LocalDate.parse(saved))
            } catch (_: DateTimeParseException) {
              DateChoice.Any
            }
      }

  private companion object {
    const val KEY_SEARCH = "events_search"
    const val KEY_CATEGORIES = "events_categories"
    const val KEY_DATE = "events_date"
    const val KEY_DISTANCE = "events_distance"
  }
}
