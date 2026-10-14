// Contributors: Claude (drafted the location picker ViewModel, #111; your location, the location
// button, confirming and duplicate results for the map screen, #111).
package com.polysocial.ui.event.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.event.Coordinates
import com.polysocial.model.geocoding.GeocodingService
import com.polysocial.model.geocoding.Place
import com.polysocial.model.geocoding.PlaceSearchResult
import com.polysocial.model.geocoding.ReverseGeocodeResult
import com.polysocial.model.location.LocationResult
import com.polysocial.model.location.LocationService
import com.polysocial.model.location.straightLineDistanceMeters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a place lookup gave no answer. */
enum class LookupFailure {
  OFFLINE,
  UNEXPECTED,
}

/** The place search at the top of the location picker. */
sealed interface PlaceSearchState {
  /** No results shown: nothing searched yet, or the results were closed. */
  data object Idle : PlaceSearchState

  data object Searching : PlaceSearchState

  /** The places found, best match first. Never empty: see [NoResults]. */
  data class Results(val places: List<Place>) : PlaceSearchState

  data object NoResults : PlaceSearchState

  /** The search failed. The pin still works, so the student can place it by hand. */
  data class Failed(val reason: LookupFailure) : PlaceSearchState
}

/** What the card at the bottom of the picker says about the pin. */
sealed interface PinPlaceState {
  /** The pin's address is being looked up. */
  data object Resolving : PinPlaceState

  data class Found(val place: Place) : PinPlaceState

  /**
   * The pin has no name: there is no address there ([failure] is null), or the lookup failed. The
   * pin can still be confirmed.
   */
  data class Unnamed(val failure: LookupFailure?) : PinPlaceState

  /**
   * The pin is on the student's own position. It is not looked up, so the position is never sent to
   * a third party unless the student moves the map or confirms it.
   */
  data object YourLocation : PinPlaceState
}

/**
 * Asks the map to show [target]: the pin was placed by the app (where the picker opens, a search
 * result, the student's position), not by moving the map. Each request has a new [id], so the same
 * target can be asked for twice.
 */
data class CameraRequest(val target: Coordinates, val id: Int)

/**
 * Everything the location picker shows.
 *
 * @property query the text in the search field. Typing sends nothing; only
 *   [LocationPickerViewModel.onSearch] does.
 * @property pin where the pin is. The pin stays at the center of the map, so this is also where the
 *   map is centered.
 * @property hasLocationAccess whether the app may read the student's position. Without it, the
 *   picker says so and hides the location button.
 * @property camera where the map must move: the pin when the app placed it.
 * @property confirming "Use this location" was tapped while the pin's address was still unknown:
 *   the picker confirms once the lookup ends.
 * @property confirmed the student confirmed the pin: [LocationPickerViewModel.selection] is ready.
 */
data class LocationPickerUiState(
    val query: String = "",
    val search: PlaceSearchState = PlaceSearchState.Idle,
    val pin: Coordinates = LocationPickerViewModel.LAUSANNE,
    val pinPlace: PinPlaceState = PinPlaceState.Resolving,
    val hasLocationAccess: Boolean = false,
    val camera: CameraRequest = CameraRequest(LocationPickerViewModel.LAUSANNE, 0),
    val confirming: Boolean = false,
    val confirmed: Boolean = false,
)

/**
 * Holds the state of the Create Event location picker: the place search and the pin.
 *
 * The student places the pin by moving the map, by tapping it (the map recenters there), or by
 * choosing a search result. A search is sent only when the student submits it. When the map stops
 * on a new position, the pin's name and address are looked up once, after [SETTLE_DELAY] so that a
 * quick series of moves sends a single lookup. Offline or failed lookups leave the pin usable, so
 * Confirm always works.
 *
 * With location access, the picker opens on the student's position, which is never looked up on its
 * own: only moving the map or confirming sends it to the geocoding service. The picker never asks
 * for the permission itself.
 */
@HiltViewModel
class LocationPickerViewModel
@Inject
constructor(
    private val geocoding: GeocodingService,
    private val location: LocationService,
) : ViewModel() {

  private val _uiState = MutableStateFlow(LocationPickerUiState())
  val uiState: StateFlow<LocationPickerUiState> = _uiState.asStateFlow()

  private var opened = false
  private var searchJob: Job? = null
  private var lookupJob: Job? = null
  private var locateJob: Job? = null

  /**
   * Places the pin where the picker opens: on [initial], the place already on the form, or else on
   * Lausanne. A named [initial] place is shown as it is, without a lookup; an unnamed one
   * (confirmed while its lookup failed) is looked up again. Only the first call counts, so the
   * screen can call it again after a rotation.
   */
  fun open(initial: PickedLocation?) {
    if (opened) return
    opened = true
    val hasAccess = location.hasPermission()
    _uiState.update { it.copy(hasLocationAccess = hasAccess) }
    when {
      initial != null && initial.isNamed -> {
        val place = Place(initial.name, initial.address, initial.coordinates)
        _uiState.update { it.copy(pinPlace = PinPlaceState.Found(place)) }
        moveCamera(place.coordinates)
      }
      initial != null -> {
        moveCamera(initial.coordinates)
        lookUp(initial.coordinates)
      }
      hasAccess -> locate(fallback = { lookUp(LAUSANNE) })
      else -> lookUp(LAUSANNE)
    }
  }

  /**
   * The location button: moves the pin to the student's position, without looking it up. Without
   * the permission, the picker shows that location access is off instead. If no position can be
   * read (location turned off, timeout), the pin stays where it is.
   */
  fun onMyLocation() {
    if (!location.hasPermission()) {
      _uiState.update { it.copy(hasLocationAccess = false) }
      return
    }
    locate(fallback = {})
  }

  /** Reads the permission again, for example when the student comes back from Settings. */
  fun onLocationAccessCheck() = _uiState.update {
    it.copy(hasLocationAccess = location.hasPermission())
  }

  /**
   * "Use this location": confirms the pin. While its address is unknown (still looking up, or the
   * student's own position, which was never sent), it is looked up first and the picker confirms
   * when the answer comes, so the form gets the place's name whenever there is one.
   */
  fun onUseLocation() {
    when (_uiState.value.pinPlace) {
      PinPlaceState.Resolving -> _uiState.update { it.copy(confirming = true) }
      PinPlaceState.YourLocation -> {
        _uiState.update { it.copy(confirming = true) }
        lookUp(_uiState.value.pin, wait = false)
      }
      else -> _uiState.update { it.copy(confirmed = true) }
    }
  }

  fun onQueryChange(query: String) = _uiState.update { it.copy(query = query) }

  /** Searches the current query. Does nothing for a blank query or while a search is running. */
  fun onSearch() {
    val query = _uiState.value.query.trim()
    if (query.isEmpty() || _uiState.value.search == PlaceSearchState.Searching) return
    _uiState.update { it.copy(search = PlaceSearchState.Searching) }
    searchJob = viewModelScope.launch {
      val search =
          when (val result = geocoding.search(query)) {
            is PlaceSearchResult.Found ->
                if (result.places.isEmpty()) PlaceSearchState.NoResults
                else PlaceSearchState.Results(withoutNearbyDuplicates(result.places))
            PlaceSearchResult.NetworkError -> PlaceSearchState.Failed(LookupFailure.OFFLINE)
            PlaceSearchResult.UnexpectedError -> PlaceSearchState.Failed(LookupFailure.UNEXPECTED)
          }
      _uiState.update { it.copy(search = search) }
    }
  }

  /** Closes the results or the search error, cancelling a running search. */
  fun onSearchDismiss() {
    searchJob?.cancel()
    _uiState.update { it.copy(search = PlaceSearchState.Idle) }
  }

  /** Moves the pin to [place]. Its name is already known, so nothing is looked up. */
  fun onResultSelect(place: Place) {
    searchJob?.cancel()
    lookupJob?.cancel()
    locateJob?.cancel()
    _uiState.update {
      it.copy(
          search = PlaceSearchState.Idle,
          pin = place.coordinates,
          pinPlace = PinPlaceState.Found(place),
          confirming = false,
      )
    }
    moveCamera(place.coordinates)
  }

  /**
   * Called when the map stops moving, with its center: the pin's new position. A position within
   * about a metre of the pin is ignored, so the map settling on a chosen result keeps its name.
   */
  fun onPinMove(coordinates: Coordinates) {
    val pin = _uiState.value.pin
    if (
        abs(coordinates.latitude - pin.latitude) < SAME_PLACE_DEGREES &&
            abs(coordinates.longitude - pin.longitude) < SAME_PLACE_DEGREES
    ) {
      return
    }
    // The student chose another spot: a pending confirmation or position read no longer applies.
    locateJob?.cancel()
    _uiState.update { it.copy(confirming = false) }
    lookUp(coordinates)
  }

  /**
   * The place to return to the Create Event form. A pin without a name yet (still looking up,
   * offline, or no address there) is called [unnamedName].
   */
  fun selection(unnamedName: String): PickedLocation {
    val state = _uiState.value
    val place = (state.pinPlace as? PinPlaceState.Found)?.place
    return PickedLocation(
        coordinates = state.pin,
        name = place?.name ?: unnamedName,
        address = place?.address ?: "",
        isNamed = place != null,
    )
  }

  /** Looks up the pin at [coordinates], after [SETTLE_DELAY] unless [wait] is false. */
  private fun lookUp(coordinates: Coordinates, wait: Boolean = true) {
    lookupJob?.cancel()
    _uiState.update { it.copy(pin = coordinates, pinPlace = PinPlaceState.Resolving) }
    lookupJob = viewModelScope.launch {
      if (wait) delay(SETTLE_DELAY)
      val pinPlace =
          when (val result = geocoding.reverse(coordinates)) {
            is ReverseGeocodeResult.Found -> PinPlaceState.Found(result.place)
            ReverseGeocodeResult.NotFound -> PinPlaceState.Unnamed(failure = null)
            ReverseGeocodeResult.NetworkError -> PinPlaceState.Unnamed(LookupFailure.OFFLINE)
            ReverseGeocodeResult.UnexpectedError -> PinPlaceState.Unnamed(LookupFailure.UNEXPECTED)
          }
      _uiState.update {
        it.copy(pinPlace = pinPlace, confirmed = it.confirmed || it.confirming, confirming = false)
      }
    }
  }

  /**
   * Reads the student's position once and moves the pin there, as [PinPlaceState.YourLocation].
   * Without a position, calls [fallback]; a denied permission also hides the location button.
   */
  private fun locate(fallback: () -> Unit) {
    locateJob?.cancel()
    locateJob = viewModelScope.launch {
      when (val result = location.currentLocation()) {
        is LocationResult.Available -> {
          moveCamera(result.coordinates)
          if (_uiState.value.confirming) {
            // Confirmed while the position was being read: look it up now, then confirm.
            lookUp(result.coordinates, wait = false)
          } else {
            lookupJob?.cancel()
            _uiState.update { it.copy(pinPlace = PinPlaceState.YourLocation) }
          }
        }
        LocationResult.PermissionDenied -> {
          _uiState.update { it.copy(hasLocationAccess = false) }
          fallback()
        }
        LocationResult.Unavailable -> fallback()
      }
    }
  }

  private fun moveCamera(target: Coordinates) = _uiState.update {
    it.copy(pin = target, camera = CameraRequest(target, it.camera.id + 1))
  }

  companion object {
    /** Where the picker opens when the form has no location yet: the center of Lausanne. */
    val LAUSANNE = Coordinates(46.5197, 6.6323)

    /** How long the map must stay still before the pin's address is looked up. */
    val SETTLE_DELAY = 500.milliseconds

    /** About a metre in latitude, and less in longitude at Lausanne's latitude. */
    private const val SAME_PLACE_DEGREES = 0.00001

    /** Two results with the same name closer than this are the same place. */
    internal const val DUPLICATE_METERS = 100.0
  }
}

/**
 * Drops a place with the same name as an earlier one within [DUPLICATE_METERS], which Nominatim
 * returns for a building and its entrance, for example. The first one, the better match, stays.
 */
internal fun withoutNearbyDuplicates(places: List<Place>): List<Place> =
    places.fold(emptyList()) { kept, place ->
      val duplicate = kept.any {
        it.name == place.name &&
            straightLineDistanceMeters(it.coordinates, place.coordinates) <
                LocationPickerViewModel.DUPLICATE_METERS
      }
      if (duplicate) kept else kept + place
    }
