// Contributors: Claude (Opus 5.5, Anthropic) drafted Find a group from #227's Figma frames, #233,
// and ranked the groups with the student's friends, #497.
package com.polysocial.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.FriendshipsResult
import com.polysocial.model.friend.otherUid
import com.polysocial.model.group.Group
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.GroupWriteResult
import com.polysocial.model.group.GroupsResult
import com.polysocial.model.group.MatchGroup
import com.polysocial.model.group.MatchProfile
import com.polysocial.model.group.rankGroups
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfilesResult
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.event.detail.EventDetails
import com.polysocial.ui.event.detail.eventDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A member of a suggested group: their public profile, or null when it couldn't be read. */
data class GroupMember(val uid: String, val profile: PublicProfile?)

/**
 * A group Find a group suggests, with why it fits, from #231's ranking: [sharedInterests] is how
 * many of the student's interests its members share, and [sameSection] and [sameYear] are the
 * student's own codes when at least one member shares them, null otherwise. [friends] are the
 * members who are the student's friends (#497), in member order, each with a null profile when it
 * couldn't be read; empty when none is.
 */
data class GroupSuggestion(
    val groupId: String,
    val capacity: Int,
    val members: List<GroupMember>,
    val sharedInterests: Int,
    val sameSection: String?,
    val sameYear: String?,
    val friends: List<GroupMember> = emptyList(),
)

/** What Find a group shows. */
sealed interface FindGroupUiState {
  /** Figma "Find a group · searching": the groups haven't been read yet. */
  data object Loading : FindGroupUiState

  /** Figma "Find a group · couldn't load": the groups couldn't be read; Retry reads them again. */
  data object CouldNotLoad : FindGroupUiState

  /**
   * The event's open groups, best match first ([suggestions], empty for Figma "no group yet").
   * [event] is null until the event is read. While [isOffline], Join and Start a new group are
   * disabled, since a write is refused offline. [pending] is the write that runs, if any.
   */
  data class Loaded(
      val event: EventDetails?,
      val suggestions: List<GroupSuggestion>,
      val isOffline: Boolean,
      val pending: PendingGroupWrite? = null,
  ) : FindGroupUiState
}

/** The write Find a group waits for: joining the group [Join.groupId], or starting one. */
sealed interface PendingGroupWrite {
  data class Join(val groupId: String) : PendingGroupWrite

  data object Start : PendingGroupWrite
}

/** Why the last Join or Start a new group didn't go through, shown once in a Snackbar. */
enum class FindGroupMessage {
  /** Figma "Find a group · group full": someone took the last place first. */
  GROUP_FULL,
  ALREADY_IN_GROUP,
  /** The group no longer exists, for example its last member left. */
  GROUP_GONE,
  /** The event was deleted, or can't be read anymore. */
  EVENT_GONE,
  OFFLINE,
  UNEXPECTED,
}

/** How Find a group ended: the student joined a group or started one. */
enum class FindGroupDone {
  JOINED,
  STARTED,
}

/**
 * Find a group (#233) for the event in the navigation argument [EventDetailViewModel.EVENT_ID_ARG],
 * from #227's frames: the event's open groups ranked for the signed-in student by [rankGroups]
 * (#231), with why each fits, Join, and Start a new group.
 *
 * The groups are observed live. Each time they change, the members' and the student's public
 * profiles are read once ([PublicProfileRepository.getPublicProfiles]); a profile that can't be
 * read leaves that member without a name and adds nothing to the score but a friend's points, and
 * if none can be read, the groups are still shown, ranked on friends only. When the profiles
 * couldn't be read, the next emission reads them again even if the groups didn't change, so coming
 * back online names the members. A failed read of the groups shows [FindGroupUiState.CouldNotLoad],
 * and [retry] reads them again.
 *
 * The student's friendships are observed live too ([FriendRepository.observeFriendships], #497),
 * and the groups are ranked again, without reading the profiles again, each time their accepted
 * friends change. The groups never wait for them: until they answer, or while they can't be read,
 * the groups are ranked without friends, and a friendships read that failed is tried again once the
 * device comes back online (or when the screen opens again).
 *
 * [join] and [start] run one write at a time; taps while one runs are ignored. A success reports
 * [done] (the screen then leaves), and a refusal reports [message]. Nothing reports a success that
 * didn't happen: [done] is only set from [GroupWriteResult.Success].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FindGroupViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val events: EventRepository,
    private val groups: GroupRepository,
    private val profiles: PublicProfileRepository,
    private val friends: FriendRepository,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : ViewModel() {
  private val eventId = savedStateHandle.get<String>(EventDetailViewModel.EVENT_ID_ARG).orEmpty()

  /** Bumped by [retry], so the groups are read again. */
  private val reads = MutableStateFlow(0)

  private val pending = MutableStateFlow<PendingGroupWrite?>(null)

  private val _message = MutableStateFlow<FindGroupMessage?>(null)
  val message: StateFlow<FindGroupMessage?> = _message.asStateFlow()

  private val _done = MutableStateFlow<FindGroupDone?>(null)
  val done: StateFlow<FindGroupDone?> = _done.asStateFlow()

  val uiState: StateFlow<FindGroupUiState> =
      combine(reads.flatMapLatest { observe() }, pending) { state, write ->
            if (state is FindGroupUiState.Loaded) state.copy(pending = write) else state
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
              FindGroupUiState.Loading,
          )

  /** Reads the groups again after [FindGroupUiState.CouldNotLoad]. */
  fun retry() {
    reads.value++
  }

  /** Joins the group [groupId]; ignored while a write runs or offline. */
  fun join(groupId: String) =
      write(PendingGroupWrite.Join(groupId)) { groups.joinGroup(eventId, groupId) }

  /** Starts a new group for the event; ignored while a write runs or offline. */
  fun start() = write(PendingGroupWrite.Start) { groups.createGroup(eventId) }

  /** Clears [shown], unless a newer message replaced it meanwhile. */
  fun onMessageShown(shown: FindGroupMessage) {
    _message.compareAndSet(shown, null)
  }

  private fun write(what: PendingGroupWrite, call: suspend () -> GroupWriteResult) {
    val state = uiState.value
    if (pending.value != null || state !is FindGroupUiState.Loaded || state.isOffline) return
    pending.value = what
    viewModelScope.launch {
      val result =
          try {
            call()
          } catch (cancelled: CancellationException) {
            pending.value = null
            throw cancelled
          } catch (_: Exception) {
            GroupWriteResult.UnexpectedError
          }
      pending.value = null
      when (result) {
        is GroupWriteResult.Success ->
            _done.value =
                if (what == PendingGroupWrite.Start) FindGroupDone.STARTED else FindGroupDone.JOINED
        GroupWriteResult.Full -> _message.value = FindGroupMessage.GROUP_FULL
        GroupWriteResult.AlreadyInGroup -> _message.value = FindGroupMessage.ALREADY_IN_GROUP
        GroupWriteResult.NotFound ->
            _message.value =
                if (what == PendingGroupWrite.Start) FindGroupMessage.EVENT_GONE
                else FindGroupMessage.GROUP_GONE
        GroupWriteResult.NetworkError -> _message.value = FindGroupMessage.OFFLINE
        GroupWriteResult.UnexpectedError -> _message.value = FindGroupMessage.UNEXPECTED
      }
    }
  }

  private fun observe(): Flow<FindGroupUiState> {
    val event =
        events
            .getEvent(eventId)
            .map {
              (it as? EventResult.Found)?.let { found -> eventDetails(found.event, clock.zone) }
            }
            .onStart { emit(null) }
    // Whether the last read could read the profiles: if not, the same groups are read again.
    var profilesRead = true
    val groupsRead =
        groups
            .observeGroups(eventId)
            // Only the groups matter here: when only fromCache changes, the profiles aren't read
            // again, unless the last read failed (for example offline, then back online).
            .map { if (it is GroupsResult.Success) it.copy(fromCache = false) else it }
            .distinctUntilChanged { old, new -> profilesRead && old == new }
            .mapLatest { result ->
              when (result) {
                GroupsResult.Loading -> GroupsRead.Loading
                is GroupsResult.Success -> {
                  val found = readProfiles(result.groups)
                  profilesRead = found != null
                  GroupsRead.Done(result.groups, found.orEmpty())
                }
                GroupsResult.NetworkError,
                GroupsResult.UnexpectedError -> GroupsRead.Failed
              }
            }
    return combine(event, groupsRead, observeFriendUids()) { details, read, friendUids ->
      when (read) {
        GroupsRead.Loading -> FindGroupUiState.Loading
        GroupsRead.Failed -> FindGroupUiState.CouldNotLoad
        is GroupsRead.Done ->
            FindGroupUiState.Loaded(
                details,
                rank(read.groups, read.profiles, friendUids),
                isOffline = !network.isOnline(),
            )
      }
    }
  }

  /**
   * The UIDs of the signed-in student's accepted friends, live: none until the friendships answer,
   * so the groups never wait for them, and none while they can't be read. A pending request isn't a
   * friend. The listener ends after a failure; it then starts again once the device comes back
   * online.
   */
  private fun observeFriendUids(): Flow<Set<String>> = flow {
    emit(emptySet())
    val me = auth.currentUser()?.uid ?: return@flow
    do {
      friends.observeFriendships().collect { emit(it.friendUidsOf(me)) }
    } while (cameBackOnline())
  }
      .distinctUntilChanged()

  /** Waits until the device goes offline, then online again; false if that can't be observed. */
  private suspend fun cameBackOnline(): Boolean =
      network.observeOnline().dropWhile { it }.firstOrNull { it } != null

  private fun FriendshipsResult.friendUidsOf(me: String): Set<String> =
      when (this) {
        is FriendshipsResult.Success ->
            friendships
                .filter { it.status == FriendshipStatus.ACCEPTED }
                .mapNotNullTo(mutableSetOf()) { it.otherUid(me) }
        FriendshipsResult.Error -> emptySet()
      }

  /**
   * The public profiles of [stored]'s members and the student, or null if they couldn't be read.
   */
  private suspend fun readProfiles(stored: List<Group>): Map<String, PublicProfile>? {
    val uids = stored.flatMap { it.memberUids } + auth.currentUser()?.uid.orEmpty()
    return when (val result = profiles.getPublicProfiles(uids.filter { it.isNotEmpty() })) {
      is PublicProfilesResult.Found -> result.profiles
      PublicProfilesResult.NetworkError,
      PublicProfilesResult.UnexpectedError -> null
    }
  }

  /** Ranks [stored] for the signed-in student, with the profiles [read] and their [friendUids]. */
  private fun rank(
      stored: List<Group>,
      read: Map<String, PublicProfile>,
      friendUids: Set<String>,
  ): List<GroupSuggestion> {
    val me = auth.currentUser()?.uid.orEmpty()
    val student = read[me]?.toMatch() ?: MatchProfile(me, section = "", year = "", emptySet())
    val byId = stored.associateBy { it.id }
    return rankGroups(
            student,
            stored.map { group ->
              MatchGroup(
                  group.id,
                  group.capacity,
                  group.memberUids,
                  group.memberUids.mapNotNull { read[it]?.toMatch() },
              )
            },
            friendUids,
        )
        .map { ranked ->
          val group = byId.getValue(ranked.group.id)
          GroupSuggestion(
              groupId = group.id,
              capacity = group.capacity,
              members = group.memberUids.map { GroupMember(it, read[it]) },
              sharedInterests = ranked.sharedInterests.size,
              sameSection = student.section.takeIf { ranked.membersInSameSection > 0 },
              sameYear = student.year.takeIf { ranked.membersInSameYear > 0 },
              friends = ranked.friends.map { GroupMember(it, read[it]) },
          )
        }
  }

  private fun PublicProfile.toMatch() = MatchProfile(uid, section, year, interests)

  /** Where reading the groups and their members' profiles is. */
  private sealed interface GroupsRead {
    data object Loading : GroupsRead

    data object Failed : GroupsRead

    /** The [groups] as stored, with the [profiles] that could be read, by UID. */
    data class Done(val groups: List<Group>, val profiles: Map<String, PublicProfile>) : GroupsRead
  }

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
