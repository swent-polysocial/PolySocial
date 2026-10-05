// Contributors: Claude (drafted the EventRepository interface).
package com.polysocial.model.event

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

/** Stores events in `events/{id}`. Reading events comes with the Map epic (#49). */
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
}
