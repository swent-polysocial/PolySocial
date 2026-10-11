// Contributors: Claude (drafted the location picker ViewModel, #111).
package com.polysocial.ui.event.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.event.Coordinates
import com.polysocial.model.geocoding.GeocodingService
import com.polysocial.model.geocoding.Place
import com.polysocial.model.geocoding.PlaceSearchResult
import com.polysocial.model.geocoding.ReverseGeocodeResult
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
}

/**
 * Everything the location picker shows.
 *
 * @property query the text in the search field. Typing sends nothing; only
 *   [LocationPickerViewModel.onSearch] does.
 * @property pin where the pin is. The pin stays at the center of the map, so this is also where the
 *   map is centered.
 */
data class LocationPickerUiState(
    val query: String = "",
    val search: PlaceSearchState = PlaceSearchState.Idle,
    val pin: Coordinates = LocationPickerViewModel.LAUSANNE,
    val pinPlace: PinPlaceState = PinPlaceState.Resolving,
)

/**
 * Holds the state of the Create Event location picker: the place search and the pin.
 *
 * The student places the pin by moving the map, by tapping it (the map recenters there), or by
 * choosing a search result. A search is sent only when the student submits it. When the map stops
 * on a new position, the pin's name and address are looked up once, after [SETTLE_DELAY] so that a
 * quick series of moves sends a single lookup. Offline or failed lookups leave the pin usable, so
 * Confirm always works.
 */
@HiltViewModel
class LocationPickerViewModel @Inject constructor(private val geocoding: GeocodingService) :
    ViewModel() {

  private val _uiState = MutableStateFlow(LocationPickerUiState())
  val uiState: StateFlow<LocationPickerUiState> = _uiState.asStateFlow()

  private var opened = false
  private var searchJob: Job? = null
  private var lookupJob: Job? = null

  /**
   * Places the pin where the picker opens: on [initial], the place already on the form, without a
   * lookup, or else on Lausanne. Only the first call counts, so the screen can call it again after
   * a rotation.
   */
  fun open(initial: PickedLocation?) {
    if (opened) return
    opened = true
    if (initial == null) {
      lookUp(LAUSANNE)
    } else {
      val place = Place(initial.name, initial.address, initial.coordinates)
      _uiState.update { it.copy(pin = place.coordinates, pinPlace = PinPlaceState.Found(place)) }
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
                else PlaceSearchState.Results(result.places)
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
    _uiState.update {
      it.copy(
          search = PlaceSearchState.Idle,
          pin = place.coordinates,
          pinPlace = PinPlaceState.Found(place),
      )
    }
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
    lookUp(coordinates)
  }

  /**
   * The place to return to the Create Event form. A pin without a name yet (still looking up,
   * offline, or no address there) is called [unnamedName].
   */
  fun selection(unnamedName: String): PickedLocation {
    val state = _uiState.value
    val place = (state.pinPlace as? PinPlaceState.Found)?.place
    return PickedLocation(state.pin, place?.name ?: unnamedName, place?.address ?: "")
  }

  private fun lookUp(coordinates: Coordinates) {
    lookupJob?.cancel()
    _uiState.update { it.copy(pin = coordinates, pinPlace = PinPlaceState.Resolving) }
    lookupJob = viewModelScope.launch {
      delay(SETTLE_DELAY)
      val pinPlace =
          when (val result = geocoding.reverse(coordinates)) {
            is ReverseGeocodeResult.Found -> PinPlaceState.Found(result.place)
            ReverseGeocodeResult.NotFound -> PinPlaceState.Unnamed(failure = null)
            ReverseGeocodeResult.NetworkError -> PinPlaceState.Unnamed(LookupFailure.OFFLINE)
            ReverseGeocodeResult.UnexpectedError -> PinPlaceState.Unnamed(LookupFailure.UNEXPECTED)
          }
      _uiState.update { it.copy(pinPlace = pinPlace) }
    }
  }

  companion object {
    /** Where the picker opens when the form has no location yet: the center of Lausanne. */
    val LAUSANNE = Coordinates(46.5197, 6.6323)

    /** How long the map must stay still before the pin's address is looked up. */
    val SETTLE_DELAY = 500.milliseconds

    /** About a metre in latitude, and less in longitude at Lausanne's latitude. */
    private const val SAME_PLACE_DEGREES = 0.00001
  }
}
