// Contributors: OpenAI Codex (Mapbox Compose renderer for #50).
package com.polysocial.ui.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.google.gson.JsonPrimitive
import com.mapbox.annotation.MapboxDelicateApi
import com.mapbox.annotation.MapboxExperimental
import com.mapbox.geojson.Point
import com.mapbox.maps.MapLoadingErrorType
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.MapboxMapComposable
import com.mapbox.maps.extension.compose.MapboxMapScope
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotationGroup
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotationGroupState
import com.mapbox.maps.extension.compose.ornaments.attribution.MapAttributionScope
import com.mapbox.maps.extension.compose.rememberMapState
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.polysocial.model.event.Event

/**
 * The only map-SDK boundary. It shows public event coordinates without enabling a location puck.
 * Logo and attribution (including telemetry opt-out) stay above the event card.
 */
@Composable
@OptIn(MapboxExperimental::class, MapboxDelicateApi::class)
internal fun MapboxRenderer(
    events: List<Event>,
    onSelectEvent: (String) -> Unit,
    onStatus: (MapRenderStatus) -> Unit,
    bottomInset: Dp,
) {
  val mapState = rememberMapState()
  val currentStatus = rememberUpdatedState(onStatus)
  val currentSelect = rememberUpdatedState(onSelectEvent)
  val lifecycle = remember(mapState) { MapRenderLifecycle { currentStatus.value(it) } }
  LaunchedEffect(mapState) { mapState.mapLoadingErrorEvents.collect { lifecycle.onError(it.type) } }
  LaunchedEffect(mapState) { mapState.mapLoadedEvents.collect { lifecycle.onLoaded() } }
  val annotations =
      remember(events) {
        events.map { event ->
          CircleAnnotationOptions()
              .withPoint(Point.fromLngLat(event.location.longitude, event.location.latitude))
              .withCircleColor(categoryColor(event.category).toArgb())
              .withCircleRadius(12.0)
              .withCircleStrokeColor(Color.White.toArgb())
              .withCircleStrokeWidth(3.0)
              .withData(JsonPrimitive(event.id))
        }
      }
  val groupState = remember {
    CircleAnnotationGroupState().apply {
      interactionsState.onClicked { annotation ->
        currentSelect.value(checkNotNull(annotation.getData()).asString)
        true
      }
    }
  }
  val mapContent: @Composable @MapboxMapComposable MapboxMapScope.() -> Unit = {
    CircleAnnotationGroup(annotations, circleAnnotationGroupState = groupState)
  }
  MapboxMap(
      modifier = Modifier.fillMaxSize().testTag(MapTags.CANVAS),
      mapState = mapState,
      mapViewportState =
          rememberMapViewportState {
            setCameraOptions {
              center(Point.fromLngLat(6.6323, 46.5197))
              zoom(12.0)
              pitch(0.0)
              bearing(0.0)
            }
          },
      logo = { Logo(Modifier.padding(bottom = bottomInset)) },
      attribution = {
        // Keep Mapbox's attribution/opt-out UI; initialize optional collection to disabled.
        val consent = remember {
          MapAttributionScope.UserConsentState.Builder()
              .setTelemetryEnableState(false)
              .setGeofencingUserConsentState(false)
              .build()
        }
        AttributionControl(consent, remember { MapAttributionScope.AttributionState() })
        Attribution(Modifier.padding(bottom = bottomInset))
      },
      content = mapContent,
  )
}

/** A missing resource after first load leaves the cached map usable; a style failure is fatal. */
internal class MapRenderLifecycle(private val onStatus: (MapRenderStatus) -> Unit) {
  private var hasLoaded = false

  fun onLoaded() {
    hasLoaded = true
    onStatus(MapRenderStatus.READY)
  }

  fun onError(type: MapLoadingErrorType) {
    if (!hasLoaded || type == MapLoadingErrorType.STYLE) onStatus(MapRenderStatus.ERROR)
  }
}
