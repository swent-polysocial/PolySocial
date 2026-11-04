// Contributors: Claude (Opus 5.5, Anthropic) (wrote this fake for tests, #232; aligned it with
// FirestoreGroupRepository after review).
package com.polysocial.model.group

import com.polysocial.model.event.isReadableEventId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * In-memory [GroupRepository] for tests. It follows the [GroupRepository] contracts and checks in
 * the same order as `FirestoreGroupRepository`: IDs that can't be document IDs, then the signed-in
 * user, then [online], then [writeFailure], then the stored groups.
 *
 * Only the events in [readableEventIds] can be read; any other event is
 * [GroupWriteResult.NotFound], like a missing or private one. A student's group for an event is the
 * one whose members include them, so the fake needs no separate marker. New groups get the IDs
 * `group-1`, `group-2`… and deterministic creation times.
 *
 * The observe flows read [currentUid], [readableEventIds] and [readFailure] when their collection
 * starts, as the real listeners read the user and are checked by the rules.
 *
 * @property currentUid the signed-in student, or null when nobody is signed in.
 */
class FakeGroupRepository(var currentUid: String? = "test-student") : GroupRepository {
  /** The events a student may read, and so start, join and observe groups of. */
  val readableEventIds = mutableSetOf<String>()

  /** False simulates an offline device: every write returns [GroupWriteResult.NetworkError]. */
  var online = true

  /** When set, every write returns this result instead of writing, like a failed transaction. */
  var writeFailure: GroupWriteResult? = null

  /** When set, both observe flows emit Loading, then this failure and end, like a failed listener. */
  var readFailure: ReadFailure? = null

  /** The `fromCache` flag the observe flows report, to simulate reading from the offline cache. */
  var readFromCache = false

  /** The two ways a listener can fail, mapped to each flow's own result. */
  enum class ReadFailure {
    NETWORK,
    UNEXPECTED,
  }

  /** The stored groups, by event ID, oldest first. */
  private val groups = MutableStateFlow<Map<String, List<Group>>>(emptyMap())

  private var created = 0

  /** Stores a group directly, like one other students formed, and returns it. */
  fun seedGroup(
      eventId: String,
      memberUids: List<String>,
      capacity: Int = GROUP_CAPACITY,
  ): Group {
    val group = newGroup(eventId, memberUids, capacity)
    store(eventId, groupsOf(eventId) + group)
    return group
  }

  /** The stored groups of [eventId], oldest first. */
  fun groupsOf(eventId: String): List<Group> = groups.value[eventId].orEmpty()

  override fun observeGroups(eventId: String): Flow<GroupsResult> = flow {
    emit(GroupsResult.Loading)
    val failure = readFailure
    when {
      failure != null ->
          emit(
              if (failure == ReadFailure.NETWORK) GroupsResult.NetworkError
              else GroupsResult.UnexpectedError
          )
      eventId !in readableEventIds -> emit(GroupsResult.UnexpectedError)
      else -> emitAll(groups.map { GroupsResult.Success(it[eventId].orEmpty(), readFromCache) })
    }
  }

  override fun observeMyGroup(eventId: String): Flow<MyGroupResult> = flow {
    emit(MyGroupResult.Loading)
    val failure = readFailure
    val uid = currentUid
    when {
      failure != null ->
          emit(
              if (failure == ReadFailure.NETWORK) MyGroupResult.NetworkError
              else MyGroupResult.UnexpectedError
          )
      uid == null || !isReadableEventId(eventId) -> emit(MyGroupResult.UnexpectedError)
      else ->
          emitAll(
              groups.map { stored ->
                MyGroupResult.Success(
                    stored[eventId].orEmpty().firstOrNull { uid in it.memberUids },
                    readFromCache,
                )
              }
          )
    }
  }

  override suspend fun createGroup(eventId: String): GroupWriteResult =
      write(eventId) { uid ->
        when {
          eventId !in readableEventIds -> GroupWriteResult.NotFound
          myGroup(eventId, uid) != null -> GroupWriteResult.AlreadyInGroup
          else -> {
            val group = newGroup(eventId, listOf(uid), GROUP_CAPACITY)
            store(eventId, groupsOf(eventId) + group)
            GroupWriteResult.Success(group.id)
          }
        }
      }

  override suspend fun joinGroup(eventId: String, groupId: String): GroupWriteResult =
      write(eventId, groupId) { uid ->
        val group = groupsOf(eventId).firstOrNull { it.id == groupId }
        when {
          eventId !in readableEventIds || group == null -> GroupWriteResult.NotFound
          uid in group.memberUids -> GroupWriteResult.Success(groupId)
          myGroup(eventId, uid) != null -> GroupWriteResult.AlreadyInGroup
          group.isFull -> GroupWriteResult.Full
          else -> {
            replace(eventId, group.copy(memberUids = group.memberUids + uid))
            GroupWriteResult.Success(groupId)
          }
        }
      }

  override suspend fun leaveGroup(eventId: String, groupId: String): GroupWriteResult =
      write(eventId, groupId) { uid ->
        val group = groupsOf(eventId).firstOrNull { it.id == groupId }
        when {
          eventId !in readableEventIds || group == null -> GroupWriteResult.NotFound
          uid !in group.memberUids -> GroupWriteResult.Success(groupId)
          group.memberUids == listOf(uid) -> {
            store(eventId, groupsOf(eventId) - group)
            GroupWriteResult.Success(groupId)
          }
          else -> {
            replace(eventId, group.copy(memberUids = group.memberUids - uid))
            GroupWriteResult.Success(groupId)
          }
        }
      }

  /** The checks every write makes before touching the stored groups, in the real order. */
  private inline fun write(
      vararg ids: String,
      change: (uid: String) -> GroupWriteResult,
  ): GroupWriteResult {
    if (!ids.all(::isReadableEventId)) return GroupWriteResult.NotFound
    val uid = currentUid ?: return GroupWriteResult.UnexpectedError
    if (!online) return GroupWriteResult.NetworkError
    writeFailure?.let {
      return it
    }
    return change(uid)
  }

  private fun myGroup(eventId: String, uid: String) =
      groupsOf(eventId).firstOrNull { uid in it.memberUids }

  private fun newGroup(eventId: String, memberUids: List<String>, capacity: Int): Group {
    created++
    return Group(
        id = "group-$created",
        eventId = eventId,
        capacity = capacity,
        memberUids = memberUids,
        createdBy = memberUids.firstOrNull().orEmpty(),
        createdAt = FIRST_CREATED_AT.plusSeconds(created.toLong()),
    )
  }

  private fun replace(eventId: String, group: Group) =
      store(eventId, groupsOf(eventId).map { if (it.id == group.id) group else it })

  private fun store(eventId: String, eventGroups: List<Group>) {
    groups.value += eventId to eventGroups
  }

  private companion object {
    val FIRST_CREATED_AT: Instant = Instant.parse("2030-01-01T12:00:00Z")
  }
}
