// Contributors: Claude (drafted the geocoding interface, #111).
package com.polysocial.model.geocoding

import com.polysocial.model.event.Coordinates

/**
 * A named place on the map, as the location picker shows it.
 *
 * @property name the place's own name ("Rolex Learning Center"), or its street when it has none.
 * @property address the rest of its address ("Route Cantonale, 1015 Ecublens"), possibly blank.
 */
data class Place(val name: String, val address: String, val coordinates: Coordinates)

/** Outcome of [GeocodingService.search]. */
sealed interface PlaceSearchResult {
  /** The places matching the query, best match first. Empty when nothing matched. */
  data class Found(val places: List<Place>) : PlaceSearchResult

  /** The device is offline, so no request was sent, or the request couldn't reach the server. */
  data object NetworkError : PlaceSearchResult

  /** Any other failure, for example an error response from the server. */
  data object UnexpectedError : PlaceSearchResult
}

/** Outcome of [GeocodingService.reverse]. */
sealed interface ReverseGeocodeResult {
  data class Found(val place: Place) : ReverseGeocodeResult

  /** The server knows no address there, for example in the middle of the lake. */
  data object NotFound : ReverseGeocodeResult

  /** The device is offline, so no request was sent, or the request couldn't reach the server. */
  data object NetworkError : ReverseGeocodeResult

  /** Any other failure, for example an error response from the server. */
  data object UnexpectedError : ReverseGeocodeResult
}

/**
 * Turns place names into map positions and back, for the Create Event location picker.
 *
 * Each call may send one request to an external service, so callers send a search only when the
 * student submits it, never while they type, and look up the pin's address only once the map has
 * stopped moving. Implementations never throw: every failure is a result.
 */
interface GeocodingService {
  /** Finds the places matching [query]. A blank query finds nothing and sends no request. */
  suspend fun search(query: String): PlaceSearchResult

  /** Finds the place at [coordinates], to name a pin the student placed by moving the map. */
  suspend fun reverse(coordinates: Coordinates): ReverseGeocodeResult
}
