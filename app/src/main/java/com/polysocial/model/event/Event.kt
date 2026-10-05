// Contributors: Claude (drafted the Event model and withCreator).
package com.polysocial.model.event

import java.time.Instant

/** A point on the map in degrees (WGS 84), independent of the map SDK. */
data class Coordinates(val latitude: Double, val longitude: Double)

/** The kind of event, picked from the chips on the Create Event form. */
enum class EventCategory {
  STUDY,
  SPORTS,
  CULTURE,
  PARTY,
}

/**
 * An event, stored as `events/{id}`.
 *
 * Anyone can create an event and becomes its organizer. [organizerIds] lists who can manage the
 * event and always contains [createdBy]. A private event is readable only by [allowedUids]: its
 * organizers, members and the students whose match request an organizer approved.
 * [isAssociationEvent] is true only for an event published by a verified association account, and
 * shows a verified badge.
 *
 * [EventRepository.createEvent] sets [id] and [createdBy], so a new event from the form leaves them
 * empty.
 *
 * @property endTime when the event ends, or null if the organizer didn't say.
 * @property capacity the maximum number of attendees, or null for no limit.
 */
data class Event(
    val id: String = "",
    val title: String,
    val description: String,
    val category: EventCategory,
    val location: Coordinates,
    val startTime: Instant,
    val endTime: Instant? = null,
    val capacity: Int? = null,
    val isPrivate: Boolean,
    val createdBy: String = "",
    val organizerIds: List<String> = emptyList(),
    val allowedUids: List<String> = emptyList(),
    val isAssociationEvent: Boolean = false,
)

/**
 * Returns this event as created by [uid]: [uid] becomes [Event.createdBy] and is added to
 * [Event.organizerIds], and every organizer is added to [Event.allowedUids] so that they can read
 * the event if it is private. No UID is listed twice.
 *
 * Repositories call this with the signed-in user's UID, so a creator chosen by the caller is never
 * trusted.
 */
fun Event.withCreator(uid: String): Event {
  val organizers = (listOf(uid) + organizerIds).distinct()
  return copy(
      createdBy = uid,
      organizerIds = organizers,
      allowedUids = (organizers + allowedUids).distinct(),
  )
}
