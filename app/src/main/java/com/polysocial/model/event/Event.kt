// Contributors: Claude (drafted the Event model and withCreator; the place name, #112); Mohamed
// Khellaf (reviewed). Claude (keeping the stored organizers on update, #130); Franciszek Najda
// (reviewed).
// Contributors: Claude (Opus 5.5, Anthropic) (the attendee count and when an event is full, #373).
// Contributors: Claude (Sonnet 5.5, Anthropic) (the sections an event is open to, #369).
package com.polysocial.model.event

import com.polysocial.model.user.orderedSections
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
 * [EventRepository.createEvent] sets [id], [createdBy], [organizerIds], [allowedUids],
 * [attendeeCount] and [isAssociationEvent] (see [withCreator]), so a new event from the form leaves
 * them at their defaults.
 *
 * @property endTime when the event ends, or null if the organizer didn't say. Always after
 *   [startTime]: the form treats an end time earlier than the start time as the next day.
 * @property capacity the maximum number of attendees, or null for no limit.
 * @property attendeeCount how many students are registered (#372, decision 21). Only a registration
 *   or a cancellation changes it, in the same write as the registration; the app never sends it
 *   with the rest of the event. 0 for an event stored before the counter existed.
 * @property locationName the name of the place picked on the map ("Rolex Learning Center"), shown
 *   with the location. Blank for events created before it was stored.
 * @property allowedSections the EPFL sections (codes of [com.polysocial.model.user.SECTIONS]) whose
 *   students can read this public event, or empty for every section (#369). Always stored, so the
 *   Security Rules and the list queries can rely on it; only a student's public event has any.
 *   Organizers read the event whatever their section.
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
    val attendeeCount: Int = 0,
    val isPrivate: Boolean,
    val createdBy: String = "",
    val organizerIds: List<String> = emptyList(),
    val allowedUids: List<String> = emptyList(),
    val isAssociationEvent: Boolean = false,
    val allowedSections: List<String> = emptyList(),
)

/**
 * Whether the event is open to every section: it has no [Event.allowedSections] restriction. Only a
 * public event of a student can be restricted (#369).
 */
fun Event.isOpenToAllSections(): Boolean = allowedSections.isEmpty()

/**
 * Whether the event has no place left: it has a [Event.capacity] and [Event.attendeeCount] has
 * reached it. A capacity lowered below the count is full too. It agrees with the Security Rules,
 * which accept a registration only while the count stays within the capacity, also for a capacity
 * stored as a decimal, since [Event.capacity] is read truncated.
 */
fun Event.isFull(): Boolean = capacity != null && attendeeCount >= capacity

/**
 * Returns this event as newly created by [uid]: [uid] becomes [Event.createdBy], the only organizer
 * and the only allowed reader, [Event.attendeeCount] is 0, and [Event.isAssociationEvent] is true
 * only if the creator [isVerifiedAssociation]. The caller's values for these fields are replaced,
 * so the form can't add organizers or readers, or give itself the verified badge. The sections are
 * kept only for a public event of a student (see [withSectionRestriction]).
 *
 * Repositories call this with the signed-in user's UID and the creator's profile.
 */
fun Event.withCreator(uid: String, isVerifiedAssociation: Boolean): Event =
    copy(
            createdBy = uid,
            organizerIds = listOf(uid),
            allowedUids = listOf(uid),
            attendeeCount = 0,
            isAssociationEvent = isVerifiedAssociation,
        )
        .withSectionRestriction()

/**
 * Returns this event as an update of [stored]: it keeps [stored]'s [Event.id], [Event.createdBy],
 * [Event.organizerIds], [Event.allowedUids], [Event.attendeeCount], [Event.isAssociationEvent] and
 * [Event.allowedSections], replacing the caller's values, so editing an event can't change who
 * manages or reads it, how many are going, or its verified badge. The sections are the ones stored
 * now, not the ones the edit form loaded: a co-organizer may have narrowed them while the form was
 * open, and an unrelated edit must not bring the old audience back (#369). Changing them is a
 * separate, explicit action that the section picker adds.
 *
 * Repositories call this with the event as it is stored now.
 */
fun Event.withOwnershipOf(stored: Event): Event =
    copy(
            id = stored.id,
            createdBy = stored.createdBy,
            organizerIds = stored.organizerIds,
            allowedUids = stored.allowedUids,
            attendeeCount = stored.attendeeCount,
            isAssociationEvent = stored.isAssociationEvent,
            allowedSections = stored.allowedSections,
        )
        .withSectionRestriction()

/**
 * Returns this event with its [Event.allowedSections] in the order of
 * [com.polysocial.model.user.SECTIONS], without duplicates or unknown codes, and none at all for a
 * private event or an association's: the restriction is for a student's public event (#369), which
 * the Security Rules also enforce.
 */
fun Event.withSectionRestriction(): Event =
    copy(
        allowedSections =
            if (isPrivate || isAssociationEvent) emptyList() else orderedSections(allowedSections)
    )
