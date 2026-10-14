// Contributors: Claude (Mapbox map of the location picker, #111, following the Map tab's renderer).
package com.polysocial.ui.event.create

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.ornaments.attribution.MapAttributionScope
import com.mapbox.maps.extension.compose.rememberMapState
import com.polysocial.model.event.Coordinates

/**
 * The picker's map, the only part that uses the map SDK. The pin is drawn by the screen at the
 * center, so the map only reports where its center stops ([onCenterSettled]) and recenters on a
 * tapped point, which moves the pin there. It moves to [camera] whenever the app places the pin.
 * Like the Map tab, it keeps Mapbox's logo and attribution (with the telemetry opt-out), above the
 * place card ([bottomInset]), and turns optional data collection off.
 */
@Composable
internal fun PickerMapboxRenderer(
    camera: CameraRequest,
    onCenterSettled: (Coordinates) -> Unit,
    onMapTap: () -> Unit,
    bottomInset: Dp,
) {
  val mapState = rememberMapState()
  val viewport = rememberMapViewportState {
    setCameraOptions {
      center(camera.target.toPoint())
      zoom(PICKER_ZOOM)
      pitch(0.0)
      bearing(0.0)
    }
  }
  val settled = rememberUpdatedState(onCenterSettled)
  val tapped = rememberUpdatedState(onMapTap)
  LaunchedEffect(camera.id) {
    viewport.easeTo(CameraOptions.Builder().center(camera.target.toPoint()).build())
  }
  LaunchedEffect(mapState) {
    mapState.mapIdleEvents.collect {
      viewport.cameraState?.center?.let {
        settled.value(Coordinates(latitude = it.latitude(), longitude = it.longitude()))
      }
    }
  }
  MapboxMap(
      modifier = Modifier.fillMaxSize().testTag(LocationPickerTags.MAP),
      mapState = mapState,
      mapViewportState = viewport,
      onMapClickListener = { point ->
        tapped.value()
        viewport.easeTo(CameraOptions.Builder().center(point).build())
        true
      },
      logo = { Logo(Modifier.padding(bottom = bottomInset)) },
      attribution = {
        val consent = remember {
          MapAttributionScope.UserConsentState.Builder()
              .setTelemetryEnableState(false)
              .setGeofencingUserConsentState(false)
              .build()
        }
        AttributionControl(consent, remember { MapAttributionScope.AttributionState() })
        Attribution(Modifier.padding(bottom = bottomInset))
      },
  )
}

private fun Coordinates.toPoint(): Point = Point.fromLngLat(longitude, latitude)

/** Street level: close enough to tell buildings apart when placing the pin. */
private const val PICKER_ZOOM = 16.0
