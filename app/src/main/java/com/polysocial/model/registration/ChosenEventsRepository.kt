// Contributors: Claude Opus 5.5 (medium) (drafted the chosen events, #203).
// Contributors: Claude (Opus 5.5, Anthropic) combined the readable private events with the chosen
// events, #320.
package com.polysocial.model.registration

import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.event.PrivateEventsResult
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
   * The signed-in student's chosen events: every event they registered for ("I'm going") and every
   * private event they may read (they organize it, are a member or were approved, #320), past and
   * upcoming, with no time window. Each event is listed once, even a private event they also
   * registered for. Each registered event ID that isn't one of the readable private events is in
   * exactly one of [events], [unavailableIds], [notCachedIds] and [pendingIds].
   *
   * @property events the events the student can read, sorted by start time (then ID, so the order
   *   is stable).
   * @property unavailableIds registered events that can't be shown: deleted, malformed, or no
   *   longer readable (a private event the student was removed from). Only their IDs are known, on
   *   purpose, like `EventResult.NotFound`, so a private event reveals nothing else. A private
   *   event listed only because the student may read it is never here: once it is deleted or they
   *   can no longer read it, it just leaves [events].
   * @property notCachedIds registered events that aren't in the offline cache while Firestore is
   *   offline (`EventResult.NotCached`). They appear once it is back online.
   * @property pendingIds registered events whose first read hasn't answered yet.
   * @property fromCache true when the registrations, the private events or any of [events] come
   *   from Firestore's offline cache. They may then be out of date.
   * @property waiting true while the registrations or the private events haven't answered yet, so
   *   their events may still be missing.
   * @property failed true when the registrations or the private events couldn't be read, so their
   *   events are missing until a new collection; the other read's events are still listed.
   */
  data class Events(
      val events: List<Event>,
      val unavailableIds: Set<String>,
      val notCachedIds: Set<String>,
      val pendingIds: Set<String>,
      val fromCache: Boolean,
      val waiting: Boolean = false,
      val failed: Boolean = false,
  ) : ChosenEventsResult

  /**
   * Neither the registrations nor the private events could be read: nobody is signed in, or both
   * queries failed.
   */
  data object Error : ChosenEventsResult
}

/**
 * The signed-in student's chosen events, for My events (#204): their own registrations
 * ([RegistrationRepository.observeMyRegistrations], #121) resolved to the events they can read
 * ([EventRepository.getEvent], #112), together with the private events they may read
 * ([EventRepository.getReadablePrivateEvents], #320), so a private event they created or were added
 * to shows without registering.
 *
 * It never reads other students' registrations or an event the student isn't allowed in. The
 * registrations come from the student's own collection-group query, each registered event is read
 * by ID under the event read rule, and the private events come from the one query that rule accepts
 * for them. Reading registered events by ID also lifts the 14-day window of the public events, and
 * one denied private event can't make the others fail, as one query over all of them would
 * (Security Rules aren't filters).
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
   * It emits a [ChosenEventsResult.Events] as soon as either read answers, then on every change: a
   * registration added ("I'm going") or removed (cancelled), a chosen event edited, deleted or
   * becoming unreadable, or a private event the student may read added or removed. Each registered
   * event has exactly one listener, kept while it stays registered and stopped when the
   * registration goes away. Offline, everything comes from the cache (`fromCache`), and a
   * registered event that was never read on this device is in `notCachedIds`, so a caller can end
   * its loading state instead of waiting forever.
   *
   * The registrations and the private events are read separately: if one fails, the other's events
   * are still listed, with `failed`. If both fail, the flow emits [ChosenEventsResult.Error],
   * always delivered last, and ends. A slow collector gets only the latest result. The student is
   * the one signed in when collection starts (see [RegistrationRepository.observeMyRegistrations]
   * and [EventRepository.getReadablePrivateEvents]), so a new account needs a new collection;
   * cancelling the collection stops every listener.
   */
  fun observeChosenEvents(): Flow<ChosenEventsResult> = channelFlow {
    val state = ChosenEvents()
    val listeners = mutableMapOf<String, Job>()
    val lock = Mutex()
    launch {
      events.getReadablePrivateEvents().collect { result ->
        lock.withLock {
          state.privateEvents(result)
          send(state.result())
        }
      }
    }
    registrations.observeMyRegistrations().collect { result ->
      lock.withLock {
        when (result) {
          MyRegistrationsResult.Error -> {
            listeners.values.forEach { it.cancel() }
            listeners.clear()
            state.registrationsFailed()
            send(state.result())
          }
          is MyRegistrationsResult.Registrations -> {
            val ids = result.eventIds
            (listeners - ids).values.forEach { it.cancel() }
            listeners.keys.retainAll(ids)
            state.registrations(ids, result.fromCache)
            (ids - listeners.keys).forEach { id ->
              listeners[id] = launch {
                events.getEvent(id).collect { answer ->
                  lock.withLock { if (state.answer(id, answer)) send(state.result()) }
                }
              }
            }
            send(state.result())
          }
        }
      }
    }
    lock.withLock { listeners.values.forEach { it.cancel() } }
  }
      .conflate()
      .distinctUntilChanged()
}

/**
 * What [ChosenEventsRepository] knows so far: the registered IDs and each event's last answer, and
 * the private events the student may read.
 */
private class ChosenEvents {
  private var ids: Set<String> = emptySet()
  private var registrationsAnswered = false
  private var registrationsFromCache = false
  private var registrationsFailed = false
  private val answers = mutableMapOf<String, EventResult>()

  /** The last answer of the private events, or null before the first one. */
  private var privateEvents: PrivateEventsResult? = null

  fun registrations(eventIds: Set<String>, fromCache: Boolean) {
    ids = eventIds
    registrationsAnswered = true
    registrationsFromCache = fromCache
    answers.keys.retainAll(eventIds)
  }

  /** The registrations failed: their events leave the list, and later answers are ignored. */
  fun registrationsFailed() {
    registrationsFailed = true
    ids = emptySet()
    registrationsFromCache = false
    answers.clear()
  }

  fun privateEvents(result: PrivateEventsResult) {
    privateEvents = result
  }

  /**
   * Records [result] for the event [id]. Returns false, recording nothing, once the registrations
   * failed or if [id] is no longer registered (a listener cancelled while it waited for the lock).
   */
  fun answer(id: String, result: EventResult): Boolean {
    if (registrationsFailed || id !in ids) return false
    answers[id] = result
    return true
  }

  fun result(): ChosenEventsResult {
    val private = privateEvents
    val privateFailed = private == PrivateEventsResult.Error
    if (registrationsFailed && privateFailed) return ChosenEventsResult.Error
    val readable = (private as? PrivateEventsResult.Events)?.events.orEmpty()
    val readableIds = readable.map { it.id }.toSet()
    // A registered event that is also a readable private event is listed once, from the latter.
    val registered = answers.filterKeys { it !in readableIds }
    val found = registered.mapNotNull { (id, result) ->
      (result as? EventResult.Found)?.let { id to it }
    }
    return ChosenEventsResult.Events(
        events =
            (found.map { (id, result) -> result.event.copy(id = id) } + readable).sortedWith(
                compareBy({ it.startTime }, { it.id })
            ),
        unavailableIds = registered.filterValues { it == EventResult.NotFound }.keys.toSet(),
        notCachedIds = registered.filterValues { it == EventResult.NotCached }.keys.toSet(),
        pendingIds = ids - readableIds - answers.keys,
        fromCache =
            registrationsFromCache ||
                (private as? PrivateEventsResult.Events)?.fromCache == true ||
                found.any { (_, result) -> result.fromCache },
        waiting = (!registrationsAnswered && !registrationsFailed) || private == null,
        failed = registrationsFailed || privateFailed,
    )
  }
}
