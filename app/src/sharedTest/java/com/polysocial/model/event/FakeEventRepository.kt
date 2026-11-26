// Contributors: Claude (wrote this fake for tests, including the upcoming-events query, #49, the
// pending save, #46, and reading one event, #112; the ID guard, #112); Mohamed Khellaf
// (reviewed). Claude (updating and deleting, #130; an association's own events, #132); Franciszek
// Najda (reviewed).
// Contributors: Claude (Opus 5.5, Anthropic) added the readable private events to this fake, #320.
// Contributors: Claude (Opus 5.5, Anthropic) added co-organizer changes to this fake, #370, and
// read the registrations from a FakeRegistrationRepository, #370 review.
// Contributors: Claude (Sonnet 5.5, Anthropic) (events restricted to sections, #369).
package com.polysocial.model.event

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * In-memory [EventRepository] for tests. It follows the [EventRepository.createEvent] contract,
 * with a fixed clock: validation, then the signed-in user, then [pending], then [failure], then the
 * save. [updateEvent] and [deleteEvent] follow their contracts the same way, with [pending] and
 * then [updateFailure] or [deleteFailure] before the stored event is checked. Tests that read,
 * update or delete events [seed] them first.
 *
 * @property currentUid the signed-in user, or null when nobody is signed in.
 * @property creatorIsVerifiedAssociation whether the signed-in user is a verified association.
 * @property now the time used to reject events that start in the past.
 * @property registeredUids the students registered for an event, by its ID, which [updateEvent]
 *   checks before adding them as organizers, like the repository reading their registrations. Pass
 *   a `FakeRegistrationRepository`'s `attendeesOf`, so a test seeds each registration once.
 */
class FakeEventRepository(
    var currentUid: String? = "test-creator",
    var creatorIsVerifiedAssociation: Boolean = false,
    var now: Instant = TEST_NOW,
    private val registeredUids: (eventId: String) -> Set<String> = { emptySet() },
) : EventRepository {
  /** The events saved so far, in creation order. */
  val events = mutableListOf<Event>()

  /**
   * When set, [createEvent] returns this result instead of saving a valid event, to simulate a
   * failure such as [CreateEventResult.NetworkError].
   */
  var failure: CreateEventResult? = null

  /**
   * When set, [createEvent] waits for it to complete before going on, so a test can check what the
   * screen shows while the event is being saved.
   */
  var pending: CompletableDeferred<Unit>? = null

  /** When set, [updateEvent] returns this result instead of saving, like a failed write. */
  var updateFailure: UpdateEventResult? = null

  /** When set, [deleteEvent] returns this result instead of deleting, like a failed write. */
  var deleteFailure: DeleteEventResult? = null

  /** The events passed to [updateEvent] so far, valid or not, in call order. */
  val updateRequests = mutableListOf<Event>()

  /** The organizer changes passed to [updateEvent] so far, in call order. */
  val organizerRequests = mutableListOf<OrganizerChanges>()

  /** The IDs passed to [deleteEvent] so far, in call order. */
  val deleteRequests = mutableListOf<String>()

  /** When true, [getUpcomingPublicEvents] emits [PublicEventsResult.Error], like a denied query. */
  var readFailure = false

  /**
   * When true, [getOwnAssociationEvents] emits [OwnEventsResult.Error], like a query the rules
   * deny.
   */
  var ownEventsFailure = false

  /**
   * When true, [getReadablePrivateEvents] emits [PrivateEventsResult.Error], like a query the rules
   * deny.
   */
  var privateEventsFailure = false

  /**
   * The signed-in student's section, which decides the events restricted to sections that
   * [getUpcomingPublicEvents] and [getEvent] show them (#369). Null, the default, is a student whose
   * section can't be read: only the events open to every section. An organizer reads their event
   * whatever their section, like the Security Rules.
   */
  var viewerSection: String? = null

  /** The `fromCache` flag the event flows report, to simulate being offline. */
  var readFromCache = false

  /** Bumped on every change to [events], so [getUpcomingPublicEvents] emits again. */
  private val changes = MutableStateFlow(0)

  /** IDs [getEvent] treats as unreadable, like a private event the Security Rules deny. */
  val deniedIds = mutableSetOf<String>()

  /** Adds [seeded] as events already stored, for tests that read events. */
  fun seed(vararg seeded: Event) {
    events += seeded
    changes.value++
  }

  /** Deletes the event [id], so [getEvent] emits [EventResult.NotFound] for it. */
  fun remove(id: String) {
    events.removeAll { it.id == id }
    changes.value++
  }

  override suspend fun createEvent(event: Event): CreateEventResult {
    val errors = validateNewEvent(event, now)
    if (errors.isNotEmpty()) return CreateEventResult.Invalid(errors)
    val uid = currentUid ?: return CreateEventResult.NotSignedIn
    pending?.await()
    failure?.let {
      return it
    }
    val saved =
        event.withCreator(uid, creatorIsVerifiedAssociation).copy(id = "event-${events.size + 1}")
    events += saved
    changes.value++
    return CreateEventResult.Created(saved.id)
  }

  override suspend fun updateEvent(
      event: Event,
      organizers: OrganizerChanges,
  ): UpdateEventResult {
    updateRequests += event
    organizerRequests += organizers
    val errors = validateNewEvent(event, now) - EventValidationError.START_IN_PAST
    if (errors.isNotEmpty()) return UpdateEventResult.Invalid(errors)
    if (!isReadableEventId(event.id)) return UpdateEventResult.NotFound
    val uid = currentUid ?: return UpdateEventResult.NotOrganizer
    pending?.await()
    updateFailure?.let {
      return it
    }
    val index = readableIndexOf(event.id)
    if (index < 0) return UpdateEventResult.NotFound
    val stored = events[index]
    if (uid !in stored.organizerIds) return UpdateEventResult.NotOrganizer
    val startErrors = validateEventUpdate(event, stored, now)
    if (startErrors.isNotEmpty()) return UpdateEventResult.Invalid(startErrors)
    val update =
        when (val changes = applyOrganizerChanges(stored, organizers)) {
          is OrganizersUpdate.Refused -> return UpdateEventResult.InvalidOrganizers(changes.error)
          is OrganizersUpdate.Valid -> changes
        }
    if (!registeredUids(stored.id).containsAll(update.newOrganizers)) {
      return UpdateEventResult.InvalidOrganizers(OrganizerError.NOT_REGISTERED)
    }
    events[index] =
        event
            .withOwnershipOf(stored)
            .copy(organizerIds = update.organizerIds, allowedUids = update.allowedUids)
    changes.value++
    return UpdateEventResult.Updated
  }

  override suspend fun deleteEvent(id: String): DeleteEventResult {
    deleteRequests += id
    if (!isReadableEventId(id)) return DeleteEventResult.NotFound
    val uid = currentUid ?: return DeleteEventResult.NotOrganizer
    pending?.await()
    deleteFailure?.let {
      return it
    }
    val index = readableIndexOf(id)
    if (index < 0) return DeleteEventResult.NotFound
    if (uid !in events[index].organizerIds) return DeleteEventResult.NotOrganizer
    events.removeAt(index)
    changes.value++
    return DeleteEventResult.Deleted
  }

  /** The index of the stored event [id], or -1 if it is missing or in [deniedIds]. */
  private fun readableIndexOf(id: String): Int =
      if (id in deniedIds) -1 else events.indexOfFirst { it.id == id }

  /** Emits the stored events that [isUpcomingPublicEvent] keeps at [now], and again on changes. */
  override fun getUpcomingPublicEvents(windowDays: Int): Flow<PublicEventsResult> {
    require(windowDays > 0) { "windowDays must be positive, was $windowDays" }
    if (readFailure) return flowOf(PublicEventsResult.Error)
    val start = now
    return changes.map {
      PublicEventsResult.Events(
          events
              .filter { it.isUpcomingPublicEvent(start, windowDays) && it.isOpenToViewer() }
              .sortedBy { it.startTime },
          readFromCache,
      )
    }
  }

  /**
   * Emits the stored association events of [currentUid], sorted by start time, and again on
   * changes. Nobody signed in, or [ownEventsFailure], is [OwnEventsResult.Error], like the real
   * repository.
   */
  override fun getOwnAssociationEvents(): Flow<OwnEventsResult> {
    val uid = currentUid
    if (uid == null || ownEventsFailure) return flowOf(OwnEventsResult.Error)
    return changes.map {
      OwnEventsResult.Events(
          events
              .filter { it.createdBy == uid && it.isAssociationEvent }
              .sortedBy { it.startTime },
          readFromCache,
      )
    }
  }

  /**
   * Emits the stored private events whose `allowedUids` list [currentUid], sorted by start time
   * (then ID), and again on changes. The user is read when collection starts. Nobody signed in, or
   * [privateEventsFailure], is [PrivateEventsResult.Error], like the real repository.
   */
  override fun getReadablePrivateEvents(): Flow<PrivateEventsResult> {
    val uid = currentUid
    if (uid == null || privateEventsFailure) return flowOf(PrivateEventsResult.Error)
    return changes.map {
      PrivateEventsResult.Events(
          events
              .filter { it.isPrivate && uid in it.allowedUids }
              .sortedWith(compareBy({ it.startTime }, { it.id })),
          readFromCache,
      )
    }
  }

  /**
   * Emits the stored event [id] and again on changes. A missing event is [EventResult.NotCached]
   * while [readFromCache] is true (offline) and [EventResult.NotFound] otherwise. An ID in
   * [deniedIds] is [EventResult.NotFound] and ends the flow, like a denied read, and so is an ID
   * that can't be a document ID (blank or containing a slash), like the real repository.
   */
  override fun getEvent(id: String): Flow<EventResult> {
    if (!isReadableEventId(id) || id in deniedIds) return flowOf(EventResult.NotFound)
    return changes.map {
      val event = events.firstOrNull { it.id == id }?.takeIf { it.isOpenToViewer() }
      when {
        event != null -> EventResult.Found(event, readFromCache)
        readFromCache -> EventResult.NotCached
        else -> EventResult.NotFound
      }
    }
  }

  /**
   * Whether [viewerSection] (or an organizer of the event, which is [currentUid]) can read this
   * event, as the Security Rules decide (#369): it has no restriction, or lists the section.
   */
  private fun Event.isOpenToViewer(): Boolean =
      allowedSections.isEmpty() ||
          (viewerSection != null && viewerSection in allowedSections) ||
          (currentUid != null && currentUid in organizerIds)

}
