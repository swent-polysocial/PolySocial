// Contributors: Claude Opus 5.5 (drafted the Firestore friend repository, #357); Konstantinos
// Perdikeas (directed, reviewed and owns it).
package com.polysocial.model.friend

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.reportFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.tasks.await

/**
 * [FriendRepository] backed by Firestore: one `friendships/{pairId}` document per pair of students,
 * written with [newFriendRequestFirestoreMap] and [acceptanceFirestoreMap] and read with
 * [friendshipFromFirestore].
 *
 * Like the other repositories, writes check [NetworkMonitor] first and run in a transaction, which
 * Firestore only runs against the server, so a write fails offline instead of being queued. Unlike
 * registrations, the transaction keeps Firestore's retries: when two students send each other a
 * request at the same moment, the second commit fails on the first one's document, and the retry
 * reads it and returns [SendFriendRequestResult.AlreadyExists].
 */
class FirestoreFriendRepository(
    private val database: () -> FirebaseFirestore,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
) : FriendRepository {
  constructor(
      db: FirebaseFirestore,
      auth: AuthRepository,
      network: NetworkMonitor,
  ) : this({ db }, auth, network)

  private val db: FirebaseFirestore
    get() = database()

  private fun friendships() = db.collection(FRIENDSHIPS_COLLECTION)

  /**
   * Listens to `friendships where uids array-contains <signed-in UID>`, the only query the read
   * rule allows. A student has few friendships, so it needs no composite index. Metadata changes
   * are included, so the flow also emits when only `fromCache` changes. A document that can't be
   * read is skipped.
   *
   * Like the other repositories' listeners, the flow is conflated, and any failure (nobody signed
   * in, a listener error, an unexpected exception) ends it with [FriendshipsResult.Error] instead
   * of throwing.
   */
  override fun observeFriendships(): Flow<FriendshipsResult> = callbackFlow {
    fun fail() {
      trySend(FriendshipsResult.Error)
      close()
    }
    // Nothing in this try suspends, so it can't catch the collector's cancellation, and
    // awaitClose still ends a cancelled collection.
    val registration =
        try {
          val user = auth.currentUser()
          if (user == null) fail()
          user?.let {
            friendships().whereArrayContains(FRIENDSHIP_UIDS, it.uid).addSnapshotListener(
                MetadataChanges.INCLUDE
            ) { snapshot, failure ->
              if (failure != null || snapshot == null) fail()
              else
                  try {
                    val read =
                        snapshot.documents.mapNotNull { document ->
                          friendshipFromFirestore(document.id, document.data)
                        }
                    trySend(FriendshipsResult.Success(read, snapshot.metadata.isFromCache))
                  } catch (e: Exception) {
                    // Thrown here, it would crash the app on Firestore's callback thread.
                    fail()
                  }
            }
          }
        } catch (e: Exception) {
          fail()
          null
        }
    awaitClose { registration?.remove() }
  }
      .buffer(Channel.CONFLATED)
      .catch { failure ->
        if (failure is CancellationException) throw failure
        emit(FriendshipsResult.Error)
      }

  /** Reads the pair's document in a transaction and writes the request only if it is missing. */
  override suspend fun sendRequest(otherUid: String): SendFriendRequestResult {
    val user = auth.currentUser() ?: return SendFriendRequestResult.UnexpectedError
    val id = friendshipId(user.uid, otherUid) ?: return SendFriendRequestResult.UnexpectedError
    if (!network.isOnline()) return SendFriendRequestResult.NetworkError
    return reportFailures(
        offline = SendFriendRequestResult.NetworkError,
        failed = SendFriendRequestResult.UnexpectedError,
    ) {
      val pair = friendships().document(id)
      db.runTransaction(RETRIED) { transaction ->
            if (transaction.get(pair).exists()) {
              SendFriendRequestResult.AlreadyExists
            } else {
              transaction.set(pair, newFriendRequestFirestoreMap(user.uid, otherUid))
              SendFriendRequestResult.Sent
            }
          }
          .await()
    }
  }

  override suspend fun accept(shown: Friendship): FriendshipActionResult =
      actOn(shown) { transaction, pair, stored, uid ->
        if (stored.requestedBy == uid || stored.status != FriendshipStatus.PENDING) {
          FriendshipActionResult.UnexpectedError
        } else {
          transaction.update(pair, acceptanceFirestoreMap())
          FriendshipActionResult.Done
        }
      }

  override suspend fun remove(shown: Friendship): FriendshipActionResult =
      actOn(shown) { transaction, pair, _, _ ->
        transaction.delete(pair)
        FriendshipActionResult.Done
      }

  /**
   * Runs [change] on [shown]'s document, in a transaction that reads it again first: in the
   * contract's order, nobody signed in, offline, then a student whose UID isn't one half of
   * [Friendship.id] (checked here, as the rules would refuse the read), then a missing document or
   * one whose status or `createdAt` changed ([FriendshipActionResult.Gone]), then [change] on the
   * document as stored. A stored document that can't be read is an `UnexpectedError`.
   */
  private suspend fun actOn(
      shown: Friendship,
      change: (Transaction, DocumentReference, Friendship, String) -> FriendshipActionResult,
  ): FriendshipActionResult {
    val user = auth.currentUser() ?: return FriendshipActionResult.UnexpectedError
    if (!network.isOnline()) return FriendshipActionResult.NetworkError
    if (!isFriendshipIdOf(shown.id, user.uid)) return FriendshipActionResult.UnexpectedError
    return reportFailures(
        offline = FriendshipActionResult.NetworkError,
        failed = FriendshipActionResult.UnexpectedError,
    ) {
      val pair = friendships().document(shown.id)
      db.runTransaction(RETRIED) { transaction ->
            val snapshot = transaction.get(pair)
            val stored = friendshipFromFirestore(shown.id, snapshot.data)
            when {
              !snapshot.exists() -> FriendshipActionResult.Gone
              stored == null -> FriendshipActionResult.UnexpectedError
              stored.status != shown.status || stored.createdAt != shown.createdAt ->
                  FriendshipActionResult.Gone
              else -> change(transaction, pair, stored, user.uid)
            }
          }
          .await()
    }
  }

  private companion object {
    /**
     * Firestore's default number of attempts, kept explicit: a retry after a concurrent change
     * reads the document again, so it returns `AlreadyExists` or `Gone` instead of failing.
     */
    val RETRIED: TransactionOptions = TransactionOptions.Builder().setMaxAttempts(5).build()
  }
}
