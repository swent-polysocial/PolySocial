// Contributors: Claude (drafted the EventRepository interface; the creator's profile, #45; the
// public upcoming-events query, #49; reading one event, #112; updating and deleting, #130); Mohamed
// Khellaf (reviewed).
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

/** Outcome of [EventRepository.updateEvent]. */
sealed interface UpdateEventResult {
  /** The event was saved with the new values. */
  data object Updated : UpdateEventResult

  /** The new values break the rules in [errors] (see [validateNewEvent]). Nothing was written. */
  data class Invalid(val errors: List<EventValidationError>) : UpdateEventResult

  /**
   * The user may not manage this event: they aren't one of its organizers, nobody is signed in, or
   * the Security Rules denied the write. Nothing was written.
   */
  data object NotOrganizer : UpdateEventResult

  /**
   * There is no event the user can read under this ID (see [EventResult.NotFound]), for example
   * because another organizer deleted it. Nothing was written.
   */
  data object NotFound : UpdateEventResult

  /** The device is offline. Nothing was written or queued, so the form keeps its input. */
  data object NetworkError : UpdateEventResult

  /** Any other failure. Nothing was written. */
  data object UnexpectedError : UpdateEventResult
}

/** Outcome of [EventRepository.deleteEvent]. */
sealed interface DeleteEventResult {
  /** The event was deleted. */
  data object Deleted : DeleteEventResult

  /** The user may not manage this event (see [UpdateEventResult.NotOrganizer]). Nothing changed. */
  data object NotOrganizer : DeleteEventResult

  /** There is no event the user can read under this ID (see [EventResult.NotFound]). */
  data object NotFound : DeleteEventResult

  /** The device is offline. Nothing was deleted or queued. */
  data object NetworkError : DeleteEventResult

  /** Any other failure. Nothing was deleted. */
  data object UnexpectedError : DeleteEventResult
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

/** What [EventRepository.getEvent] emits. */
sealed interface EventResult {
  /**
   * The event as stored now.
   *
   * @property fromCache true when it comes from Firestore's offline cache, for example while the
   *   device is offline. It may then be out of date.
   */
  data class Found(val event: Event, val fromCache: Boolean) : EventResult

  /**
   * There is no event the user can read under this ID: it never existed, it was deleted, it is
   * malformed, or the Security Rules don't let the user read it (a private event they aren't
   * allowed in). These cases look the same on purpose, so a private event doesn't reveal that it
   * exists.
   */
  data object NotFound : EventResult

  /**
   * The device is offline and the event isn't in the offline cache, so it can't be read yet. The
   * flow keeps listening and emits the event once the device is back online.
   */
  data object NotCached : EventResult
}

/**
 * True if [id] can name a document in `events`: not blank and without a slash, which would point to
 * another path. [EventRepository.getEvent] reads nothing for any other ID.
 */
fun isReadableEventId(id: String): Boolean = id.isNotBlank() && '/' !in id

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
   *
   * Then the creator's profile decides [Event.isAssociationEvent]: true only for a verified
   * association. If the profile can't be read, nothing is written and the result is
   * [CreateEventResult.NetworkError] or [CreateEventResult.UnexpectedError], so a verified
   * association never publishes without its badge. A user without a profile gets no badge.
   */
  suspend fun createEvent(event: Event): CreateEventResult

  /**
   * Saves [event]'s new values over the stored `events/[Event.id]`, if the signed-in user is one of
   * its organizers. The stored [Event.createdBy], [Event.organizerIds], [Event.allowedUids] and
   * [Event.isAssociationEvent] are kept, ignoring the caller's values (see [withOwnershipOf]).
   *
   * The checks run in this order, and each one stops before anything is written: new values that
   * [validateNewEvent] rejects return [UpdateEventResult.Invalid] (so an event can't be moved to
   * the past), an ID that can't be a document ID (see [isReadableEventId]) returns
   * [UpdateEventResult.NotFound], a missing user returns [UpdateEventResult.NotOrganizer], and an
   * offline device returns [UpdateEventResult.NetworkError] without starting a write, like
   * [createEvent]. Then the stored event is read and written in one step, so the organizers checked
   * are the ones the update keeps.
   */
  suspend fun updateEvent(event: Event): UpdateEventResult

  /**
   * Deletes `events/[id]`, if the signed-in user is one of its organizers. In Sprint 2, cancelling
   * an event deletes it (there is no "cancelled" status).
   *
   * Like [updateEvent], an ID that can't be a document ID returns [DeleteEventResult.NotFound], a
   * missing user [DeleteEventResult.NotOrganizer] and an offline device
   * [DeleteEventResult.NetworkError], each before anything is deleted. An event that no longer
   * exists is [DeleteEventResult.NotFound], not a success.
   */
  suspend fun deleteEvent(id: String): DeleteEventResult

  /**
   * Observes the public events that start within the next [windowDays] days (see
   * [isUpcomingPublicEvent]), for the map. Private events are never returned.
   *
   * The flow emits a new [PublicEventsResult.Events] whenever the events change, including from the
   * offline cache while the device is offline. A slow collector gets only the latest result. It
   * ends after a [PublicEventsResult.Error], which is always delivered first. The window is fixed
   * when collection starts, and cancelling the collection stops listening.
   *
   * @throws IllegalArgumentException if [windowDays] is not positive.
   */
  fun getUpcomingPublicEvents(
      windowDays: Int = DEFAULT_UPCOMING_WINDOW_DAYS
  ): Flow<PublicEventsResult>

  /**
   * Observes the event `events/[id]`, for its detail screen.
   *
   * The flow emits a new result whenever the event changes, including [EventResult.NotFound] once
   * it is deleted, and [EventResult.Found] with `fromCache` from the offline cache while the device
   * is offline. A slow collector gets only the latest result. If the read is denied, the flow emits
   * [EventResult.NotFound] and ends. An ID that can't be a document ID (see [isReadableEventId]) is
   * [EventResult.NotFound] without reading anything. Cancelling the collection stops listening.
   */
  fun getEvent(id: String): Flow<EventResult>
}
