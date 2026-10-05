// Contributors: Claude (wrote this fake for tests).
package com.polysocial.model.event

import java.time.Instant

/**
 * In-memory [EventRepository] for tests. It follows the [EventRepository.createEvent] contract,
 * with a fixed clock: validation, then the signed-in user, then [failure], then the save.
 *
 * @property currentUid the signed-in user, or null when nobody is signed in.
 * @property creatorIsVerifiedAssociation whether the signed-in user is a verified association.
 * @property now the time used to reject events that start in the past.
 */
class FakeEventRepository(
    var currentUid: String? = "test-creator",
    var creatorIsVerifiedAssociation: Boolean = false,
    var now: Instant = TEST_NOW,
) : EventRepository {
  /** The events saved so far, in creation order. */
  val events = mutableListOf<Event>()

  /**
   * When set, [createEvent] returns this result instead of saving a valid event, to simulate a
   * failure such as [CreateEventResult.NetworkError].
   */
  var failure: CreateEventResult? = null

  override suspend fun createEvent(event: Event): CreateEventResult {
    val errors = validateNewEvent(event, now)
    if (errors.isNotEmpty()) return CreateEventResult.Invalid(errors)
    val uid = currentUid ?: return CreateEventResult.NotSignedIn
    failure?.let {
      return it
    }
    val saved =
        event.withCreator(uid, creatorIsVerifiedAssociation).copy(id = "event-${events.size + 1}")
    events += saved
    return CreateEventResult.Created(saved.id)
  }
}
