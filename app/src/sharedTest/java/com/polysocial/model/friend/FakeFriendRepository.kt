// Contributors: Claude Opus 5.5 (wrote this fake for tests, #357; the shared pair check, second
// PR); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.model.friend

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * In-memory [FriendRepository] for tests. It follows the [FriendRepository] contracts and checks in
 * the order the Firestore repository must: the signed-in user, then (for [sendRequest]) the pair's
 * [friendshipId], then [sendFailure], [acceptFailure] or [removeFailure] (like an offline device),
 * then the stored friendship, as the transaction and the Security Rules would.
 *
 * Each call checks and writes in one atomic step, like the repository's transactions, so concurrent
 * calls see each other's writes.
 *
 * Unlike the Security Rules, it doesn't check that the other student has a public profile, so any
 * valid UID can be sent a request.
 *
 * @property currentUid the signed-in student, or null when nobody is signed in.
 * @property now the server time the next write stores as `createdAt` or `acceptedAt`. Each such
 *   write then moves it one millisecond on, so a request sent again after a cancel gets a new
 *   `createdAt`, as it would on the server.
 */
class FakeFriendRepository(
    var currentUid: String? = "test-student",
    var now: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : FriendRepository {
  /** The stored friendships, by ID. */
  private val friendships = MutableStateFlow<Map<String, Friendship>>(emptyMap())

  /** Held for each check-and-write, so a call is atomic like a transaction. */
  private val lock = Any()

  /** When set, [sendRequest] returns this result instead of sending, like a failed write. */
  var sendFailure: SendFriendRequestResult? = null

  /** When set, [accept] returns this result instead of accepting, like a failed write. */
  var acceptFailure: FriendshipActionResult? = null

  /** When set, [remove] returns this result instead of deleting, like a failed write. */
  var removeFailure: FriendshipActionResult? = null

  /** When true, [observeFriendships] emits [FriendshipsResult.Error], like a failed query. */
  var readFailure = false

  private val fromCache = MutableStateFlow(false)

  /**
   * The `fromCache` flag [observeFriendships] reports, to simulate being offline. Changing it
   * emits a new result, like a listener that includes metadata changes.
   */
  var readFromCache: Boolean
    get() = fromCache.value
    set(value) {
      fromCache.value = value
    }

  /** Stores [friendship] directly under its ID, like a request another student sent. */
  fun seedFriendship(friendship: Friendship) {
    synchronized(lock) { friendships.value += friendship.id to friendship }
  }

  /** The stored friendship with this [id], or null when there is none. */
  fun friendship(id: String): Friendship? = friendships.value[id]

  /** Like the contract, the user and [readFailure] are read when collection starts. */
  override fun observeFriendships(): Flow<FriendshipsResult> = flow {
    val uid = currentUid
    if (uid == null || readFailure) {
      emit(FriendshipsResult.Error)
      return@flow
    }
    emitAll(
        combine(friendships, fromCache) { stored, cached ->
          FriendshipsResult.Success(stored.values.filter { uid in it.uids }, cached)
        })
  }

  override suspend fun sendRequest(otherUid: String): SendFriendRequestResult =
      synchronized(lock) { send(otherUid) }

  override suspend fun accept(shown: Friendship): FriendshipActionResult =
      synchronized(lock) { acceptNow(shown) }

  override suspend fun remove(shown: Friendship): FriendshipActionResult =
      synchronized(lock) { removeNow(shown) }

  /** The server time for this write; the next one gets a millisecond later. */
  private fun serverTime(): Instant = now.also { now = it.plusMillis(1) }

  private fun send(otherUid: String): SendFriendRequestResult {
    val uid = currentUid ?: return SendFriendRequestResult.UnexpectedError
    val id = friendshipId(uid, otherUid) ?: return SendFriendRequestResult.UnexpectedError
    sendFailure?.let {
      return it
    }
    if (id in friendships.value) return SendFriendRequestResult.AlreadyExists
    seedFriendship(
        Friendship(
            id = id,
            uids = listOf(uid, otherUid).sorted(),
            requestedBy = uid,
            status = FriendshipStatus.PENDING,
            createdAt = serverTime(),
            acceptedAt = null,
        ))
    return SendFriendRequestResult.Sent
  }

  private fun acceptNow(shown: Friendship): FriendshipActionResult {
    val uid = currentUid ?: return FriendshipActionResult.UnexpectedError
    acceptFailure?.let {
      return it
    }
    refusal(shown, uid)?.let {
      return it
    }
    val stored = friendships.value.getValue(shown.id)
    if (stored.requestedBy == uid || stored.status != FriendshipStatus.PENDING) {
      return FriendshipActionResult.UnexpectedError
    }
    seedFriendship(stored.copy(status = FriendshipStatus.ACCEPTED, acceptedAt = serverTime()))
    return FriendshipActionResult.Done
  }

  private fun removeNow(shown: Friendship): FriendshipActionResult {
    val uid = currentUid ?: return FriendshipActionResult.UnexpectedError
    removeFailure?.let {
      return it
    }
    refusal(shown, uid)?.let {
      return it
    }
    friendships.value -= shown.id
    return FriendshipActionResult.Done
  }

  /**
   * What reading [shown]'s document again, as [uid], refuses, or null when it is still as shown: a
   * student whose UID isn't one half of its ID can't read it, whether or not it exists (the
   * Security Rules refuse), and a missing document, or one whose status or `createdAt` changed
   * since it was shown, is [FriendshipActionResult.Gone].
   */
  private fun refusal(shown: Friendship, uid: String): FriendshipActionResult? {
    if (!isFriendshipIdOf(shown.id, uid)) return FriendshipActionResult.UnexpectedError
    val stored = friendships.value[shown.id]
    if (stored == null || stored.status != shown.status || stored.createdAt != shown.createdAt) {
      return FriendshipActionResult.Gone
    }
    return null
  }
}
