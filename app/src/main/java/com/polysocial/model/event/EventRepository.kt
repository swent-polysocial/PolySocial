// Contributors: Claude (drafted the EventRepository interface and the public upcoming-events
// query, #49); Mohamed Khellaf (reviewed).
package com.polysocial.model.event

import kotlinx.coroutines.flow.Flow

/** Outcome of [EventRepository.createEvent]. */
sealed interface CreateEventResult {
  /** The event was saved as `events/[eventId]`. */
  data class Created(val eventId: String) : CreateEventResult

  /** The event breaks the rules in [errors] (see [validateNewEvent]). Nothing was written. */
  data class Invalid(val errors: List<EventValidationError>) : CreateEventResult

  /** No user is signed in, so the event would have no creator. Nothing was written. */
  data object NotSignedIn : CreateEventResult

  /**
   * The device is offline. Nothing was written or queued, so the form keeps its input and the user
   * can try again (the offline error state in Figma).
   */
  data object NetworkError : CreateEventResult

  /** Any other failure, for example a write the Security Rules deny. */
  data object UnexpectedError : CreateEventResult
}

/** How many days ahead [EventRepository.getUpcomingPublicEvents] looks by default. */
const val DEFAULT_UPCOMING_WINDOW_DAYS = 14

/** What [EventRepository.getUpcomingPublicEvents] emits. */
sealed interface PublicEventsResult {
  /**
   * The current public upcoming events, sorted by start time.
   *
   * @property fromCache true when the list comes from Firestore's offline cache, for example while
   *   the device is offline. It may then miss events created since the last sync.
   */
  data class Events(val events: List<Event>, val fromCache: Boolean) : PublicEventsResult

  /** The events couldn't be read, for example because the Security Rules denied the query. */
  data object Error : PublicEventsResult
}

/** Stores events in `events/{id}`. */
interface EventRepository {
  /**
   * Validates [event] with [validateNewEvent], then saves it as created by the signed-in user (see
   * [withCreator]). The repository picks [Event.id] and sets [Event.createdBy],
   * [Event.organizerIds], [Event.allowedUids] and [Event.isAssociationEvent] itself, ignoring the
   * caller's values.
   *
   * The checks run in this order, and each one stops before anything is written: an invalid event
   * returns [CreateEventResult.Invalid], then a missing user returns
   * [CreateEventResult.NotSignedIn], then an offline device returns
   * [CreateEventResult.NetworkError]. Firestore would queue a write made offline instead of
   * failing, so the repository must not start one.
   */
  suspend fun createEvent(event: Event): CreateEventResult

  /**
   * Observes the public events that start within the next [windowDays] days (see
   * [isUpcomingPublicEvent]), for the map. Private events are never returned.
   *
   * The flow emits a new [PublicEventsResult.Events] whenever the events change, including from the
   * offline cache while the device is offline. It ends after a [PublicEventsResult.Error]. The
   * window is fixed when collection starts, and cancelling the collection stops listening.
   *
   * @throws IllegalArgumentException if [windowDays] is not positive.
   */
  fun getUpcomingPublicEvents(
      windowDays: Int = DEFAULT_UPCOMING_WINDOW_DAYS
  ): Flow<PublicEventsResult>
}
