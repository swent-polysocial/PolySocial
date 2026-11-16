// Contributors: Claude Opus 5.5 (drafted the Firestore mapping for friendships, #357); Konstantinos
// Perdikeas (directed, reviewed and owns it).
package com.polysocial.model.friend

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue

/** Top-level collection of friend requests and friendships, one document per pair (#338). */
const val FRIENDSHIPS_COLLECTION = "friendships"

/** The two students' UIDs, sorted: also the field of the own-friendships query. */
const val FRIENDSHIP_UIDS = "uids"

private const val REQUESTED_BY = "requestedBy"
private const val STATUS = "status"
private const val CREATED_AT = "createdAt"
private const val ACCEPTED_AT = "acceptedAt"
private const val PENDING = "pending"
private const val ACCEPTED = "accepted"

/**
 * The fields of a request [fromUid] sends to [toUid]: the two UIDs sorted, the sender, `pending`
 * and the server's time, the only values the Security Rules accept for a new request.
 */
fun newFriendRequestFirestoreMap(fromUid: String, toUid: String): Map<String, Any> =
    mapOf(
        FRIENDSHIP_UIDS to listOf(fromUid, toUid).sorted(),
        REQUESTED_BY to fromUid,
        STATUS to PENDING,
        CREATED_AT to FieldValue.serverTimestamp(),
    )

/** The only change the Security Rules accept when the recipient accepts a request. */
fun acceptanceFirestoreMap(): Map<String, Any> =
    mapOf(STATUS to ACCEPTED, ACCEPTED_AT to FieldValue.serverTimestamp())

/**
 * Reads the friendship `friendships/[id]` from its stored [data], written by hand like groups (no
 * automatic mapping).
 *
 * Returns null when there is no data, when `uids` isn't two sorted UIDs whose [friendshipId] is
 * [id], when `requestedBy` isn't one of them, when `status` isn't `pending` or `accepted`, or when
 * `createdAt` isn't a timestamp, so one malformed document can't crash a screen or be acted on. A
 * missing or wrong-typed `acceptedAt` reads as null.
 */
fun friendshipFromFirestore(id: String, data: Map<String, Any?>?): Friendship? {
  if (data == null) return null
  val uids = data[FRIENDSHIP_UIDS] as? List<*> ?: return null
  if (uids.size != 2) return null
  val first = uids[0] as? String ?: return null
  val second = uids[1] as? String ?: return null
  if (first >= second || friendshipId(first, second) != id) return null
  val requestedBy = data[REQUESTED_BY] as? String ?: return null
  if (requestedBy != first && requestedBy != second) return null
  val status =
      when (data[STATUS]) {
        PENDING -> FriendshipStatus.PENDING
        ACCEPTED -> FriendshipStatus.ACCEPTED
        else -> return null
      }
  val createdAt = (data[CREATED_AT] as? Timestamp)?.toInstant() ?: return null
  return Friendship(
      id = id,
      uids = listOf(first, second),
      requestedBy = requestedBy,
      status = status,
      createdAt = createdAt,
      acceptedAt = (data[ACCEPTED_AT] as? Timestamp)?.toInstant(),
  )
}
