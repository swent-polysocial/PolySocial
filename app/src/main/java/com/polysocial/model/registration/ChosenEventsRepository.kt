// Contributors: Claude Opus 5.5 (medium) (drafted the chosen events, #203).
package com.polysocial.model.registration

import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What [ChosenEventsRepository.observeChosenEvents] emits. */
sealed interface ChosenEventsResult {
  /**
   * The signed-in student's chosen events: every event they registered for ("I'm going"), past and
   * upcoming, with no time window. Each registered event ID is in exactly one of [events],
   * [unavailableIds], [notCachedIds] and [pendingIds].
   *
   * @property events the events the student can read, sorted by start time (then ID, so the order
   *   is stable).
   * @property unavailableIds registered events that can't be shown: deleted, malformed, or no
   *   longer readable (a private event the student was removed from). Only their IDs are known, on
   *   purpose, like `EventResult.NotFound`, so a private event reveals nothing else.
   * @property notCachedIds registered events that aren't in the offline cache while Firestore is
   *   offline (`EventResult.NotCached`). They appear once it is back online.
   * @property pendingIds registered events whose first read hasn't answered yet.
   * @property fromCache true when the registrations or any of [events] come from Firestore's
   *   offline cache. They may then be out of date.
   */
  data class Events(
      val events: List<Event>,
      val unavailableIds: Set<String>,
      val notCachedIds: Set<String>,
      val pendingIds: Set<String>,
      val fromCache: Boolean,
  ) : ChosenEventsResult

  /** The registrations couldn't be read: nobody is signed in, or the query failed. */
  data object Error : ChosenEventsResult
}

/**
 * The signed-in student's chosen events, for My events (#204): their own registrations
 * ([RegistrationRepository.observeMyRegistrations], #121) resolved to the events they can read
 * ([EventRepository.getEvent], #112).
 *
 * It adds no query and no access of its own. The registrations come from the student's own
 * collection-group query, and each event is read by ID under the event read rule, so it never reads
 * other students' registrations or an event the student isn't allowed in. Reading by ID also lifts
 * the 14-day window of the public events, and one denied private event can't make the others fail,
 * as one query over all of them would (Security Rules aren't filters).
 */
class ChosenEventsRepository
@Inject
constructor(
    private val registrations: RegistrationRepository,
    private val events: EventRepository,
) {
  /**
   * Observes the signed-in student's chosen events.
   *
   * It first waits for the registrations, then emits a new [ChosenEventsResult.Events] on every
   * change: a registration added ("I'm going") or removed (cancelled), or a chosen event edited,
   * deleted or becoming unreadable. Each registered event has exactly one listener, kept while it
   * stays registered and stopped when the registration goes away. Offline, both come from the cache
   * (`fromCache`), and an event that was never read on this device is in `notCachedIds`, so a
   * caller can end its loading state instead of waiting forever.
   *
   * A slow collector gets only the latest result. The flow ends after a [ChosenEventsResult.Error],
   * which is always delivered last. The student is the one signed in when collection starts (see
   * [RegistrationRepository.observeMyRegistrations]), so a new account needs a new collection;
   * cancelling the collection stops every listener.
   */
  fun observeChosenEvents(): Flow<ChosenEventsResult> = channelFlow {
    val state = ChosenEvents()
    val listeners = mutableMapOf<String, Job>()
    val lock = Mutex()
    registrations.observeMyRegistrations().collect { result ->
      lock.withLock {
        when (result) {
          MyRegistrationsResult.Error -> {
            state.ended = true
            listeners.values.forEach { it.cancel() }
            listeners.clear()
            send(ChosenEventsResult.Error)
          }
          is MyRegistrationsResult.Registrations -> {
            val ids = result.eventIds
            (listeners.keys - ids).forEach { listeners.remove(it)?.cancel() }
            state.registrations(ids, result.fromCache)
            (ids - listeners.keys).forEach { id ->
              listeners[id] = launch {
                events.getEvent(id).collect { answer ->
                  lock.withLock { if (state.answer(id, answer)) send(state.snapshot()) }
                }
              }
            }
            send(state.snapshot())
          }
        }
      }
    }
    lock.withLock { listeners.values.forEach { it.cancel() } }
  }
      .conflate()
      .distinctUntilChanged()
}

/** What [ChosenEventsRepository] knows so far: the registered IDs and each event's last answer. */
private class ChosenEvents {
  private var ids: Set<String> = emptySet()
  private var registrationsFromCache = false
  private val answers = mutableMapOf<String, EventResult>()

  /** Set once the registrations failed: later answers must not replace the final error. */
  var ended = false

  fun registrations(eventIds: Set<String>, fromCache: Boolean) {
    ids = eventIds
    registrationsFromCache = fromCache
    answers.keys.retainAll(eventIds)
  }

  /**
   * Records [result] for the event [id]. Returns false, recording nothing, once [ended] or if [id]
   * is no longer registered (a listener cancelled while it waited for the lock).
   */
  fun answer(id: String, result: EventResult): Boolean {
    if (ended || id !in ids) return false
    answers[id] = result
    return true
  }

  fun snapshot(): ChosenEventsResult.Events {
    val found = answers.mapNotNull { (id, result) ->
      (result as? EventResult.Found)?.let { id to it }
    }
    return ChosenEventsResult.Events(
        events =
            found
                .map { (id, result) -> result.event.copy(id = id) }
                .sortedWith(compareBy({ it.startTime }, { it.id })),
        unavailableIds = answers.filterValues { it == EventResult.NotFound }.keys.toSet(),
        notCachedIds = answers.filterValues { it == EventResult.NotCached }.keys.toSet(),
        pendingIds = ids - answers.keys,
        fromCache = registrationsFromCache || found.any { (_, result) -> result.fromCache },
    )
  }
}
