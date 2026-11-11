// Contributors: OpenAI Codex (live chat, membership and retry state, #238).
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
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class ChatState(
    val event: EventDetails? = null,
    val history: ChatHistory = ChatHistory.Loading,
    val draft: ChatDraft = ChatDraft(),
)

internal sealed interface ChatHistory {
  data object Loading : ChatHistory

  data object Failed : ChatHistory

  data object Left : ChatHistory

  data class Ready(
      val messages: List<Message>,
      val members: List<GroupMember>,
      val offline: Boolean,
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
  internal val today = java.time.LocalDate.now(clock)
  internal val zone = clock.zone
  private val reads = MutableStateFlow(0)
  private val draft = MutableStateFlow(ChatDraft())

  private val history = reads.flatMapLatest {
    val updates =
        groups.observeMyGroup(eventId).distinctUntilChanged().flatMapLatest { result ->
          when (result) {
            MyGroupResult.Loading -> flowOf(ChatHistory.Loading)
            MyGroupResult.NetworkError,
            MyGroupResult.UnexpectedError -> flowOf(ChatHistory.Failed)
            is MyGroupResult.Success -> {
              val group = result.group
              when {
                group == null && result.fromCache -> flowOf(ChatHistory.Loading)
                group == null || group.id != groupId || myUid !in group.memberUids -> {
                  draft.value = ChatDraft()
                  flowOf(ChatHistory.Left)
                }
                else -> observeMessages(group.memberUids)
              }
            }
          }
        }
    combine(
        updates.onStart { emit(ChatHistory.Loading) },
        flow {
          emit(false)
          delay(READ_TIMEOUT_MILLIS)
          emit(true)
        },
    ) { loaded, timedOut ->
      if (loaded == ChatHistory.Loading && timedOut) ChatHistory.Failed else loaded
    }
  }

  internal val state =
      combine(events.observeGroupEvent(eventId, zone), history, draft, ::ChatState)
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
    if (current.stage == ChatDraft.Stage.SENDING || text.length > MAX_MESSAGE_LENGTH) return
    val sameMessage = current.text.trim() == text.trim()
    draft.value = current.copy(text = text, id = current.id.takeIf { sameMessage })
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

  private fun observeMessages(uids: List<String>) =
      combine(
          messages.observeMessages(eventId, groupId),
          flow { emit(profiles.readGroupMembers(uids)) }
              .onStart { emit(uids.map { GroupMember(it, null) }) },
      ) { result, members ->
        when (result) {
          MessagesResult.Loading -> ChatHistory.Loading
          MessagesResult.UnexpectedError,
          MessagesResult.NetworkError -> ChatHistory.Failed
          is MessagesResult.Success ->
              ChatHistory.Ready(result.messages, members, !network.isOnline())
        }
      }

  internal companion object {
    const val GROUP_ID_ARG = "groupId"
    private const val READ_TIMEOUT_MILLIS = 10_000L
  }
}
