// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Firestore mapping for groups, #232).
package com.polysocial.model.group

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue

/** Subcollection of `events/{eventId}` holding its groups. */
const val GROUPS_COLLECTION = "groups"

/**
 * Subcollection of `events/{eventId}` holding one marker per student in a group of the event, keyed
 * by the student's UID, with the group's ID in [MARKER_GROUP_ID].
 */
const val GROUP_MEMBERS_COLLECTION = "groupMembers"

/** The marker's only field: the ID of the student's group for the event. */
const val MARKER_GROUP_ID = "groupId"

/** The members' UIDs in a group document, also the field of the own-groups queries. */
const val MEMBER_UIDS = "memberUids"

/**
 * The fields of a group [uid] starts: [GROUP_CAPACITY] places, [uid] as its creator and only
 * member, and the server's time, the only values the Security Rules accept for a new group.
 */
fun newGroupFirestoreMap(uid: String): Map<String, Any> =
    mapOf(
        "capacity" to GROUP_CAPACITY,
        MEMBER_UIDS to listOf(uid),
        "createdBy" to uid,
        "createdAt" to FieldValue.serverTimestamp(),
    )

/**
 * Reads the group `events/[eventId]/groups/[id]` from its stored [data], written by hand like
 * events (no automatic mapping).
 *
 * Returns null when there is no data, when `capacity` isn't a positive number, or when `memberUids`
 * isn't a non-empty list of UIDs, so one malformed document can't crash a screen and is never
 * joined. A missing `createdBy` reads as blank, and `createdAt` as null, which it also is while the
 * server hasn't set it yet.
 */
fun groupFromFirestore(id: String, eventId: String, data: Map<String, Any?>?): Group? {
  if (data == null) return null
  // Firestore returns every whole number as a Long.
  val capacity = (data["capacity"] as? Number)?.toInt()?.takeIf { it > 0 } ?: return null
  val members = data[MEMBER_UIDS] as? List<*> ?: return null
  if (members.isEmpty() || members.any { it !is String }) return null
  return Group(
      id = id,
      eventId = eventId,
      capacity = capacity,
      memberUids = members.filterIsInstance<String>(),
      createdBy = data["createdBy"] as? String ?: "",
      createdAt = (data["createdAt"] as? Timestamp)?.toInstant(),
  )
}
