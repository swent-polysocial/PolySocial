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

  data object NetworkError : CreateEventResult

  data object UnexpectedError : CreateEventResult
}

/** Stores events in `events/{id}`. Reading events comes with the Map epic (#49). */
interface EventRepository {
  /**
   * Validates [event] with [validateNewEvent], then saves it with the signed-in user as its creator
   * (see [withCreator]). The [Event.id] and [Event.createdBy] given by the caller are ignored: the
   * repository picks the ID and always uses the signed-in user's UID.
   */
  suspend fun createEvent(event: Event): CreateEventResult
}
