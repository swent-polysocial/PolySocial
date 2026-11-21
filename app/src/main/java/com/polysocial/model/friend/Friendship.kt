// Contributors: Claude Opus 5.5 (drafted the friendship model and pair ID, #357; the pair check,
// second PR); Konstantinos Perdikeas (directed, reviewed and owns it).
// Contributors: Claude (Opus 5.5, Anthropic) (the two UIDs of a pair ID, for one-to-one chats,
// #428).
package com.polysocial.model.friend

import com.polysocial.model.qr.isQrId
import java.time.Instant

/** Where a friendship stands. Declining, cancelling and removing delete it, so it has no third. */
enum class FriendshipStatus {
  /** [Friendship.requestedBy] sent the request and the other student hasn't accepted it yet. */
  PENDING,

  /** The other student accepted the request: the two are friends. */
  ACCEPTED,
}

/**
 * A friend request or friendship between two students, stored as `friendships/{id}` (#338). It
 * holds only UIDs: names come from each student's public profile.
 *
 * @property id [friendshipId] of the two students, the same whoever sent the request.
 * @property uids the two students' UIDs, sorted.
 * @property requestedBy the UID of the student who sent the request.
 * @property createdAt when the server stored the request.
 * @property acceptedAt when the server stored the acceptance, or null while [status] is
 *   [FriendshipStatus.PENDING].
 */
data class Friendship(
    val id: String,
    val uids: List<String>,
    val requestedBy: String,
    val status: FriendshipStatus,
    val createdAt: Instant,
    val acceptedAt: Instant?,
)

/** The separator in a [friendshipId]. A valid UID never contains it. */
private const val PAIR_SEPARATOR = "~"

/**
 * The ID of the one friendship document two students can share: their UIDs sorted and joined by
 * `~`, `{lowerUid}~{higherUid}`. A request from either student gets the same ID, so there is never
 * more than one between them. This is the only place the ID is built.
 *
 * Returns null when the two can't be a pair: the same UID twice (nobody is their own friend), or a
 * UID that isn't a valid ID (see `isQrId`: 1 to 128 of `[A-Za-z0-9_-]`, never Firestore's reserved
 * `__…__`), so a `~` or `/` can never get into the ID. Those characters are all ASCII, so sorting
 * by character code here gives the same order as the Security Rules' string comparison.
 */
fun friendshipId(uidA: String, uidB: String): String? {
  if (uidA == uidB || !isQrId(uidA) || !isQrId(uidB)) return null
  return if (uidA < uidB) "$uidA$PAIR_SEPARATOR$uidB" else "$uidB$PAIR_SEPARATOR$uidA"
}

/**
 * The two UIDs [id] is the [friendshipId] of, in its order (the lower first), or null when [id]
 * isn't a [friendshipId]: more or fewer than two parts, unsorted halves or an invalid UID. A
 * one-to-one chat under the pair's ID holds these as its `uids` (#365).
 */
fun uidsOfFriendshipId(id: String): List<String>? {
  val halves = id.split(PAIR_SEPARATOR)
  return if (halves.size == 2 && friendshipId(halves[0], halves[1]) == id) halves else null
}

/**
 * Whether [id] is a [friendshipId] with [uid] as one of its two halves: what the Security Rules
 * check before a student may read a pair's document, even a missing one. An ID with more or fewer
 * than two parts, unsorted halves or an invalid UID is nobody's (see [uidsOfFriendshipId]).
 */
fun isFriendshipIdOf(id: String, uid: String): Boolean = uid in uidsOfFriendshipId(id).orEmpty()

/** The other student of the pair, or null when [myUid] isn't one of the two. */
fun Friendship.otherUid(myUid: String): String? =
    if (myUid in uids) uids.firstOrNull { it != myUid } else null
