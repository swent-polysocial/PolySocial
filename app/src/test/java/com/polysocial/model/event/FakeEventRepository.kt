// Contributors: Claude (wrote this fake for tests, including the upcoming-events query, #49);
// Mohamed Khellaf (reviewed).
package com.polysocial.model.event

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * In-memory [EventRepository] for tests. It follows the [EventRepository.createEvent] contract,
 * with a fixed clock: validation, then the signed-in user, then [failure], then the save. Tests
 * that read events [seed] them first.
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

  /** When true, [getUpcomingPublicEvents] emits [PublicEventsResult.Error], like a denied query. */
  var readFailure = false

  /** The `fromCache` flag [getUpcomingPublicEvents] reports, to simulate being offline. */
  var readFromCache = false

  /** Bumped on every change to [events], so [getUpcomingPublicEvents] emits again. */
  private val changes = MutableStateFlow(0)

  /** Adds [seeded] as events already stored, for tests that read events. */
  fun seed(vararg seeded: Event) {
    events += seeded
    changes.value++
  }

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
    changes.value++
    return CreateEventResult.Created(saved.id)
  }

  /** Emits the stored events that [isUpcomingPublicEvent] keeps at [now], and again on changes. */
  override fun getUpcomingPublicEvents(windowDays: Int): Flow<PublicEventsResult> {
    require(windowDays > 0) { "windowDays must be positive, was $windowDays" }
    if (readFailure) return flowOf(PublicEventsResult.Error)
    val start = now
    return changes.map {
      PublicEventsResult.Events(
          events.filter { it.isUpcomingPublicEvent(start, windowDays) }.sortedBy { it.startTime },
          readFromCache,
      )
    }
  }
}
