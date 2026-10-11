// Contributors: Claude (drafted the Nominatim geocoding client, #111).
package com.polysocial.model.geocoding

import com.polysocial.model.event.Coordinates
import com.polysocial.model.network.NetworkMonitor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException

/**
 * [GeocodingService] backed by the public Nominatim server (OpenStreetMap data).
 *
 * It follows the
 * [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/):
 * - requests identify the app with [USER_AGENT];
 * - at most one request per second for the whole app: requests wait for each other, and each one
 *   starts at least [MIN_REQUEST_INTERVAL] after the previous one ended (keep one instance, see
 *   `GeocodingModule`);
 * - repeated searches and lookups are answered from an in-memory cache without a request.
 *
 * Search-as-you-type is forbidden by the policy; [GeocodingService] leaves that to the caller. The
 * UI must show "© OpenStreetMap contributors" next to the results.
 *
 * Like `EventRepository.createEvent`, it checks [NetworkMonitor] first and returns a network error
 * offline without sending anything. Searches are biased towards Lausanne but not limited to it.
 *
 * @param timeSource measures the time between requests, so tests can use virtual time.
 * @param baseUrl the server, so tests can point it at a local one.
 */
class NominatimGeocodingService(
    private val network: NetworkMonitor,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val baseUrl: String = BASE_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GeocodingService {

  private val mutex = Mutex()
  private var lastRequestEnd: TimeMark? = null
  private val searches = LruCache<String, List<Place>>(CACHE_SIZE)
  private val reverses = LruCache<String, Place?>(CACHE_SIZE)

  override suspend fun search(query: String): PlaceSearchResult {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return PlaceSearchResult.Found(emptyList())
    val key = trimmed.lowercase(Locale.ROOT)
    return mutex.withLock {
      searches[key]?.let {
        return@withLock PlaceSearchResult.Found(it)
      }
      val params =
          mapOf(
              "q" to trimmed,
              "format" to "jsonv2",
              "addressdetails" to "1",
              "limit" to "$SEARCH_LIMIT",
              "viewbox" to LAUSANNE_VIEWBOX,
          )
      when (val response = get("search", params)) {
        is Response.Body ->
            try {
              val places = parseNominatimSearch(response.json)
              searches[key] = places
              PlaceSearchResult.Found(places)
            } catch (_: JSONException) {
              PlaceSearchResult.UnexpectedError
            }
        Response.Unreachable -> PlaceSearchResult.NetworkError
        Response.Failed -> PlaceSearchResult.UnexpectedError
      }
    }
  }

  override suspend fun reverse(coordinates: Coordinates): ReverseGeocodeResult {
    // Five decimals is about a metre: closer pins share one lookup. Locale.ROOT keeps the decimal
    // point, which a French or German locale would turn into a comma.
    val latitude = "%.5f".format(Locale.ROOT, coordinates.latitude)
    val longitude = "%.5f".format(Locale.ROOT, coordinates.longitude)
    val key = "$latitude,$longitude"
    return mutex.withLock {
      if (reverses.containsKey(key)) {
        return@withLock reverses[key].toReverseResult()
      }
      val params =
          mapOf(
              "lat" to latitude,
              "lon" to longitude,
              "format" to "jsonv2",
              "addressdetails" to "1",
          )
      when (val response = get("reverse", params)) {
        is Response.Body ->
            try {
              val place = parseNominatimReverse(response.json)
              reverses[key] = place
              place.toReverseResult()
            } catch (_: JSONException) {
              ReverseGeocodeResult.UnexpectedError
            }
        Response.Unreachable -> ReverseGeocodeResult.NetworkError
        Response.Failed -> ReverseGeocodeResult.UnexpectedError
      }
    }
  }

  /** Sends one GET request, after the wait the rate limit needs. Call it holding [mutex]. */
  private suspend fun get(path: String, params: Map<String, String>): Response {
    if (!network.isOnline()) return Response.Unreachable
    lastRequestEnd?.let { delay(MIN_REQUEST_INTERVAL - it.elapsedNow()) }
    val query =
        params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    return try {
      withContext(ioDispatcher) { fetch(URL("$baseUrl/$path?$query")) }
    } catch (_: IOException) {
      Response.Unreachable
    } finally {
      lastRequestEnd = timeSource.markNow()
    }
  }

  private fun fetch(url: URL): Response {
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = TIMEOUT_MILLIS
      connection.readTimeout = TIMEOUT_MILLIS
      connection.setRequestProperty("User-Agent", USER_AGENT)
      if (connection.responseCode != HttpURLConnection.HTTP_OK) return Response.Failed
      return Response.Body(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
      connection.disconnect()
    }
  }

  private fun Place?.toReverseResult(): ReverseGeocodeResult =
      if (this == null) ReverseGeocodeResult.NotFound else ReverseGeocodeResult.Found(this)

  private sealed interface Response {
    data class Body(val json: String) : Response

    /** Offline, or the server couldn't be reached in time. */
    data object Unreachable : Response

    /** The server answered with an error status. */
    data object Failed : Response
  }

  /** Keeps the [capacity] most recently used entries. */
  private class LruCache<K, V>(private val capacity: Int) :
      LinkedHashMap<K, V>(capacity, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity
  }

  companion object {
    const val BASE_URL = "https://nominatim.openstreetmap.org"
    const val USER_AGENT = "PolySocial-Android (+https://github.com/swent-polysocial/PolySocial)"
    val MIN_REQUEST_INTERVAL = 1.seconds
    const val SEARCH_LIMIT = 5

    /** Lausanne and its surroundings (west, north, east, south), where results are preferred. */
    const val LAUSANNE_VIEWBOX = "6.45,46.60,6.80,46.45"
    private const val CACHE_SIZE = 50
    private const val TIMEOUT_MILLIS = 10_000
  }
}
