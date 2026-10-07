// Contributors: OpenAI Codex (Mapbox Compose renderer for #50).
package com.polysocial.ui.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.mapbox.annotation.MapboxDelicateApi
import com.mapbox.annotation.MapboxExperimental
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation
import com.mapbox.maps.extension.compose.ornaments.attribution.MapAttributionScope
import com.mapbox.maps.extension.compose.rememberMapState
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
  LaunchedEffect(mapState) {
    mapState.mapLoadingErrorEvents.collect { currentStatus.value(MapRenderStatus.ERROR) }
  }
  LaunchedEffect(mapState) {
    mapState.mapLoadedEvents.collect { currentStatus.value(MapRenderStatus.READY) }
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
  ) {
    events.forEach { event ->
      key(event.id) {
        CircleAnnotation(Point.fromLngLat(event.location.longitude, event.location.latitude)) {
          circleColor = categoryColor(event.category)
          circleRadius = 12.0
          circleStrokeColor = Color.White
          circleStrokeWidth = 3.0
          interactionsState.onClicked {
            currentSelect.value(event.id)
            true
          }
        }
      }
    }
  }
}
