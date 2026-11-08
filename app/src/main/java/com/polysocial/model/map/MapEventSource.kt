// Contributors: OpenAI Codex (injectable public-event stream for #50).
// Contributors: Claude (an association's own upcoming events on its map, #133).
package com.polysocial.model.map

import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.OwnEventsResult
import com.polysocial.model.event.PublicEventsResult
import com.polysocial.model.event.splitUpcomingAndPast
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Result of observing the map's events; cached events may arrive while offline. */
sealed interface MapEventResult {
  data class Events(val events: List<Event>) : MapEventResult

  data object Error : MapEventResult
}

/**
 * Source of the map's events, independent of the map SDK: the public upcoming events for a student,
 * or a verified association's own upcoming events (#133).
 *
 * The repository preserves Firestore's offline cache. Each collection starts an observation;
 * cancelling it must release any backend listener.
 */
fun interface MapEventSource {
  fun observeEvents(): Flow<MapEventResult>

  /**
   * True when the events are the signed-in association's own: the map then keeps its private ones
   * too, and its preview has no Find a group (#133). False for the students' public map, which
   * drops any private event it gets.
   */
  val ownEvents: Boolean
    get() = false
}

/**
 * Connects the map to the repository's live, 14-day public-event window. Cached results remain
 * usable; cancellation and terminal errors preserve the repository's listener lifecycle.
 */
class RepositoryMapEventSource @Inject constructor(private val repository: EventRepository) :
    MapEventSource {
  override fun observeEvents(): Flow<MapEventResult> =
      repository.getUpcomingPublicEvents().map { result ->
        when (result) {
          is PublicEventsResult.Events -> MapEventResult.Events(result.events)
          PublicEventsResult.Error -> MapEventResult.Error
        }
      }
}

/**
 * Connects a verified association's map to its own events (#133), with the query its Events tab
 * uses (#132), private ones included. Only the upcoming ones are kept: an event is dropped once it
 * has ended (see [splitUpcomingAndPast]), when the events next change.
 */
class OwnAssociationMapEventSource
@Inject
constructor(private val repository: EventRepository, private val clock: Clock) : MapEventSource {
  override val ownEvents = true

  override fun observeEvents(): Flow<MapEventResult> =
      repository.getOwnAssociationEvents().map { result ->
        when (result) {
          is OwnEventsResult.Events ->
              MapEventResult.Events(splitUpcomingAndPast(result.events, clock.instant()).upcoming)
          OwnEventsResult.Error -> MapEventResult.Error
        }
      }
}
