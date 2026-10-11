// Contributors: Claude (drafted the Nominatim client tests against a local server, #111).
package com.polysocial.model.geocoding

import com.polysocial.model.event.Coordinates
import com.polysocial.model.network.NetworkMonitor
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests [NominatimGeocodingService] against a local HTTP server (the JDK's built-in `HttpServer` on
 * 127.0.0.1), so no request ever leaves the machine. Robolectric is needed because the responses
 * are parsed with Android's `org.json`.
 *
 * Time: the service measures the 1 s rate limit with the test's virtual [TimeSource] and waits with
 * `delay`, which runs on the test scheduler, so the wait costs no real time. The request itself
 * runs on [Dispatchers.Unconfined], that is inline on the test thread: the blocking HTTP call then
 * takes no virtual time at all, and virtual time only moves through the service's own `delay`. With
 * `Dispatchers.IO` the request would run on a real thread, mixing real and virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NominatimGeocodingServiceTest {

  private var online = true
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = online
      }

  private val server = LocalNominatim()

  @After fun stopServer() = server.stop()

  private fun TestScope.service(baseUrl: String = server.url) =
      NominatimGeocodingService(
          network = network,
          timeSource = testScheduler.timeSource,
          baseUrl = baseUrl,
          ioDispatcher = Dispatchers.Unconfined,
      )

  // ---------- Search results and errors ----------

  @Test
  fun search_aSuccessfulAnswerBecomesPlaces() = runTest {
    server.respond(200, "[$ROLEX,$GARE]")

    val result = service().search("rolex")

    assertEquals(
        PlaceSearchResult.Found(
            listOf(
                Place(
                    "Rolex Learning Center",
                    "Place Maurice-Cosandey, 1015 Lausanne",
                    Coordinates(46.5183517, 6.5676152),
                ),
                Place(
                    "Lausanne",
                    "Place de la Gare 9, 1003 Lausanne",
                    Coordinates(46.5167, 6.6291),
                ),
            )
        ),
        result,
    )
  }

  @Test
  fun search_anEmptyAnswerFindsNothing() = runTest {
    server.respond(200, "[]")

    assertEquals(PlaceSearchResult.Found(emptyList()), service().search("zzzz"))
  }

  @Test
  fun search_offlineGivesANetworkErrorAndSendsNothing() = runTest {
    online = false

    assertEquals(PlaceSearchResult.NetworkError, service().search("rolex"))
    assertEquals(0, server.requests.size)
  }

  @Test
  fun search_aServerErrorGivesAnUnexpectedError() = runTest {
    server.respond(500, "oops")

    assertEquals(PlaceSearchResult.UnexpectedError, service().search("rolex"))
    assertEquals(1, server.requests.size)
  }

  @Test
  fun search_anInvalidBodyGivesAnUnexpectedError() = runTest {
    server.respond(200, "<html>not json</html>")

    assertEquals(PlaceSearchResult.UnexpectedError, service().search("rolex"))
  }

  @Test
  fun search_anUnreachableServerGivesANetworkError() = runTest {
    val closed = LocalNominatim()
    val url = closed.url
    closed.stop()

    assertEquals(PlaceSearchResult.NetworkError, service(baseUrl = url).search("rolex"))
  }

  @Test
  fun search_aBlankQueryFindsNothingAndSendsNothing() = runTest {
    val service = service()

    assertEquals(PlaceSearchResult.Found(emptyList()), service.search(""))
    assertEquals(PlaceSearchResult.Found(emptyList()), service.search("   \t\n"))
    assertEquals(0, server.requests.size)
  }

  // ---------- The request ----------

  @Test
  fun search_sendsTheJsonv2FormatTheAddressDetailsTheLimitAndTheLausanneViewbox() = runTest {
    server.respond(200, "[]")

    service().search("rolex")

    val request = server.requests.single()
    assertEquals("/search", request.path)
    assertEquals(
        mapOf(
            "q" to "rolex",
            "format" to "jsonv2",
            "addressdetails" to "1",
            "limit" to "5",
            "viewbox" to "6.45,46.60,6.80,46.45",
        ),
        request.params,
    )
  }

  @Test
  fun search_sendsTheTrimmedQueryUrlEncoded() = runTest {
    server.respond(200, "[]")

    service().search("  Place de la Gare & Écublens  ")

    val request = server.requests.single()
    assertEquals("Place de la Gare & Écublens", request.params["q"])
    // Encoded: only printable ASCII, no raw space, no raw "&" splitting the query.
    assertTrue(request.rawQuery, request.rawQuery.all { it in '!'..'~' })
    assertEquals(5, request.rawQuery.split("&").size)
  }

  @Test
  fun reverse_sendsTheCoordinatesWithFiveDecimalsAndADecimalPointInAnyLocale() = runTest {
    server.respond(200, ROLEX)
    val defaultLocale = Locale.getDefault()
    try {
      Locale.setDefault(Locale.FRANCE)

      service().reverse(Coordinates(46.5183517, 6.5676152))
    } finally {
      Locale.setDefault(defaultLocale)
    }

    val request = server.requests.single()
    assertEquals("/reverse", request.path)
    assertEquals(
        mapOf(
            "lat" to "46.51835",
            "lon" to "6.56762",
            "format" to "jsonv2",
            "addressdetails" to "1",
        ),
        request.params,
    )
  }

  @Test
  fun everyRequest_identifiesTheAppWithItsUserAgent() = runTest {
    server.respond(200, "[]")
    val service = service()

    service.search("rolex")
    advanceTimeBy(2_000.milliseconds)
    service.reverse(Coordinates(46.5, 6.6))

    assertEquals(2, server.requests.size)
    server.requests.forEach { assertEquals(NominatimGeocodingService.USER_AGENT, it.userAgent) }
    assertFalse(NominatimGeocodingService.USER_AGENT.startsWith("Java"))
  }

  // ---------- Reverse results ----------

  @Test
  fun reverse_aSuccessfulAnswerBecomesAPlace() = runTest {
    server.respond(200, ROLEX)

    assertEquals(
        ReverseGeocodeResult.Found(
            Place(
                "Rolex Learning Center",
                "Place Maurice-Cosandey, 1015 Lausanne",
                Coordinates(46.5183517, 6.5676152),
            )
        ),
        service().reverse(Coordinates(46.5183517, 6.5676152)),
    )
  }

  @Test
  fun reverse_anUnknownLocationIsNotFound() = runTest {
    server.respond(200, UNABLE_TO_GEOCODE)

    assertEquals(ReverseGeocodeResult.NotFound, service().reverse(LAKE))
  }

  @Test
  fun reverse_offlineGivesANetworkErrorAndSendsNothing() = runTest {
    online = false

    assertEquals(ReverseGeocodeResult.NetworkError, service().reverse(LAKE))
    assertEquals(0, server.requests.size)
  }

  @Test
  fun reverse_aServerErrorGivesAnUnexpectedError() = runTest {
    server.respond(503, "busy")

    assertEquals(ReverseGeocodeResult.UnexpectedError, service().reverse(LAKE))
  }

  @Test
  fun reverse_anInvalidBodyGivesAnUnexpectedError() = runTest {
    server.respond(200, "[1, 2")

    assertEquals(ReverseGeocodeResult.UnexpectedError, service().reverse(LAKE))
  }

  @Test
  fun reverse_anUnreachableServerGivesANetworkError() = runTest {
    val closed = LocalNominatim()
    val url = closed.url
    closed.stop()

    assertEquals(ReverseGeocodeResult.NetworkError, service(baseUrl = url).reverse(LAKE))
  }

  // ---------- Rate limit: one request per second, measured from the end of the previous one ----

  @Test
  fun aSecondRequest_waitsOneSecondAfterThePreviousOne() = runTest {
    server.respond(200, "[]")
    val service = service()

    service.search("first")
    val second = async { service.search("second") }
    runCurrent()
    assertEquals(1, server.requests.size)

    advanceTimeBy(999.milliseconds)
    assertEquals("sent after 999 ms", 1, server.requests.size)

    advanceTimeBy(1.milliseconds)
    runCurrent()
    assertEquals(2, server.requests.size)
    assertEquals(PlaceSearchResult.Found(emptyList()), second.await())
    assertEquals(listOf(0L, 1_000L), server.requests.map { it.virtualTime })
  }

  @Test
  fun theRateLimit_isSharedBySearchesAndReverseLookups() = runTest {
    server.respond(200, "[]")
    val service = service()

    service.reverse(LAKE)
    service.search("rolex")
    service.reverse(Coordinates(46.6, 6.7))

    assertEquals(listOf(0L, 1_000L, 2_000L), server.requests.map { it.virtualTime })
  }

  @Test
  fun concurrentRequests_areSentOneSecondApart() = runTest {
    server.respond(200, "[]")
    val service = service()

    val first = async { service.search("one") }
    val second = async { service.search("two") }
    val third = async { service.reverse(LAKE) }
    first.await()
    second.await()
    third.await()

    assertEquals(listOf(0L, 1_000L, 2_000L), server.requests.map { it.virtualTime })
  }

  @Test
  fun theWait_countsTheTimeAlreadyElapsedSinceThePreviousRequest() = runTest {
    server.respond(200, "[]")
    val service = service()

    service.search("first")
    delay(600.milliseconds)
    service.search("second")
    delay(1_500.milliseconds)
    service.search("third")

    assertEquals(listOf(0L, 1_000L, 2_500L), server.requests.map { it.virtualTime })
  }

  @Test
  fun aFailedRequest_alsoCountsForTheRateLimit() = runTest {
    server.respond(500, "")
    val service = service()

    service.search("first")
    service.search("second")

    assertEquals(listOf(0L, 1_000L), server.requests.map { it.virtualTime })
  }

  // ---------- Cache ----------

  @Test
  fun aRepeatedSearch_isAnsweredFromTheCacheIgnoringCaseAndSpaces() = runTest {
    server.respond(200, "[$ROLEX]")
    val service = service()

    val first = service.search("Rolex Learning")
    val again = service.search("  rolex LEARNING ")

    assertEquals(first, again)
    assertTrue((first as PlaceSearchResult.Found).places.isNotEmpty())
    assertEquals(1, server.requests.size)
  }

  @Test
  fun aDifferentSearch_isSentAgain() = runTest {
    server.respond(200, "[]")
    val service = service()

    service.search("rolex")
    service.search("gare")

    assertEquals(listOf("rolex", "gare"), server.requests.map { it.params["q"] })
  }

  @Test
  fun aRepeatedReverseLookupWithinAboutAMetre_isAnsweredFromTheCache() = runTest {
    server.respond(200, ROLEX)
    val service = service()

    val first = service.reverse(Coordinates(46.518351, 6.567615))
    val again = service.reverse(Coordinates(46.518354, 6.567618))

    assertEquals(first, again)
    assertTrue(first is ReverseGeocodeResult.Found)
    assertEquals(1, server.requests.size)
  }

  @Test
  fun aReverseLookupFurtherAway_isSentAgain() = runTest {
    server.respond(200, ROLEX)
    val service = service()

    service.reverse(Coordinates(46.51835, 6.56762))
    service.reverse(Coordinates(46.51845, 6.56762))

    assertEquals(2, server.requests.size)
  }

  @Test
  fun aNotFoundReverseAnswer_isCachedToo() = runTest {
    server.respond(200, UNABLE_TO_GEOCODE)
    val service = service()

    service.reverse(LAKE)
    server.respond(200, ROLEX)

    assertEquals(ReverseGeocodeResult.NotFound, service.reverse(LAKE))
    assertEquals(1, server.requests.size)
  }

  @Test
  fun failedSearches_areNotCached() = runTest {
    val service = service()

    server.respond(500, "")
    assertEquals(PlaceSearchResult.UnexpectedError, service.search("rolex"))
    server.respond(200, "not json")
    assertEquals(PlaceSearchResult.UnexpectedError, service.search("rolex"))
    online = false
    assertEquals(PlaceSearchResult.NetworkError, service.search("rolex"))
    online = true
    server.respond(200, "[$ROLEX]")
    val retry = service.search("rolex")

    assertEquals("Rolex Learning Center", (retry as PlaceSearchResult.Found).places.single().name)
    assertEquals(3, server.requests.size)
  }

  @Test
  fun failedReverseLookups_areNotCached() = runTest {
    val service = service()

    server.respond(500, "")
    assertEquals(ReverseGeocodeResult.UnexpectedError, service.reverse(LAKE))
    server.respond(200, "{")
    assertEquals(ReverseGeocodeResult.UnexpectedError, service.reverse(LAKE))
    online = false
    assertEquals(ReverseGeocodeResult.NetworkError, service.reverse(LAKE))
    online = true
    server.respond(200, ROLEX)

    assertTrue(service.reverse(LAKE) is ReverseGeocodeResult.Found)
    assertEquals(3, server.requests.size)
  }

  @Test
  fun anUnreachableServer_isNotCachedEither() = runTest {
    val closed = LocalNominatim()
    val url = closed.url
    closed.stop()
    val service = service(baseUrl = url)

    assertEquals(PlaceSearchResult.NetworkError, service.search("rolex"))
    assertEquals(PlaceSearchResult.NetworkError, service.search("rolex"))
    assertEquals(ReverseGeocodeResult.NetworkError, service.reverse(LAKE))
    assertEquals(ReverseGeocodeResult.NetworkError, service.reverse(LAKE))
  }

  /** A request the local server received. */
  private data class Received(
      val path: String,
      val rawQuery: String,
      val params: Map<String, String>,
      val userAgent: String?,
      val virtualTime: Long,
  )

  /**
   * A local stand-in for Nominatim: answers every request with the scripted status and body, and
   * records each request with the virtual time at which it arrived.
   */
  private inner class LocalNominatim {
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var status = 200
    private var body = "[]"
    val requests: MutableList<Received> = Collections.synchronizedList(mutableListOf())
    var clock: () -> Long = { -1L }

    val url: String
      get() = "http://127.0.0.1:${http.address.port}"

    init {
      http.createContext("/") { exchange ->
        val raw = exchange.requestURI.rawQuery.orEmpty()
        requests +=
            Received(
                path = exchange.requestURI.path,
                rawQuery = raw,
                params =
                    raw.split("&")
                        .filter { it.isNotEmpty() }
                        .associate {
                          val (key, value) = it.split("=", limit = 2)
                          key to URLDecoder.decode(value, "UTF-8")
                        },
                userAgent = exchange.requestHeaders.getFirst("User-Agent"),
                virtualTime = clock(),
            )
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      http.start()
    }

    fun respond(status: Int, body: String) {
      this.status = status
      this.body = body
    }

    fun stop() = http.stop(0)
  }

  private fun TestScope.recordVirtualTime() {
    server.clock = { testScheduler.currentTime }
  }

  // Every test reads the time through the scheduler of its own runTest.
  private fun runTest(block: suspend TestScope.() -> Unit) =
      kotlinx.coroutines.test.runTest {
        recordVirtualTime()
        block()
      }

  private companion object {
    val LAKE = Coordinates(46.45, 6.6)

    const val UNABLE_TO_GEOCODE = """{"error":"Unable to geocode"}"""

    const val ROLEX =
        """{"lat":"46.5183517","lon":"6.5676152","name":"Rolex Learning Center",
          "display_name":"Rolex Learning Center, Place Maurice-Cosandey, Ecublens, Vaud, 1015, Schweiz/Suisse/Svizzera/Svizra",
          "address":{"building":"Rolex Learning Center","road":"Place Maurice-Cosandey",
          "city":"Lausanne","postcode":"1015","country_code":"ch"}}"""

    const val GARE =
        """{"lat":"46.5167","lon":"6.6291","name":"Lausanne",
          "address":{"road":"Place de la Gare","house_number":"9","postcode":"1003","city":"Lausanne"}}"""
  }
}
