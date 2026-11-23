// Contributors: Claude (Opus 5.5, Anthropic) (the one-to-one chat's state, from #430's frames,
// #431).
package com.polysocial.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.chat.MessagesResult
import com.polysocial.model.chat.MyOneToOneChatsResult
import com.polysocial.model.chat.OneToOneChatRepository
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.FriendshipsResult
import com.polysocial.model.friend.uidsOfFriendshipId
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.getPublicProfileWithCache
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Who the student chats with, as the header shows them. */
internal sealed interface ChatFriend {
  data object Loading : ChatFriend

  /** Their public-profile name. */
  data class Named(val name: String) : ChatFriend

  /** Their public profile couldn't be read: the chat still opens (#431). */
  data object Unavailable : ChatFriend

  /** Their account was deleted: the chat no longer lists them (`OneToOneChat.otherUid` null). */
  data object Deleted : ChatFriend
}

/** Whether the student can write in the chat, from the friendship and the chat (#365). */
internal enum class ChatAccess {
  /** Not known yet: the composer shows, disabled. */
  CHECKING,

  /** Friends, or the friendship couldn't be read: the Security Rules still check each send. */
  OPEN,

  /** A friend removed, or a new request still pending: read-only. */
  NOT_FRIENDS,

  /** The other account was deleted: read-only. */
  ACCOUNT_DELETED,
}

internal data class OneToOneChatState(
    val friend: ChatFriend = ChatFriend.Loading,
    val history: ChatHistory = ChatHistory.Loading,
    val access: ChatAccess = ChatAccess.CHECKING,
    val draft: ChatDraft = ChatDraft(),
    val offline: Boolean = false,
) {
  val readOnly: Boolean
    get() = access == ChatAccess.NOT_FRIENDS || access == ChatAccess.ACCOUNT_DELETED

  val canWrite: Boolean
    get() = access == ChatAccess.OPEN && history is ChatHistory.Ready
}

/**
 * The chat between the signed-in student and a friend (#431, Figma section 25), named by the pair's
 * ID. It shows the friend's public-profile name, the messages in the server's order and the
 * composer, which shares the group chat's draft and retry ([ChatDrafts]).
 *
 * Reading doesn't depend on the friendship, so the messages stay after a friend is removed; the
 * friendship is followed live (`observeFriendships`), and the chat turns read-only once the two
 * aren't friends or the other account was deleted (the chat's `otherUid` becomes null). A cached
 * "not friends" waits for the server, so a stale cache can't hide the composer. The messages fail
 * after 10 s without an answer, with Retry, as in the group chat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OneToOneChatViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val chats: OneToOneChatRepository,
    friends: FriendRepository,
    private val profiles: PublicProfileRepository,
    private val auth: AuthRepository,
    network: NetworkMonitor,
    clock: Clock,
) : ViewModel() {
  private val pairId = savedStateHandle.get<String>(PAIR_ID_ARG).orEmpty()
  internal val myUid = auth.currentUser()?.uid
  internal val zone = clock.zone
  internal val today =
      chatToday(clock)
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), LocalDate.now(clock))

  /** The other student of the pair, or null when [pairId] isn't a pair of the student's. */
  private val friendUid =
      uidsOfFriendshipId(pairId)?.takeIf { myUid in it }?.firstOrNull { it != myUid }

  private val reads = MutableStateFlow(0)
  private val drafts = ChatDrafts(viewModelScope)

  private val deleted: Flow<Boolean> =
      chats
          .observeMyChats()
          .map { result ->
            result is MyOneToOneChatsResult.Success &&
                result.chats.any { it.id == pairId && it.otherUid == null }
          }
          .onStart { emit(false) }
          .distinctUntilChanged()

  private val friendship: Flow<ChatAccess> =
      friends
          .observeFriendships()
          .map { result ->
            when (result) {
              is FriendshipsResult.Success -> {
                val stored = result.friendships.firstOrNull { it.id == pairId }
                when {
                  stored?.status == FriendshipStatus.ACCEPTED -> ChatAccess.OPEN
                  result.fromCache -> ChatAccess.CHECKING
                  else -> ChatAccess.NOT_FRIENDS
                }
              }
              FriendshipsResult.Error -> ChatAccess.OPEN
            }
          }
          .onStart { emit(ChatAccess.CHECKING) }
          .distinctUntilChanged()

  private val name: Flow<ChatFriend> = flow {
    emit(ChatFriend.Loading)
    val uid = friendUid
    val result =
        if (uid == null) null else profiles.getPublicProfileWithCache(uid, PROFILE_TIMEOUT_MILLIS)
    emit(
        (result as? PublicProfileResult.Found)?.let { ChatFriend.Named(it.profile.displayName) }
            ?: ChatFriend.Unavailable
    )
  }

  private val history = reads.flatMapLatest { observeHistory() }

  internal val state =
      combine(
              combine(name, deleted, friendship, ::Triple),
              history,
              drafts.draft,
              network.observeOnline(),
          ) { (name, deleted, friendship), history, draft, online ->
            OneToOneChatState(
                friend = if (deleted) ChatFriend.Deleted else name,
                history = history,
                access = if (deleted) ChatAccess.ACCOUNT_DELETED else friendship,
                draft = draft,
                offline = !online,
            )
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
              OneToOneChatState(),
          )

  internal fun retry() {
    reads.value++
  }

  internal fun edit(text: String) = drafts.edit(text)

  internal fun send() {
    if (!state.value.canWrite || auth.currentUser()?.uid != myUid) return
    drafts.send { text, id -> chats.sendMessage(pairId, text, id) }
  }

  /** The messages, Loading until the first answer, which fails after [READ_TIMEOUT_MILLIS]. */
  private fun observeHistory() =
      channelFlow<ChatHistory> {
        send(ChatHistory.Loading)
        val timeout = launch {
          delay(READ_TIMEOUT_MILLIS)
          send(ChatHistory.Failed)
        }
        chats.observeMessages(pairId).collect { result ->
          val history =
              when (result) {
                MessagesResult.Loading -> null
                MessagesResult.NetworkError,
                MessagesResult.UnexpectedError -> ChatHistory.Failed
                is MessagesResult.Success -> ChatHistory.Ready(result.messages, emptyList())
              }
          if (history != null) {
            timeout.cancel()
            send(history)
          }
        }
      }

  internal companion object {
    const val PAIR_ID_ARG = "pairId"
    private const val READ_TIMEOUT_MILLIS = 10_000L
    private const val PROFILE_TIMEOUT_MILLIS = 10_000L
  }
}
