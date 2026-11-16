// Contributors: Claude Opus 5.5 (drafted the friend repository contract, #357); Konstantinos
// Perdikeas (directed, reviewed and owns it).
package com.polysocial.model.friend

import kotlinx.coroutines.flow.Flow

/** What [FriendRepository.observeFriendships] emits. */
sealed interface FriendshipsResult {
  /**
   * Every friend request and friendship of the signed-in student, sent or received, in no
   * particular order. The friends screens split them into friends, incoming and sent requests. A
   * stored friendship that can't be read (a missing or wrong-typed field) is skipped.
   *
   * @property fromCache true when they come from Firestore's offline cache, for example while the
   *   device is offline. They may then be out of date.
   */
  data class Success(val friendships: List<Friendship>, val fromCache: Boolean) : FriendshipsResult

  /** They couldn't be read: nobody is signed in, or the query failed. The flow then ends. */
  data object Error : FriendshipsResult
}

/** Outcome of [FriendRepository.sendRequest]. */
sealed interface SendFriendRequestResult {
  /** The request is stored as [FriendshipStatus.PENDING], sent by the signed-in student. */
  data object Sent : SendFriendRequestResult

  /**
   * The two students already share a friendship: a request one of them sent, in either direction,
   * or an accepted friendship. Nothing was written; [FriendRepository.observeFriendships] already
   * shows it (with Accept, if the other student sent it).
   */
  data object AlreadyExists : SendFriendRequestResult

  /**
   * The server couldn't be reached or didn't confirm the write. Nothing was queued. If the write
   * did reach the server, trying again returns [AlreadyExists] and
   * [FriendRepository.observeFriendships] shows the request.
   */
  data object NetworkError : SendFriendRequestResult

  /**
   * Any other failure. Nothing was written, unless the failure came after the server stored the
   * request; [FriendRepository.observeFriendships] then shows it. Timeouts and an unreachable
   * server are [NetworkError] instead. This covers nobody signed in, the student's own UID or an
   * invalid one (see [friendshipId]), and a write the Security Rules deny, for example to a UID
   * without a public profile.
   */
  data object UnexpectedError : SendFriendRequestResult
}

/** Outcome of [FriendRepository.accept] and [FriendRepository.remove]. */
sealed interface FriendshipActionResult {
  /** The request was accepted, or the request or friendship deleted. */
  data object Done : FriendshipActionResult

  /**
   * The friendship isn't the one the screen showed anymore: it is gone, or its status or
   * `createdAt` changed (it was accepted, or cancelled and sent again). Nothing was written; the
   * screen says the request changed and shows the current state.
   */
  data object Gone : FriendshipActionResult

  /**
   * The server couldn't be reached or didn't confirm the write. Nothing was queued. If the write
   * did reach the server, trying again returns [Gone] and [FriendRepository.observeFriendships]
   * shows the new state.
   */
  data object NetworkError : FriendshipActionResult

  /**
   * Any other failure. Nothing was written, unless the failure came after the server stored the
   * change; [FriendRepository.observeFriendships] then shows it. Timeouts and an unreachable server
   * are [NetworkError] instead. This covers nobody signed in, a student outside the pair, accepting
   * a request the student sent themselves or one already accepted, and a write the Security Rules
   * deny.
   */
  data object UnexpectedError : FriendshipActionResult
}

/**
 * Stores friend requests and friendships between students (#338), one `friendships/{id}` document
 * per pair, with [friendshipId] as its ID. Sending creates it as [FriendshipStatus.PENDING];
 * accepting changes it to [FriendshipStatus.ACCEPTED]; declining, cancelling and removing delete
 * it, so the pair can start again later.
 *
 * Only the two students read or write their friendship, and associations never take part; the
 * Security Rules enforce it, so a refused access returns an `UnexpectedError` or
 * [FriendshipsResult.Error]. Every write is refused offline with a `NetworkError` before anything
 * is written, rather than queued like a Firestore write made offline.
 */
interface FriendRepository {
  /**
   * Observes the signed-in student's friend requests and friendships, sent and received.
   *
   * The flow emits on every change, including from the offline cache while the device is offline,
   * and when only [FriendshipsResult.Success.fromCache] changes (for example when the device comes
   * back online). A slow collector gets only the latest result. It ends after a
   * [FriendshipsResult.Error], which is always delivered. The user is read when collection starts,
   * and cancelling the collection stops listening.
   */
  fun observeFriendships(): Flow<FriendshipsResult>

  /**
   * Sends a friend request from the signed-in student to the student [otherUid], usually read from
   * their scanned friend code.
   *
   * Nobody signed in, or a UID that can't make a pair with the student's (see [friendshipId]),
   * returns [SendFriendRequestResult.UnexpectedError], and an offline device
   * [SendFriendRequestResult.NetworkError], before anything is read or written. Then, in one step,
   * the pair's document is read and written only if it doesn't exist yet, so a second request
   * between the two, in either direction, returns [SendFriendRequestResult.AlreadyExists] and
   * changes nothing. The server sets `createdAt`.
   */
  suspend fun sendRequest(otherUid: String): SendFriendRequestResult

  /**
   * Accepts the request [shown], as the screen showed it. Only its recipient accepts (never its
   * [Friendship.requestedBy]), and only a [FriendshipStatus.PENDING] request. The server sets
   * `acceptedAt`.
   *
   * Nobody signed in returns [FriendshipActionResult.UnexpectedError], and an offline device
   * [FriendshipActionResult.NetworkError], before anything is read. Then, in one step, the document
   * is read again and written only if it still has the [shown] `status` and `createdAt`; otherwise
   * this returns [FriendshipActionResult.Gone].
   *
   * The checks on the document run in this order: a student whose UID isn't one half of
   * [Friendship.id] gets [FriendshipActionResult.UnexpectedError] (the Security Rules refuse the
   * read), then the [FriendshipActionResult.Gone] check, then, on the document as read again, its
   * sender accepting or a status that isn't [FriendshipStatus.PENDING] gets
   * [FriendshipActionResult.UnexpectedError]. So a stale request is [FriendshipActionResult.Gone]
   * even when accepting it would have been refused.
   */
  suspend fun accept(shown: Friendship): FriendshipActionResult

  /**
   * Deletes the friendship or request [shown], as the screen showed it: declining (by the
   * recipient), cancelling (by the sender) or removing a friend (by either student).
   *
   * Like [accept], nobody signed in and an offline device fail before anything is read, and a
   * student whose UID isn't one half of [Friendship.id] gets
   * [FriendshipActionResult.UnexpectedError]. Then, in one step, the document is read again and
   * deleted only if it still has the [shown] `status` and `createdAt`; otherwise this returns
   * [FriendshipActionResult.Gone]. So a Cancel tapped after the other student accepted can't delete
   * the new friendship, and a stale tap can't delete a request cancelled and sent again under the
   * same ID. The Security Rules can't tell these apart, since a delete carries no data, so this
   * check is the repository's.
   */
  suspend fun remove(shown: Friendship): FriendshipActionResult
}
