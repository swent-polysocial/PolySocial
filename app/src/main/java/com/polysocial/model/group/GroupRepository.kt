// Contributors: Claude (Opus 5.5, Anthropic) (drafted the group repository contract, #232).
package com.polysocial.model.group

import kotlinx.coroutines.flow.Flow

/** Outcome of [GroupRepository.createGroup], [GroupRepository.joinGroup] and `leaveGroup`. */
sealed interface GroupWriteResult {
  /**
   * The write is done, or there was nothing to do: joining a group the student is already in, or
   * leaving one they aren't in, changes nothing and still succeeds.
   *
   * @property groupId the group the student started, joined or left.
   */
  data class Success(val groupId: String) : GroupWriteResult

  /** The group has no place left. Nothing was written. */
  data object Full : GroupWriteResult

  /** The student is already in another group of this event. Nothing was written. */
  data object AlreadyInGroup : GroupWriteResult

  /**
   * There is no event or group the student can read under these IDs: it never existed, it was
   * deleted, it can't be read back, or the event is private and the student isn't allowed in. These
   * look the same on purpose, like `EventResult.NotFound`. Nothing was written.
   */
  data object NotFound : GroupWriteResult

  /** The device is offline. Nothing was written or queued, so the student can try again. */
  data object NetworkError : GroupWriteResult

  /**
   * Any other failure, for example nobody signed in or a write the Security Rules deny. Nothing was
   * written.
   */
  data object UnexpectedError : GroupWriteResult
}

/** What [GroupRepository.observeGroups] emits. */
sealed interface GroupsResult {
  /** Nothing has been read yet. Emitted first, unless a newer result already replaced it. */
  data object Loading : GroupsResult

  /**
   * The event's groups, oldest first, full ones included ([Group.isFull]): matching (#231) leaves
   * them out. A stored group that can't be read (a missing or wrong-typed field) is skipped.
   *
   * @property fromCache true when the groups come from Firestore's offline cache, for example while
   *   the device is offline. They may then be out of date.
   */
  data class Success(val groups: List<Group>, val fromCache: Boolean) : GroupsResult

  /** The groups couldn't be read because the server can't be reached. The flow then ends. */
  data object NetworkError : GroupsResult

  /** Any other failure, for example an event the student can't read. The flow then ends. */
  data object UnexpectedError : GroupsResult
}

/** What [GroupRepository.observeMyGroup] emits. */
sealed interface MyGroupResult {
  /** Nothing has been read yet. Emitted first, unless a newer result already replaced it. */
  data object Loading : MyGroupResult

  /**
   * The signed-in student's group for the event, or null when they aren't in one.
   *
   * @property fromCache true when it comes from Firestore's offline cache, like
   *   [GroupsResult.Success.fromCache].
   */
  data class Success(val group: Group?, val fromCache: Boolean) : MyGroupResult

  /** The group couldn't be read because the server can't be reached. The flow then ends. */
  data object NetworkError : MyGroupResult

  /** Any other failure, for example nobody signed in. The flow then ends. */
  data object UnexpectedError : MyGroupResult
}

/**
 * Stores the groups students form to go to an event together (Find a group, #228), in
 * `events/{eventId}/groups/{groupId}`, and keeps each student in at most one group per event with
 * the marker `events/{eventId}/groupMembers/{uid}`, which holds the student's `groupId`.
 *
 * A student who can read the event may start, join and leave its groups, and changes only their own
 * membership; associations never reach groups. The Security Rules enforce it and check the
 * capacity.
 *
 * Every write checks, in this order and before anything is written: IDs that can't be document IDs
 * ([GroupWriteResult.NotFound]), the signed-in user ([GroupWriteResult.UnexpectedError] without
 * one) and the connection ([GroupWriteResult.NetworkError] offline, since Firestore would queue the
 * write instead of failing). Then it reads and writes in one transaction, so two students can't
 * take the last place, and a student's membership and marker always change together.
 */
interface GroupRepository {
  /**
   * Observes the groups of the event [eventId], for Find a group.
   *
   * The flow emits [GroupsResult.Loading], then on every change, including from the offline cache.
   * A slow collector gets only the latest result. It ends after a [GroupsResult.NetworkError] or
   * [GroupsResult.UnexpectedError], which is always delivered. Cancelling the collection stops
   * listening.
   */
  fun observeGroups(eventId: String): Flow<GroupsResult>

  /**
   * Observes the signed-in student's group for the event [eventId], for the group card on the
   * detail and the Group screen. It emits like [observeGroups]; the user is read when collection
   * starts.
   */
  fun observeMyGroup(eventId: String): Flow<MyGroupResult>

  /**
   * Starts a new group for the event [eventId], with the signed-in student as its only member.
   *
   * Returns [GroupWriteResult.Success] with the new group's ID, [GroupWriteResult.NotFound] for an
   * event the student can't read, and [GroupWriteResult.AlreadyInGroup] when they are already in a
   * group of this event.
   */
  suspend fun createGroup(eventId: String): GroupWriteResult

  /**
   * Adds the signed-in student to the group [groupId] of the event [eventId].
   *
   * The results, in the order they are checked: [GroupWriteResult.NotFound] for a group the student
   * can't read; [GroupWriteResult.Success] without a write when they are already in this group;
   * [GroupWriteResult.AlreadyInGroup] when they are in another group of this event;
   * [GroupWriteResult.Full] when the group has no place left; otherwise [GroupWriteResult.Success]
   * once they are added. When two students race for the last place, one joins and the other gets
   * [GroupWriteResult.Full].
   */
  suspend fun joinGroup(eventId: String, groupId: String): GroupWriteResult

  /**
   * Removes the signed-in student from the group [groupId] of the event [eventId]; the last member
   * to leave deletes the group. Leaving a group the student isn't in changes nothing and succeeds;
   * a group that doesn't exist (anymore), or of an event the student can no longer read, is
   * [GroupWriteResult.NotFound].
   */
  suspend fun leaveGroup(eventId: String, groupId: String): GroupWriteResult
}
