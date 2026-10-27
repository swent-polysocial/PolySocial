// Contributors: Claude (drafted the Event model and withCreator; the place name, #112); Mohamed
// Khellaf (reviewed). Claude (keeping the stored organizers on update, #130); Franciszek Najda
// (reviewed).
package com.polysocial.model.event

import java.time.Instant

/** A point on the map in degrees (WGS 84), independent of the map SDK. */
data class Coordinates(val latitude: Double, val longitude: Double)

/**
 * The kind of event, picked from the chips on the Create Event form. [OTHER] is for events that fit
 * none of the others, and it is also how the app shows a stored category it doesn't know.
 */
enum class EventCategory {
  STUDY,
  SPORTS,
  CULTURE,
  PARTY,
  OTHER,
}

/**
 * An event, stored as `events/{id}`.
 *
 * Anyone can create an event and becomes its organizer. [organizerIds] lists who can manage the
 * event and always contains [createdBy]. A private event is readable only by [allowedUids]: its
 * organizers, members and approved match requesters. [isAssociationEvent] is true only for an event
 * published by a verified association account, and shows a verified badge.
 *
 * [EventRepository.createEvent] sets [id], [createdBy], [organizerIds], [allowedUids] and
 * [isAssociationEvent] (see [withCreator]), so a new event from the form leaves them at their
 * defaults.
 *
 * @property endTime when the event ends, or null if the organizer didn't say. Always after
 *   [startTime]: the form treats an end time earlier than the start time as the next day.
 * @property capacity the maximum number of attendees, or null for no limit.
 * @property locationName the name of the place picked on the map ("Rolex Learning Center"), shown
 *   with the location. Blank for events created before it was stored.
 */
data class Event(
    val id: String = "",
    val title: String,
    val description: String,
    val category: EventCategory,
    val location: Coordinates,
    val locationName: String = "",
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
 * Returns this event as newly created by [uid]: [uid] becomes [Event.createdBy], the only organizer
 * and the only allowed reader, and [Event.isAssociationEvent] is true only if the creator
 * [isVerifiedAssociation]. The caller's values for these fields are replaced, so the form can't add
 * organizers or readers, or give itself the verified badge.
 *
 * Repositories call this with the signed-in user's UID and the creator's profile.
 */
fun Event.withCreator(uid: String, isVerifiedAssociation: Boolean): Event =
    copy(
        createdBy = uid,
        organizerIds = listOf(uid),
        allowedUids = listOf(uid),
        isAssociationEvent = isVerifiedAssociation,
    )

/**
 * Returns this event as an update of [stored]: it keeps [stored]'s [Event.id], [Event.createdBy],
 * [Event.organizerIds], [Event.allowedUids] and [Event.isAssociationEvent], replacing the caller's
 * values, so editing an event can't change who manages or reads it, or its verified badge.
 *
 * Repositories call this with the event as it is stored now.
 */
fun Event.withOwnershipOf(stored: Event): Event =
    copy(
        id = stored.id,
        createdBy = stored.createdBy,
        organizerIds = stored.organizerIds,
        allowedUids = stored.allowedUids,
        isAssociationEvent = stored.isAssociationEvent,
    )
