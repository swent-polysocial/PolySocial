// Contributors: OpenAI Codex (injectable public-event stream for #50).
package com.polysocial.model.map

import com.polysocial.model.event.Event
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Result of observing public upcoming events; cached events may arrive while offline. */
sealed interface MapEventResult {
  data object Loading : MapEventResult

  data class Events(val events: List<Event>) : MapEventResult

  data object Unavailable : MapEventResult

  data object Error : MapEventResult
}

/**
 * Source of the map's public upcoming events, independent of the map SDK.
 *
 * The #49 repository adapter must query public events only and preserve Firestore's offline cache.
 * Each collection starts an observation; cancelling it must release any backend listener.
 */
fun interface MapEventSource {
  fun observePublicUpcomingEvents(): Flow<MapEventResult>
}

/** Honest setup state until #49 provides the repository's public upcoming-event API. */
class NotConfiguredMapEventSource @Inject constructor() : MapEventSource {
  override fun observePublicUpcomingEvents(): Flow<MapEventResult> =
      flowOf(MapEventResult.Unavailable)
}
