// Contributors: OpenAI Codex (live chat, membership gating, connectivity and retry state, #238).
package com.polysocial.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.chat.MAX_MESSAGE_LENGTH
import com.polysocial.model.chat.Message
import com.polysocial.model.chat.MessageRepository
import com.polysocial.model.chat.MessagesResult
import com.polysocial.model.chat.SendMessageResult
import com.polysocial.model.chat.validateMessageText
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
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

internal data class ChatState(
    val event: EventDetails? = null,
    val history: ChatHistory = ChatHistory.Loading,
    val draft: ChatDraft = ChatDraft(),
    val offline: Boolean = false,
)

internal sealed interface ChatHistory {
  data object Loading : ChatHistory

  data object Failed : ChatHistory

  data object Left : ChatHistory

  data class Ready(
      val messages: List<Message>,
      val members: List<GroupMember>,
  ) : ChatHistory
}

internal data class ChatDraft(
    val text: String = "",
    val id: String? = null,
    val stage: Stage = Stage.EDITING,
) {
  enum class Stage {
    EDITING,
    SENDING,
    OFFLINE,
    FAILED,
  }

  val canSend
    get() = stage != Stage.SENDING && validateMessageText(text) == null
}

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
  internal val today = flow {
    while (true) {
      val now = clock.instant()
      val date = now.atZone(zone).toLocalDate()
      emit(date)
      val midnight = date.plusDays(1).atStartOfDay(zone).toInstant()
      delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1))
    }
  }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), LocalDate.now(clock))
  private val reads = MutableStateFlow(0)
  private val draft = MutableStateFlow(ChatDraft())

  private val history = reads.flatMapLatest { observeHistory(groups) }

  internal val state =
      combine(events.observeGroupEvent(eventId, zone), history, draft, network.observeOnline()) {
              event,
              history,
              draft,
              online ->
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

  internal fun edit(text: String) {
    val current = draft.value
    if (current.stage == ChatDraft.Stage.SENDING) return
    val prefix = text.take(MAX_MESSAGE_LENGTH)
    val limited =
        if (text.length > prefix.length && prefix.last().isHighSurrogate()) prefix.dropLast(1)
        else prefix
    val sameMessage = current.text.trim() == limited.trim()
    draft.value = current.copy(text = limited, id = current.id.takeIf { sameMessage })
  }

  internal fun send() {
    val current = draft.value
    if (
        !current.canSend ||
            state.value.history !is ChatHistory.Ready ||
            auth.currentUser()?.uid != myUid
    )
        return
    val pending =
        current.copy(
            id = current.id ?: UUID.randomUUID().toString(),
            stage = ChatDraft.Stage.SENDING,
        )
    if (!draft.compareAndSet(current, pending)) return
    viewModelScope.launch {
      val result =
          try {
            messages.sendMessage(eventId, groupId, pending.text, pending.id)
          } catch (cancelled: CancellationException) {
            draft.compareAndSet(pending, current)
            throw cancelled
          } catch (_: Exception) {
            SendMessageResult.UnexpectedError
          }
      val next =
          when (result) {
            SendMessageResult.Sent -> ChatDraft()
            SendMessageResult.NetworkError -> pending.copy(stage = ChatDraft.Stage.OFFLINE)
            else -> pending.copy(stage = ChatDraft.Stage.FAILED)
          }
      draft.compareAndSet(pending, next)
    }
  }

  /** Share one membership listener, but only restart messages when access itself changes. */
  private fun observeHistory(groups: GroupRepository) =
      channelFlow<ChatHistory> {
        send(ChatHistory.Loading)
        val timeout = launch {
          delay(READ_TIMEOUT_MILLIS)
          send(ChatHistory.Failed)
        }
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
                      draft.value = ChatDraft()
                      emit(ChatHistory.Left)
                    }
                    Access.MEMBER -> emitAll(observeMessages())
                  }
                }
        val memberProfiles =
            membership
                .map { it.memberUids }
                .distinctUntilChanged()
                .transformLatest<List<String>, List<GroupMember>> { uids ->
                  if (uids.isNotEmpty()) {
                    emit(uids.map { GroupMember(it, null) })
                    emit(profiles.readGroupMembers(uids))
                  }
                }
                .onStart { emit(emptyList()) }
        var initialLoad = true
        combine(messagesByAccess, memberProfiles) { history, members ->
              if (history is ChatHistory.Ready) history.copy(members = members) else history
            }
            .collect { history ->
              if (history != ChatHistory.Loading) {
                initialLoad = false
                timeout.cancel()
              }
              // While the first read is pending, keep the initial Loading or timeout error visible.
              if (!initialLoad) send(history)
            }
      }

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
      messages.observeMessages(eventId, groupId).map { result ->
        when (result) {
          MessagesResult.Loading -> ChatHistory.Loading
          MessagesResult.UnexpectedError,
          MessagesResult.NetworkError -> ChatHistory.Failed
          is MessagesResult.Success -> ChatHistory.Ready(result.messages, emptyList())
        }
      }

  private data class Membership(val access: Access, val memberUids: List<String> = emptyList())

  private enum class Access {
    WAITING,
    FAILED,
    LEFT,
    MEMBER,
  }

  internal companion object {
    const val GROUP_ID_ARG = "groupId"
    private const val READ_TIMEOUT_MILLIS = 10_000L
  }
}
