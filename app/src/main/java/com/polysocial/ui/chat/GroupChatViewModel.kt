// Contributors: OpenAI Codex (live chat, membership gating, stable sender names, connectivity
// and retry state, #238).
// Contributors: Claude (Opus 5.5, Anthropic) (the draft and today's date moved to ChatDraft.kt, to
// share them with the one-to-one chat, #431; the history and its first read's timeout moved to
// ChatHistory.kt, from #520's review).
package com.polysocial.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.chat.MessageRepository
import com.polysocial.model.event.EventRepository
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.MyGroupResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.event.detail.EventDetails
import com.polysocial.ui.group.GroupMember
import com.polysocial.ui.group.observeGroupEvent
import com.polysocial.ui.group.readGroupMembers
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

internal data class ChatState(
    val event: EventDetails? = null,
    val history: ChatHistory = ChatHistory.Loading,
    val draft: ChatDraft = ChatDraft(),
    val offline: Boolean = false,
)

/**
 * Holds the route's draft and observes messages while the student belongs to the selected group. An
 * unchanged failed draft keeps its document ID across manual retries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupChatViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    groups: GroupRepository,
    private val messages: MessageRepository,
    private val profiles: PublicProfileRepository,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
    events: EventRepository,
    clock: Clock,
) : ViewModel() {
  private val eventId = savedStateHandle.get<String>(EventDetailViewModel.EVENT_ID_ARG).orEmpty()
  private val groupId = savedStateHandle.get<String>(GROUP_ID_ARG).orEmpty()
  internal val myUid = auth.currentUser()?.uid
  internal val zone = clock.zone
  internal val today =
      chatToday(clock)
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), LocalDate.now(clock))
  private val reads = MutableStateFlow(0)
  private val drafts = ChatDrafts(viewModelScope)

  private val history = reads.flatMapLatest { observeHistory(groups) }

  internal val state =
      combine(
              events.observeGroupEvent(eventId, zone),
              history,
              drafts.draft,
              network.observeOnline(),
          ) { event, history, draft, online ->
            ChatState(event, history, draft, offline = !online)
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
              ChatState(),
          )

  internal fun retry() {
    reads.value++
  }

  internal fun edit(text: String) = drafts.edit(text)

  internal fun send() {
    if (state.value.history !is ChatHistory.Ready || auth.currentUser()?.uid != myUid) return
    drafts.send { text, id -> messages.sendMessage(eventId, groupId, text, id) }
  }

  /** Share one membership listener, but only restart messages when access itself changes. */
  private fun observeHistory(groups: GroupRepository) =
      channelFlow<ChatHistory> {
            val membership =
                groups
                    .observeMyGroup(eventId)
                    .map(::membershipFor)
                    .distinctUntilChanged()
                    .shareIn(this, SharingStarted.Lazily, replay = 1)
            val messagesByAccess =
                membership
                    .map { it.access }
                    .distinctUntilChanged()
                    .transformLatest<Access, ChatHistory> { access ->
                      when (access) {
                        Access.WAITING -> emit(ChatHistory.Loading)
                        Access.FAILED -> emit(ChatHistory.Failed)
                        Access.LEFT -> {
                          drafts.clear()
                          emit(ChatHistory.Left)
                        }
                        Access.MEMBER -> emitAll(observeMessages())
                      }
                    }
            var loadedMembers = emptyMap<String, GroupMember>()
            val memberProfiles =
                membership
                    .map { it.memberUids }
                    .distinctUntilChanged()
                    .transformLatest<List<String>, List<GroupMember>> { uids ->
                      loadedMembers = loadedMembers.filterKeys { it in uids }
                      emit(uids.map { loadedMembers[it] ?: GroupMember(it, null) })
                      if (uids.isNotEmpty()) {
                        val refreshedMembers = profiles.readGroupMembers(uids)
                        loadedMembers = refreshedMembers.associateBy { it.uid }
                        emit(refreshedMembers)
                      }
                    }
                    .onStart { emit(emptyList()) }
            combine(messagesByAccess, memberProfiles) { history, members ->
                  if (history is ChatHistory.Ready) history.copy(members = members) else history
                }
                .collect { send(it) }
          }
          .failingIfSilent()

  private fun membershipFor(result: MyGroupResult): Membership =
      when (result) {
        MyGroupResult.Loading -> Membership(Access.WAITING)
        MyGroupResult.NetworkError,
        MyGroupResult.UnexpectedError -> Membership(Access.FAILED)
        is MyGroupResult.Success -> {
          val group = result.group
          when {
            group == null && result.fromCache -> Membership(Access.WAITING)
            group == null || group.id != groupId || myUid !in group.memberUids ->
                Membership(Access.LEFT)
            else -> Membership(Access.MEMBER, group.memberUids)
          }
        }
      }

  private fun observeMessages() =
      messages.observeMessages(eventId, groupId).map { it.toChatHistory() }

  private data class Membership(val access: Access, val memberUids: List<String> = emptyList())

  private enum class Access {
    WAITING,
    FAILED,
    LEFT,
    MEMBER,
  }

  internal companion object {
    const val GROUP_ID_ARG = "groupId"
  }
}
