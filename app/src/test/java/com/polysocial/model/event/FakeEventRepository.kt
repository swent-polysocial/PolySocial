// Contributors: Claude (wrote this fake for tests).
package com.polysocial.model.event

import java.time.Instant

/**
 * In-memory [EventRepository] for tests. It validates and stamps the creator like the real
 * repository, with a fixed clock.
 *
 * @property currentUid the signed-in user, or null when nobody is signed in.
 * @property now the time used to reject events that start in the past.
 */
class FakeEventRepository(
    var currentUid: String? = "test-creator",
    var now: Instant = TEST_NOW,
) : EventRepository {
  /** The events saved so far, in creation order. */
  val events = mutableListOf<Event>()

  /** When set, [createEvent] returns this result without saving, to simulate a failure. */
  var failure: CreateEventResult? = null

  override suspend fun createEvent(event: Event): CreateEventResult {
    failure?.let {
      return it
    }
    val errors = validateNewEvent(event, now)
    if (errors.isNotEmpty()) return CreateEventResult.Invalid(errors)
    val uid = currentUid ?: return CreateEventResult.NotSignedIn
    val saved = event.withCreator(uid).copy(id = "event-${events.size + 1}")
    events += saved
    return CreateEventResult.Created(saved.id)
  }
}
