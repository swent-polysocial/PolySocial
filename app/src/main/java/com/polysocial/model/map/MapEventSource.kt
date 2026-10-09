// Contributors: OpenAI Codex (injectable public-event stream for #50).
package com.polysocial.model.map

import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.PublicEventsResult
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Result of observing public upcoming events; cached events may arrive while offline. */
sealed interface MapEventResult {
  data class Events(val events: List<Event>) : MapEventResult

  data object Error : MapEventResult
}

/**
 * Source of the map's public upcoming events, independent of the map SDK.
 *
 * The repository queries public events only and preserves Firestore's offline cache. Each
 * collection starts an observation; cancelling it must release any backend listener.
 */
fun interface MapEventSource {
  fun observePublicUpcomingEvents(): Flow<MapEventResult>
}

/**
 * Connects the map to the repository's live, 14-day public-event window. Cached results remain
 * usable; cancellation and terminal errors preserve the repository's listener lifecycle.
 */
class RepositoryMapEventSource @Inject constructor(private val repository: EventRepository) :
    MapEventSource {
  override fun observePublicUpcomingEvents(): Flow<MapEventResult> =
      repository.getUpcomingPublicEvents().map { result ->
        when (result) {
          is PublicEventsResult.Events -> MapEventResult.Events(result.events)
          PublicEventsResult.Error -> MapEventResult.Error
        }
      }
}
