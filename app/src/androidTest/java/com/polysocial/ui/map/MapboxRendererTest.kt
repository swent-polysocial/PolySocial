// Contributors: OpenAI Codex (hermetic native marker, failure and consent tests for #50).
package com.polysocial.ui.map

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.bindgen.Value
import com.mapbox.common.HttpRequest
import com.mapbox.common.HttpRequestError
import com.mapbox.common.HttpRequestErrorType
import com.mapbox.common.HttpRequestOrResponse
import com.mapbox.common.HttpResponse
import com.mapbox.common.HttpResponseData
import com.mapbox.common.HttpServiceFactory
import com.mapbox.common.HttpServiceInterceptorInterface
import com.mapbox.common.HttpServiceInterceptorRequestContinuation
import com.mapbox.common.HttpServiceInterceptorResponseContinuation
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapLoadingErrorType
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMapsOptions
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.ScreenBox
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.TileStoreUsageMode
import com.mapbox.maps.plugin.MapPlugin
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.plugin.delegates.MapAttributionDelegate
import com.mapbox.maps.plugin.delegates.MapDelegateProvider
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.ui.theme.PolySocialTheme
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual native renderer with an empty local style and no live HTTP requests. */
@RunWith(AndroidJUnit4::class)
class MapboxRendererTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val visible = mutableStateOf(true)
  private val state = mutableStateOf(MapUiState())
  private val http = LocalMapHttp()
  private lateinit var cache: File
  private lateinit var cacheRoot: File
  private lateinit var originalToken: String
  private lateinit var originalDataPath: String
  private lateinit var originalBaseUrl: String
  private lateinit var originalTileStoreMode: TileStoreUsageMode
  private var contentInstalled = false
  private var viewedEvent: String? = null

  @Before
  fun interceptBeforeCreatingMap() {
    // This interceptor never continues with an HttpRequest, including accounting/telemetry traffic.
    HttpServiceFactory.setHttpServiceInterceptor(http)
    originalToken = MapboxOptions.accessToken
    originalDataPath = MapboxMapsOptions.dataPath
    originalBaseUrl = MapboxMapsOptions.baseUrl
    originalTileStoreMode = MapboxMapsOptions.tileStoreUsageMode
    cacheRoot = compose.activity.cacheDir.canonicalFile
    cache = File.createTempFile("polysocial-native-map-", "", cacheRoot)
    check(cache.delete() && cache.mkdir())
    MapboxOptions.accessToken = "pk.hermetic-native-map-test"
    MapboxMapsOptions.dataPath = cache.absolutePath
    MapboxMapsOptions.baseUrl = "https://api.mapbox.com"
    MapboxMapsOptions.tileStoreUsageMode = TileStoreUsageMode.DISABLED
  }

  @After
  fun destroyMapBeforeRestoringGlobals() {
    try {
      if (contentInstalled) {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag(MapTags.CANVAS).assertDoesNotExist()
      }
    } finally {
      // Closing the activity also destroys its composition if an assertion failed during disposal.
      compose.activityRule.scenario.close()
      try {
        MapboxOptions.accessToken = originalToken
        MapboxMapsOptions.dataPath = originalDataPath
        MapboxMapsOptions.baseUrl = originalBaseUrl
        MapboxMapsOptions.tileStoreUsageMode = originalTileStoreMode
        if (::cache.isInitialized) {
          check(cache.canonicalFile.parentFile == cacheRoot)
          check(cache.deleteRecursively())
        }
      } finally {
        HttpServiceFactory.setHttpServiceInterceptor(null)
      }
    }
  }

  @Test
  fun localStyleRendersEventCoordinatesAndRealTapOpensCorrectPreview() {
    val events =
        listOf(
            event("first", "Library meetup", 6.6323, 46.5197),
            event("second", "Campus concert", 6.6410, 46.5260),
        )
    show(events)
    awaitStatus(MapRenderStatus.READY)
    val map = nativeMap()
    val points = renderedPoints(map, events.size)
    assertEquals(events.size, points.size)
    compose.runOnIdle {
      assertEquals(1, checkNotNull(map.mapboxMap.style).styleLayers.count { it.type == "circle" })
    }
    compose.runOnIdle {
      assertEquals(6.6323, map.mapboxMap.cameraState.center.longitude(), 0.000001)
      assertEquals(46.5197, map.mapboxMap.cameraState.center.latitude(), 0.000001)
      assertEquals(12.0, map.mapboxMap.cameraState.zoom, 0.000001)
    }
    assertConsentDisabled(map)
    events.forEach { event ->
      val pixel = compose.runOnIdle {
        map.mapboxMap.pixelForCoordinate(
            Point.fromLngLat(event.location.longitude, event.location.latitude)
        )
      }
      // Rendered GeoJSON is decoded from tiles, rather than the original source coordinates.
      // Query and tap the exact projected input point to check placement and event identity.
      assertEquals(1, renderedPoints(map, 1, RenderedQueryGeometry(pixel)).size)
      compose.onNodeWithTag(MapTags.CANVAS).performTouchInput {
        click(Offset(pixel.x.toFloat(), pixel.y.toFloat()))
      }
      compose.waitUntil(TIMEOUT) { state.value.selectedEvent?.id == event.id }
      compose.onNodeWithTag(MapTags.TITLE).assertTextEquals(event.title)
      compose.onNodeWithTag(MapTags.DETAILS).performClick()
      compose.runOnIdle { assertEquals(event.id, viewedEvent) }
      compose.onNodeWithTag(MapTags.CLOSE).performClick()
      compose.onNodeWithTag(MapTags.PREVIEW).assertDoesNotExist()
    }
    assertTrue(http.styleRequests.get() > 0)
  }

  @Test
  fun rejectedStyleSurfacesNativeFailureAndRetryLoadsLocalStyle() {
    http.rejectStyle.set(true)
    show(listOf(event("retry", "Retry meetup", 6.6323, 46.5197)))
    awaitStatus(MapRenderStatus.ERROR)
    compose.onNodeWithTag(MapTags.ERROR).assertIsDisplayed()
    compose.runOnIdle { http.rejectStyle.set(false) }
    compose.onNodeWithTag(MapTags.RETRY).performClick()
    awaitStatus(MapRenderStatus.READY)
    compose.onNodeWithTag(MapTags.ERROR).assertDoesNotExist()
    assertEquals(1, renderedPoints(nativeMap(), 1).size)
    assertTrue(http.styleRequests.get() >= 2)
  }

  @Test
  fun liveMarkerUpdatesKeepOneLayerAndClicksSelectTheNewEventId() {
    val first = event("old", "Old meetup", 6.6323, 46.5197)
    show(listOf(first))
    awaitStatus(MapRenderStatus.READY)
    val map = nativeMap()
    renderedPoints(map, 1)
    val replacement = event("new", "New meetup", 6.6370, 46.5230)
    val second =
        event("second", "Sports meetup", 6.6410, 46.5260).copy(category = EventCategory.SPORTS)
    compose.runOnIdle { state.value = state.value.copy(events = listOf(replacement, second)) }
    val pixel = compose.runOnIdle {
      map.mapboxMap.pixelForCoordinate(Point.fromLngLat(6.6370, 46.5230))
    }
    renderedPoints(map, 1, RenderedQueryGeometry(pixel))
    assertEquals(2, renderedPoints(map, 2).size)
    compose.onNodeWithTag(MapTags.CANVAS).performTouchInput {
      click(Offset(pixel.x.toFloat(), pixel.y.toFloat()))
    }
    compose.waitUntil(TIMEOUT) { state.value.selectedEvent?.id == replacement.id }
    compose.onNodeWithTag(MapTags.TITLE).assertTextEquals(replacement.title)
    assertSame(map, nativeMap())
    compose.runOnIdle {
      assertEquals(1, checkNotNull(map.mapboxMap.style).styleLayers.count { it.type == "circle" })
    }
  }

  @Test
  fun missingTileAfterLoadKeepsTheMapAndCameraUsable() {
    show(listOf(event("cached", "Cached meetup", 6.6323, 46.5197)))
    awaitStatus(MapRenderStatus.READY)
    val map = nativeMap()
    val failures = AtomicInteger(0)
    val subscription = compose.runOnIdle {
      map.mapboxMap.subscribeMapLoadingError {
        if (it.type == MapLoadingErrorType.TILE) failures.incrementAndGet()
      }
    }
    try {
      compose.runOnIdle {
        map.mapboxMap.setCamera(
            CameraOptions.Builder().center(Point.fromLngLat(6.6330, 46.5200)).zoom(13.0).build()
        )
        val style = checkNotNull(map.mapboxMap.style)
        assertEquals(
            null,
            style
                .addStyleSource("uncached", checkNotNull(Value.fromJson(OFFLINE_SOURCE).value))
                .error,
        )
        assertEquals(
            null,
            style.addStyleLayer(checkNotNull(Value.fromJson(OFFLINE_LAYER).value), null).error,
        )
      }
      compose.waitUntil(TIMEOUT) { failures.get() > 0 }
      compose.waitForIdle()
      compose.onNodeWithTag(MapTags.ERROR).assertDoesNotExist()
      assertSame(map, nativeMap())
      compose.runOnIdle {
        assertEquals(MapRenderStatus.READY, state.value.renderStatus)
        assertEquals(13.0, map.mapboxMap.cameraState.zoom, 0.000001)
        assertEquals(6.6330, map.mapboxMap.cameraState.center.longitude(), 0.000001)
      }
      assertEquals(1, renderedPoints(map, 1).size)
    } finally {
      compose.runOnIdle { subscription.cancel() }
    }
  }

  private fun show(events: List<Event>) {
    state.value = MapUiState(status = MapContentStatus.READY, events = events)
    contentInstalled = true
    compose.setContent {
      if (visible.value) {
        PolySocialTheme {
          MapScreen(
              state.value,
              onSelectEvent = { id ->
                state.value =
                    state.value.copy(selectedEvent = state.value.events.first { it.id == id })
              },
              onClosePreview = { state.value = state.value.copy(selectedEvent = null) },
              onViewDetails = { viewedEvent = it },
              onRetry = {
                state.value =
                    state.value.copy(
                        renderStatus = MapRenderStatus.LOADING,
                        renderGeneration = state.value.renderGeneration + 1,
                    )
              },
              onRenderStatus = { state.value = state.value.copy(renderStatus = it) },
              tokenConfigured = true,
          )
        }
      }
    }
  }

  private fun awaitStatus(status: MapRenderStatus) {
    compose.waitUntil(TIMEOUT) { state.value.renderStatus == status }
    compose.runOnIdle { assertEquals(status, state.value.renderStatus) }
  }

  private fun nativeMap(): MapView = compose.runOnIdle {
    fun find(view: View): MapView? {
      if (view is MapView) return view
      if (view is ViewGroup) {
        for (index in 0 until view.childCount) find(view.getChildAt(index))?.let {
          return it
        }
      }
      return null
    }
    checkNotNull(find(compose.activity.window.decorView))
  }

  private fun renderedPoints(
      map: MapView,
      expectedCount: Int,
      geometry: RenderedQueryGeometry? = null,
  ): List<Point> {
    val pending = AtomicBoolean(false)
    val points = AtomicReference<List<Point>>(emptyList())
    val error = AtomicReference<String?>(null)
    compose.waitUntil(TIMEOUT) {
      if (points.get().size == expectedCount || error.get() != null) true
      else {
        if (pending.compareAndSet(false, true)) {
          compose.runOnUiThread {
            map.mapboxMap.queryRenderedFeatures(
                geometry
                    ?: RenderedQueryGeometry(
                        ScreenBox(
                            ScreenCoordinate(0.0, 0.0),
                            ScreenCoordinate(map.width.toDouble(), map.height.toDouble()),
                        )
                    ),
                RenderedQueryOptions(null, null),
            ) { result ->
              error.set(result.error)
              points.set(
                  result.value?.mapNotNull { it.queriedFeature.feature.geometry() as? Point }
                      ?: emptyList()
              )
              pending.set(false)
            }
          }
        }
        false
      }
    }
    assertEquals("Native feature query failed", null, error.get())
    return points.get()
  }

  private fun assertConsentDisabled(map: MapView) {
    val observer = ConsentObserver()
    compose.runOnIdle { map.createPlugin(Plugin.Custom("polysocial-consent-test", observer)) }
    compose.waitUntil(TIMEOUT) {
      compose.runOnIdle {
        observer.delegate?.let {
          !it.telemetry().userTelemetryRequestState && !it.geofencingConsent().getUserConsent()
        } == true
      }
    }
    compose.runOnIdle {
      val delegate = checkNotNull(observer.delegate)
      assertFalse(delegate.telemetry().userTelemetryRequestState)
      assertFalse(delegate.geofencingConsent().getUserConsent())
      map.removePlugin("polysocial-consent-test")
    }
  }

  private class ConsentObserver : MapPlugin {
    var delegate: MapAttributionDelegate? = null

    override fun onDelegateProvider(delegateProvider: MapDelegateProvider) {
      delegate = delegateProvider.mapAttributionDelegate
    }

    // A passive plugin only observes the actual SDK consent services; it supplies no replacements.
    override fun initialize() = Unit

    override fun cleanup() {
      delegate = null
    }
  }

  private class LocalMapHttp : HttpServiceInterceptorInterface {
    val rejectStyle = AtomicBoolean(false)
    val styleRequests = AtomicInteger(0)

    override fun onRequest(
        request: HttpRequest,
        continuation: HttpServiceInterceptorRequestContinuation,
    ) {
      val uri = runCatching { URI(request.url) }.getOrNull()
      val result =
          if (uri?.host == "api.mapbox.com" && uri.path == "/styles/v1/mapbox/standard") {
            styleRequests.incrementAndGet()
            ExpectedFactory.createValue<HttpRequestError, HttpResponseData>(
                HttpResponseData(
                    hashMapOf("Content-Type" to "application/json", "Cache-Control" to "no-store"),
                    if (rejectStyle.get()) 401 else 200,
                    (if (rejectStyle.get()) "{\"message\":\"hermetic rejection\"}" else LOCAL_STYLE)
                        .toByteArray(Charsets.UTF_8),
                )
            )
          } else if (uri?.host == "offline.invalid") {
            ExpectedFactory.createValue<HttpRequestError, HttpResponseData>(
                HttpResponseData(hashMapOf("Cache-Control" to "no-store"), 403, ByteArray(0))
            )
          } else {
            ExpectedFactory.createError<HttpRequestError, HttpResponseData>(
                HttpRequestError(
                    HttpRequestErrorType.OTHER_ERROR,
                    "Request rejected by hermetic map test",
                )
            )
          }
      continuation.run(HttpRequestOrResponse(HttpResponse(request.id ?: 0L, request, result)))
    }

    override fun onResponse(
        response: HttpResponse,
        continuation: HttpServiceInterceptorResponseContinuation,
    ) {
      continuation.run(response)
    }
  }

  private fun event(id: String, title: String, longitude: Double, latitude: Double) =
      Event(
          id = id,
          title = title,
          description = "Synthetic native renderer fixture",
          category = EventCategory.CULTURE,
          location = Coordinates(latitude, longitude),
          startTime = Instant.parse("2026-10-07T16:00:00Z"),
          isPrivate = false,
      )

  private companion object {
    const val TIMEOUT = 20_000L
    const val LOCAL_STYLE =
        """{"version":8,"name":"hermetic","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#ffffff"}}]}"""
    const val OFFLINE_SOURCE =
        """{"type":"raster","tiles":["https://offline.invalid/{z}/{x}/{y}.png"],"tileSize":256}"""
    const val OFFLINE_LAYER = """{"id":"uncached-layer","type":"raster","source":"uncached"}"""
  }
}
