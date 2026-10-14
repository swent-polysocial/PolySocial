// Contributors: Claude (native map tests for the location picker, #111, with the hermetic setup of
// the Map tab's MapboxRendererTest).
package com.polysocial.ui.event.create

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapbox.bindgen.ExpectedFactory
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
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMapsOptions
import com.mapbox.maps.TileStoreUsageMode
import com.mapbox.maps.plugin.MapPlugin
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.plugin.delegates.MapAttributionDelegate
import com.mapbox.maps.plugin.delegates.MapDelegateProvider
import com.polysocial.model.event.Coordinates
import java.io.File
import java.net.URI
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the picker's real Mapbox map with an empty local style and no live HTTP request, and checks
 * what the screen relies on: where it starts, that it follows camera requests, that it reports
 * where it stops, and that a tap recenters it.
 */
@RunWith(AndroidJUnit4::class)
class PickerMapboxRendererTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val visible = mutableStateOf(true)
  private val camera = mutableStateOf(CameraRequest(START, 0))
  private val settled = Collections.synchronizedList(mutableListOf<Coordinates>())
  private var taps = 0
  private lateinit var cache: File
  private lateinit var cacheRoot: File
  private lateinit var originalToken: String
  private lateinit var originalDataPath: String
  private lateinit var originalBaseUrl: String
  private lateinit var originalTileStoreMode: TileStoreUsageMode
  private var contentInstalled = false

  @Before
  fun interceptBeforeCreatingMap() {
    HttpServiceFactory.setHttpServiceInterceptor(LocalStyle)
    originalToken = MapboxOptions.accessToken
    originalDataPath = MapboxMapsOptions.dataPath
    originalBaseUrl = MapboxMapsOptions.baseUrl
    originalTileStoreMode = MapboxMapsOptions.tileStoreUsageMode
    cacheRoot = compose.activity.cacheDir.canonicalFile
    cache = File.createTempFile("polysocial-picker-map-", "", cacheRoot)
    check(cache.delete() && cache.mkdir())
    MapboxOptions.accessToken = "pk.hermetic-picker-map-test"
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
        compose.onNodeWithTag(LocationPickerTags.MAP).assertDoesNotExist()
      }
    } finally {
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
  fun startsOnTheTargetAtStreetLevel_reportsWhereItStops_andKeepsDataCollectionOff() {
    show()

    awaitSettledNear(START)
    val map = nativeMap()
    compose.runOnIdle {
      assertNear(START, map.mapboxMap.cameraState.center)
      assertEquals(16.0, map.mapboxMap.cameraState.zoom, 0.000001)
    }
    assertConsentDisabled(map)
  }

  @Test
  fun aNewCameraRequestMovesTheMapThere() {
    show()
    awaitSettledNear(START)

    compose.runOnIdle { camera.value = CameraRequest(ELSEWHERE, 1) }

    awaitSettledNear(ELSEWHERE)
    val map = nativeMap()
    compose.runOnIdle { assertNear(ELSEWHERE, map.mapboxMap.cameraState.center) }
  }

  @Test
  fun aTapRecentersTheMapOnThatPoint() {
    show()
    awaitSettledNear(START)
    val map = nativeMap()
    val pixel = compose.runOnIdle {
      map.mapboxMap.pixelForCoordinate(Point.fromLngLat(ELSEWHERE.longitude, ELSEWHERE.latitude))
    }

    compose.onNodeWithTag(LocationPickerTags.MAP).performTouchInput {
      click(Offset(pixel.x.toFloat(), pixel.y.toFloat()))
    }

    compose.waitUntil(TIMEOUT) { taps == 1 }
    awaitSettledNear(ELSEWHERE, tolerance = TAP_TOLERANCE)
  }

  @Test
  fun theDefaultRendererWithATokenIsTheMapboxMap() {
    contentInstalled = true
    compose.setContent {
      if (visible.value) {
        defaultPickerRenderer(hasToken = true)(camera.value, { settled += it }, { taps++ }, 0.dp)
      }
    }

    awaitSettledNear(START)
    nativeMap()
  }

  private fun show() {
    contentInstalled = true
    compose.setContent {
      if (visible.value) {
        Box(Modifier.fillMaxSize()) {
          PickerMapboxRenderer(camera.value, { settled += it }, { taps++ }, 0.dp)
        }
      }
    }
  }

  private fun awaitSettledNear(target: Coordinates, tolerance: Double = TOLERANCE) =
      compose.waitUntil(TIMEOUT) {
        synchronized(settled) {
          settled.any {
            kotlin.math.abs(it.latitude - target.latitude) < tolerance &&
                kotlin.math.abs(it.longitude - target.longitude) < tolerance
          }
        }
      }

  private fun assertNear(expected: Coordinates, actual: Point) {
    assertEquals(expected.latitude, actual.latitude(), TOLERANCE)
    assertEquals(expected.longitude, actual.longitude(), TOLERANCE)
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

  private fun assertConsentDisabled(map: MapView) {
    val observer = ConsentObserver()
    compose.runOnIdle { map.createPlugin(Plugin.Custom("polysocial-picker-consent", observer)) }
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
      map.removePlugin("polysocial-picker-consent")
    }
  }

  private class ConsentObserver : MapPlugin {
    var delegate: MapAttributionDelegate? = null

    override fun onDelegateProvider(delegateProvider: MapDelegateProvider) {
      delegate = delegateProvider.mapAttributionDelegate
    }

    override fun initialize() = Unit

    override fun cleanup() {
      delegate = null
    }
  }

  /** Serves an empty local style and rejects every other request, so nothing leaves the device. */
  private object LocalStyle : HttpServiceInterceptorInterface {
    override fun onRequest(
        request: HttpRequest,
        continuation: HttpServiceInterceptorRequestContinuation,
    ) {
      val uri = runCatching { URI(request.url) }.getOrNull()
      val result =
          if (uri?.host == "api.mapbox.com" && uri.path == "/styles/v1/mapbox/standard") {
            ExpectedFactory.createValue<HttpRequestError, HttpResponseData>(
                HttpResponseData(
                    hashMapOf("Content-Type" to "application/json", "Cache-Control" to "no-store"),
                    200,
                    LOCAL_STYLE.toByteArray(Charsets.UTF_8),
                )
            )
          } else {
            ExpectedFactory.createError<HttpRequestError, HttpResponseData>(
                HttpRequestError(HttpRequestErrorType.OTHER_ERROR, "Rejected by the hermetic test")
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

  private companion object {
    const val TIMEOUT = 20_000L
    const val TOLERANCE = 0.000001

    /** A tap lands on a whole pixel, a few centimetres from the exact coordinate at zoom 16. */
    const val TAP_TOLERANCE = 0.0001
    val START = Coordinates(46.5197, 6.6323)
    val ELSEWHERE = Coordinates(46.5210, 6.6340)
    const val LOCAL_STYLE =
        """{"version":8,"name":"hermetic","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#ffffff"}}]}"""
  }
}
