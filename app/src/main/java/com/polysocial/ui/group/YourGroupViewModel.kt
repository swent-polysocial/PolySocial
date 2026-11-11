// Contributors: OpenAI Codex (live roster, connectivity recovery and confirmed leave, #234).
// Contributors: OpenAI Codex (shared group member and event reads with chat, #238).
package com.polysocial.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.GroupWriteResult
import com.polysocial.model.group.MyGroupResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.event.detail.EventDetails
import com.polysocial.ui.event.detail.eventDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class YourGroupScreenState(
    val event: EventDetails? = null,
    val roster: GroupRoster = GroupRoster.Loading,
    val leave: LeaveGroupRequest? = null,
    val offline: Boolean = false,
)

internal sealed interface GroupRoster {
  data object Loading : GroupRoster

  enum class Unavailable : GroupRoster {
    OFFLINE,
    MISSING,
    FAILED,
  }

  data class Members(
      val groupId: String,
      val capacity: Int,
      val people: List<GroupMember>,
      val myUid: String?,
  ) : GroupRoster
}

internal data class LeaveGroupRequest(
    val groupId: String,
    val eventTitle: String?,
    val stage: Stage = Stage.CONFIRM,
) {
  enum class Stage {
    CONFIRM,
    LEAVING,
    OFFLINE,
    FAILED,
    LEFT,
  }
}

/**
 * Observes the student's group for the event. Profile reads are independent, so one unavailable
 * profile leaves a placeholder while the other names still show, including from the offline cache.
 * A confirmation captures its group ID, so live updates can't redirect a pending leave.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class YourGroupViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val events: EventRepository,
    private val groups: GroupRepository,
    private val profiles: PublicProfileRepository,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : ViewModel() {
  private val eventId = savedStateHandle.get<String>(EventDetailViewModel.EVENT_ID_ARG).orEmpty()
  private val reads = MutableStateFlow(0)
  private val leave = MutableStateFlow<LeaveGroupRequest?>(null)

  internal val state =
      reads
          .flatMapLatest {
            combine(events.observeGroupEvent(eventId, clock.zone), observeRoster(), leave) {
                details, roster, request ->
              YourGroupScreenState(details, roster, request)
            }
          }
          .combine(network.observeOnline()) { state, online -> state.copy(offline = !online) }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), YourGroupScreenState())

  internal fun retry() {
    reads.value++
  }

  internal fun askToLeave() {
    val current = state.value
    val roster = current.roster as? GroupRoster.Members ?: return
    if (!current.offline) {
      leave.compareAndSet(null, LeaveGroupRequest(roster.groupId, current.event?.event?.title))
    }
  }

  internal fun stay() {
    val request = leave.value ?: return
    if (
        request.stage != LeaveGroupRequest.Stage.LEAVING &&
            request.stage != LeaveGroupRequest.Stage.LEFT
    ) {
      leave.compareAndSet(request, null)
    }
  }

  internal fun confirmLeave() {
    val request = leave.value ?: return
    val writing = request.copy(stage = LeaveGroupRequest.Stage.LEAVING)
    if (
        request == writing ||
            request.stage == LeaveGroupRequest.Stage.LEFT ||
            !leave.compareAndSet(request, writing)
    )
        return
    viewModelScope.launch {
      val stage =
          try {
            when (groups.leaveGroup(eventId, request.groupId)) {
              is GroupWriteResult.Success -> LeaveGroupRequest.Stage.LEFT
              GroupWriteResult.NetworkError -> LeaveGroupRequest.Stage.OFFLINE
              else -> LeaveGroupRequest.Stage.FAILED
            }
          } catch (cancelled: CancellationException) {
            leave.value = request
            throw cancelled
          } catch (_: Exception) {
            LeaveGroupRequest.Stage.FAILED
          }
      leave.value = request.copy(stage = stage)
    }
  }

  private fun observeRoster() =
      groups.observeMyGroup(eventId).distinctUntilChanged().transformLatest<
          MyGroupResult,
          GroupRoster,
      > { result ->
        when (result) {
          MyGroupResult.Loading -> awaitRoster()
          MyGroupResult.NetworkError -> emit(GroupRoster.Unavailable.OFFLINE)
          MyGroupResult.UnexpectedError -> emit(GroupRoster.Unavailable.FAILED)
          is MyGroupResult.Success -> {
            val group = result.group
            if (group == null) {
              when {
                !network.isOnline() -> emit(GroupRoster.Unavailable.OFFLINE)
                result.fromCache -> awaitRoster()
                else -> emit(GroupRoster.Unavailable.MISSING)
              }
            } else {
              val me = auth.currentUser()?.uid
              val ids = group.memberUids.sortedBy { it == me }
              val members = profiles.readGroupMembers(ids)
              emit(GroupRoster.Members(group.id, group.capacity, members, me))
            }
          }
        }
      }

  /** A cache miss can precede a server answer. Keep listening after giving the student Retry. */
  private suspend fun FlowCollector<GroupRoster>.awaitRoster() {
    emit(GroupRoster.Loading)
    delay(ROSTER_TIMEOUT_MILLIS)
    emit(
        if (network.isOnline()) GroupRoster.Unavailable.FAILED else GroupRoster.Unavailable.OFFLINE
    )
  }

  private companion object {
    const val ROSTER_TIMEOUT_MILLIS = 10_000L
  }
}

/** An event summary when the current student can read it. */
internal fun EventRepository.observeGroupEvent(eventId: String, zone: ZoneId) =
    getEvent(eventId)
        .map { (it as? EventResult.Found)?.let { found -> eventDetails(found.event, zone) } }
        .onStart { emit(null) }

/** Reads members independently so an uncached or slow profile cannot hide the others. */
internal suspend fun PublicProfileRepository.readGroupMembers(uids: List<String>) = coroutineScope {
  uids
      .map { uid ->
        async {
          GroupMember(
              uid,
              withTimeoutOrNull(PROFILE_TIMEOUT_MILLIS) {
                (getPublicProfile(uid) as? PublicProfileResult.Found)?.profile
              },
          )
        }
      }
      .awaitAll()
}

private const val PROFILE_TIMEOUT_MILLIS = 10_000L
