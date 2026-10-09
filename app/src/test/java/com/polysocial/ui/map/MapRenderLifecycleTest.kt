// Contributors: OpenAI Codex (cached-map loading-error regression tests, #50).
package com.polysocial.ui.map

import com.mapbox.maps.MapLoadingErrorType
import org.junit.Assert.assertEquals
import org.junit.Test

class MapRenderLifecycleTest {
  @Test
  fun uncachedResourcesAfterLoadKeepTheMapReady() {
    val statuses = mutableListOf<MapRenderStatus>()
    val lifecycle = MapRenderLifecycle(statuses::add)
    lifecycle.onLoaded()
    listOf(
            MapLoadingErrorType.TILE,
            MapLoadingErrorType.SPRITE,
            MapLoadingErrorType.GLYPHS,
            MapLoadingErrorType.SOURCE,
        )
        .forEach(lifecycle::onError)
    assertEquals(listOf(MapRenderStatus.READY), statuses)
  }

  @Test
  fun initialFailureCanRecoverButLaterStyleFailureStillNeedsRetry() {
    val statuses = mutableListOf<MapRenderStatus>()
    val lifecycle = MapRenderLifecycle(statuses::add)
    lifecycle.onError(MapLoadingErrorType.TILE)
    lifecycle.onLoaded()
    lifecycle.onError(MapLoadingErrorType.STYLE)
    lifecycle.onError(MapLoadingErrorType.GLYPHS)
    assertEquals(
        listOf(MapRenderStatus.ERROR, MapRenderStatus.READY, MapRenderStatus.ERROR),
        statuses,
    )
  }
}
