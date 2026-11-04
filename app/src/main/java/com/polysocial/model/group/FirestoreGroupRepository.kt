// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Firestore group repository, #232, and
// revised it after review).
package com.polysocial.model.group

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Transaction
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.isReadableEventId
import com.polysocial.model.network.NetworkMonitor
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/**
 * [GroupRepository] backed by Firestore: groups in `events/{eventId}/groups/{groupId}` and each
 * member's marker in `events/{eventId}/groupMembers/{uid}` (see [GROUP_MEMBERS_COLLECTION]).
 *
 * Every write runs in one transaction that reads the group (or the event, for a new group) and the
 * student's marker, then changes `memberUids` and the marker together, as the Security Rules
 * require (`getAfter`). Android transactions can't run queries, so the marker is how a transaction
 * sees that the student is already in a group. When two students race for the last place, the
 * second one's write, based on the group it read, is denied; the transaction then runs again with a
 * fresh read (see [write]), and that student gets [GroupWriteResult.Full].
 *
 * Like `FirestoreEventRepository`, writes check [NetworkMonitor] first: Firestore only runs
 * transactions against the server, so a write fails offline instead of being queued.
 */
class FirestoreGroupRepository(
    private val database: () -> FirebaseFirestore,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
) : GroupRepository {
  constructor(
      db: FirebaseFirestore,
      auth: AuthRepository,
      network: NetworkMonitor,
  ) : this({ db }, auth, network)

  private val db: FirebaseFirestore
    get() = database()

  private fun event(eventId: String): DocumentReference =
      db.collection(EVENTS_COLLECTION).document(eventId)

  private fun groups(eventId: String): CollectionReference =
      event(eventId).collection(GROUPS_COLLECTION)

  private fun marker(eventId: String, uid: String): DocumentReference =
      event(eventId).collection(GROUP_MEMBERS_COLLECTION).document(uid)

  /**
   * Listens to all of `events/[eventId]/groups`, which the rules allow when the event is readable.
   */
  override fun observeGroups(eventId: String): Flow<GroupsResult> {
    if (!isReadableEventId(eventId)) {
      return flowOf(GroupsResult.Loading, GroupsResult.UnexpectedError)
    }
    return listen(
        loading = GroupsResult.Loading,
        failed = { offline ->
          if (offline) GroupsResult.NetworkError else GroupsResult.UnexpectedError
        },
        query = { groups(eventId) },
    ) { snapshot ->
      GroupsResult.Success(
          snapshot.documents
              .mapNotNull { groupFromFirestore(it.id, eventId, it.data) }
              .sortedWith(OLDEST_FIRST),
          snapshot.metadata.isFromCache,
      )
    }
  }

  /**
   * Listens to the event's groups whose `memberUids` contain the signed-in student. That needs no
   * marker read, and stays correct if a marker and its group ever disagree.
   */
  override fun observeMyGroup(eventId: String): Flow<MyGroupResult> {
    if (!isReadableEventId(eventId)) {
      return flowOf(MyGroupResult.Loading, MyGroupResult.UnexpectedError)
    }
    return listen(
        loading = MyGroupResult.Loading,
        failed = { offline ->
          if (offline) MyGroupResult.NetworkError else MyGroupResult.UnexpectedError
        },
        query = {
          auth.currentUser()?.let { groups(eventId).whereArrayContains(MEMBER_UIDS, it.uid) }
        },
    ) { snapshot ->
      MyGroupResult.Success(
          snapshot.documents
              .mapNotNull { groupFromFirestore(it.id, eventId, it.data) }
              .minWithOrNull(OLDEST_FIRST),
          snapshot.metadata.isFromCache,
      )
    }
  }

  /**
   * Reads the event (a missing one is [GroupWriteResult.NotFound]) and the student's marker (one is
   * [GroupWriteResult.AlreadyInGroup]), then writes the new group and the marker.
   */
  override suspend fun createGroup(eventId: String): GroupWriteResult =
      write(eventId) { transaction, uid, firstReadDone ->
        val exists = transaction.get(event(eventId)).exists()
        firstReadDone()
        when {
          !exists -> GroupWriteResult.NotFound
          transaction.get(marker(eventId, uid)).exists() -> GroupWriteResult.AlreadyInGroup
          else -> {
            val group = groups(eventId).document()
            transaction.set(group, newGroupFirestoreMap(uid))
            transaction.set(marker(eventId, uid), mapOf(MARKER_GROUP_ID to group.id))
            GroupWriteResult.Success(group.id)
          }
        }
      }

  /**
   * Reads the group and the student's marker, then appends the student to `memberUids` and creates
   * the marker. A group the student is already in is a success without a write, and any marker
   * otherwise means another group of this event.
   */
  override suspend fun joinGroup(eventId: String, groupId: String): GroupWriteResult =
      write(eventId, groupId) { transaction, uid, firstReadDone ->
        val reference = groups(eventId).document(groupId)
        val stored = transaction.get(reference)
        firstReadDone()
        val group = groupFromFirestore(groupId, eventId, stored.data)
        when {
          group == null -> GroupWriteResult.NotFound
          uid in group.memberUids -> GroupWriteResult.Success(groupId)
          transaction.get(marker(eventId, uid)).exists() -> GroupWriteResult.AlreadyInGroup
          group.isFull -> GroupWriteResult.Full
          else -> {
            transaction.update(reference, mapOf(MEMBER_UIDS to group.memberUids + uid))
            transaction.set(marker(eventId, uid), mapOf(MARKER_GROUP_ID to groupId))
            GroupWriteResult.Success(groupId)
          }
        }
      }

  /**
   * Reads the group and the student's marker, then removes the student from `memberUids`, or
   * deletes the group when they were its last member, and deletes their marker. The marker is
   * deleted only if it exists: the rules deny deleting a missing one. The rules let a member read
   * their group even once they can no longer read the event, so they can still leave it; anyone
   * else's read is then denied, which is [GroupWriteResult.NotFound].
   */
  override suspend fun leaveGroup(eventId: String, groupId: String): GroupWriteResult =
      write(eventId, groupId) { transaction, uid, firstReadDone ->
        val reference = groups(eventId).document(groupId)
        val stored = transaction.get(reference)
        firstReadDone()
        val group = groupFromFirestore(groupId, eventId, stored.data)
        when {
          group == null -> GroupWriteResult.NotFound
          uid !in group.memberUids -> GroupWriteResult.Success(groupId)
          else -> {
            val marker = marker(eventId, uid)
            val hasMarker = transaction.get(marker).exists()
            val remaining = group.memberUids.filter { it != uid }
            if (remaining.isEmpty()) transaction.delete(reference)
            else transaction.update(reference, mapOf(MEMBER_UIDS to remaining))
            if (hasMarker) transaction.delete(marker)
            GroupWriteResult.Success(groupId)
          }
        }
      }

  /**
   * Runs the checks every write makes before touching Firestore (the IDs, the user, the
   * connection), then [change] in a transaction, and turns failures into results.
   *
   * [change] calls its `firstReadDone` once its first read has returned: a read the rules deny
   * before that is [GroupWriteResult.NotFound] (the event or group is missing or unreadable).
   *
   * A write the rules deny after the reads usually means another student changed the group in
   * between: the rules check the write against the group as it is now, before Firestore's own
   * conflict check, and Firestore doesn't retry a denied transaction. So the whole transaction runs
   * again, reading the group afresh, up to [MAX_ATTEMPTS] times; the student who lost the last
   * place then gets [GroupWriteResult.Full]. A write still denied after that is
   * [GroupWriteResult.UnexpectedError].
   *
   * Any denial after the reads is rerun, not only a lost race. A rule that denies the same write
   * every time (for example "I'm going", #244, or a past-event check) would cost each tap
   * [MAX_ATTEMPTS] transactions and end in [GroupWriteResult.UnexpectedError], so such a rule needs
   * its own result, checked before writing.
   */
  private suspend fun write(
      vararg ids: String,
      change: (Transaction, uid: String, firstReadDone: () -> Unit) -> GroupWriteResult,
  ): GroupWriteResult {
    if (!ids.all(::isReadableEventId)) return GroupWriteResult.NotFound
    val user = auth.currentUser() ?: return GroupWriteResult.UnexpectedError
    if (!network.isOnline()) return GroupWriteResult.NetworkError
    var attempt = 1
    while (true) {
      // Set on Firestore's transaction thread, read here once the transaction has ended.
      val firstRead = AtomicBoolean(false)
      try {
        return db.runTransaction { transaction ->
              change(transaction, user.uid) { firstRead.set(true) }
            }
            .await()
      } catch (e: FirebaseFirestoreException) {
        val deniedAfterReading =
            e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED && firstRead.get()
        if (!deniedAfterReading || attempt == MAX_ATTEMPTS) return e.toWriteResult(firstRead.get())
      } catch (_: CancellationException) {
        // A task cancelled while the caller still runs is a failure; a cancelled caller rethrows.
        currentCoroutineContext().ensureActive()
        return GroupWriteResult.UnexpectedError
      } catch (_: Exception) {
        return GroupWriteResult.UnexpectedError
      }
      attempt++
    }
  }

  private fun FirebaseFirestoreException.toWriteResult(firstReadDone: Boolean) =
      when {
        isOffline() -> GroupWriteResult.NetworkError
        code == FirebaseFirestoreException.Code.PERMISSION_DENIED && !firstReadDone ->
            GroupWriteResult.NotFound
        code == FirebaseFirestoreException.Code.NOT_FOUND -> GroupWriteResult.NotFound
        else -> GroupWriteResult.UnexpectedError
      }

  /**
   * A snapshot listener on [query] as a flow: [loading] first, then [read] of every snapshot,
   * metadata changes included so it also emits when only `fromCache` changes. A null [query]
   * (nobody signed in), a listener error, or a snapshot [read] can't handle ends the flow with
   * [failed], told whether the server was unreachable. The flow is conflated, like the other
   * repositories' listeners.
   */
  private fun <T> listen(
      loading: T,
      failed: (offline: Boolean) -> T,
      query: () -> Query?,
      read: (QuerySnapshot) -> T,
  ): Flow<T> = callbackFlow {
    trySend(loading)
    fun fail(error: Exception?) {
      trySend(failed((error as? FirebaseFirestoreException)?.isOffline() == true))
      close()
    }
    // Nothing in this try suspends, so it can't catch the collector's cancellation, and
    // awaitClose still ends a cancelled collection.
    val registration =
        try {
          val listened = query()
          if (listened == null) fail(null)
          listened?.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null || snapshot == null) fail(error)
            else
                try {
                  trySend(read(snapshot))
                } catch (e: Exception) {
                  // Thrown here, it would crash the app on Firestore's callback thread.
                  fail(e)
                }
          }
        } catch (e: Exception) {
          fail(e)
          null
        }
    awaitClose { registration?.remove() }
  }
      .buffer(Channel.CONFLATED)

  private fun FirebaseFirestoreException.isOffline() =
      code == FirebaseFirestoreException.Code.UNAVAILABLE ||
          code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED

  private companion object {
    /** How many times [write] runs a transaction whose write the rules denied after its reads. */
    const val MAX_ATTEMPTS = 3

    /** Oldest first; a group the server hasn't timestamped yet is the newest. Then by ID. */
    val OLDEST_FIRST: Comparator<Group> =
        compareBy<Group, Instant?>(nullsLast()) { it.createdAt }.thenBy { it.id }
  }
}
