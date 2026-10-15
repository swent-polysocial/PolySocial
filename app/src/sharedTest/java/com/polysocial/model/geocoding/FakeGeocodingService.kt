// Contributors: Claude (wrote this fake for the location picker tests, #111).
package com.polysocial.model.geocoding

import com.polysocial.model.event.Coordinates
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [GeocodingService] for tests. Tests script the answers, read which calls were made, and
 * can hold calls until they release them, to check what the screen shows meanwhile.
 *
 * @property searchResult what [search] answers.
 * @property reverseResult what [reverse] answers.
 */
class FakeGeocodingService(
    var searchResult: PlaceSearchResult = PlaceSearchResult.Found(emptyList()),
    var reverseResult: ReverseGeocodeResult = ReverseGeocodeResult.NotFound,
) : GeocodingService {
  /** The queries passed to [search], in call order. */
  val searches = mutableListOf<String>()

  /** The coordinates passed to [reverse], in call order. */
  val reverses = mutableListOf<Coordinates>()

  /** When set, [search] waits for it to complete before answering. */
  var pendingSearch: CompletableDeferred<Unit>? = null

  /** When set, [reverse] waits for it to complete before answering. */
  var pendingReverse: CompletableDeferred<Unit>? = null

  override suspend fun search(query: String): PlaceSearchResult {
    searches += query
    pendingSearch?.await()
    return searchResult
  }

  override suspend fun reverse(coordinates: Coordinates): ReverseGeocodeResult {
    reverses += coordinates
    pendingReverse?.await()
    return reverseResult
  }
}
