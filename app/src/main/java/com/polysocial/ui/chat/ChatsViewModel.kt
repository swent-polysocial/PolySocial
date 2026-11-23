// Contributors: Claude (Opus 5.5, Anthropic) (the Chats list, from #362's Figma section 16, #364;
// the first name moved to model/user for Find a group, #497; one-to-one chats merged into it, from
// #430's Figma section 25, #432).
package com.polysocial.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.chat.ChatPreview
import com.polysocial.model.chat.ChatsRepository
import com.polysocial.model.chat.DELETED_MESSAGE_SENDER
import com.polysocial.model.chat.MyChatsResult
import com.polysocial.model.chat.OneToOneChatPreview
import com.polysocial.model.chat.OneToOneChatPreviewsResult
import com.polysocial.model.chat.OneToOneChatRepository
import com.polysocial.model.chat.observeChatPreviews
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfilesResult
import com.polysocial.model.user.firstName
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Duration as JavaDuration
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Chats tab shows (#364). */
sealed interface ChatsUiState {
  /** The chats haven't arrived yet, for at most [ChatsViewModel.LOAD_TIMEOUT]. */
  data object Loading : ChatsUiState

  /** The chats couldn't be read, or didn't arrive in time: "Couldn't load your chats" and Retry. */
  data object Error : ChatsUiState

  /**
   * Offline with no chat saved on this device: "You're offline" and Retry, rather than "Join a
   * group to chat", which a student in groups would read as having none (#444 review, as My
   * events).
   */
  data object OfflineNothingSaved : ChatsUiState

  /**
   * The student's group and one-to-one chats in one list, newest activity first (a group without a
   * message by its creation time, as [ChatsRepository] orders them); empty when they have none
   * ("Join a group to chat"). Offline with none saved is [OfflineNothingSaved].
   *
   * @property isOffline true when the list comes from the cache and the device is offline: the
   *   screen shows its offline banner above the chats.
   * @property partlyFailed true when one of the two kinds of chats couldn't be read: the other is
   *   listed, with a note and Retry (#432).
   */
  data class Chats(
      val rows: List<ChatListRow>,
      val isOffline: Boolean,
      val partlyFailed: Boolean = false,
  ) : ChatsUiState
}

/** One row of the Chats list: a group chat ([ChatRow]) or a one-to-one chat ([OneToOneChatRow]). */
sealed interface ChatListRow

/**
 * One row of the Chats list: a group the student is in.
 *
 * @property event the group's event, as far as it is known.
 * @property otherNames the first names of the other members whose public profile was read, in the
 *   group's order.
 * @property otherCount how many other members the group has, named or not.
 * @property latest the latest message, or null while the group has none.
 */
data class ChatRow(
    val eventId: String,
    val groupId: String,
    val event: ChatEvent,
    val otherNames: List<String>,
    val otherCount: Int,
    val latest: LatestMessage?,
) : ChatListRow

/**
 * One row of the Chats list: a chat with a friend (#432, Figma section 25), which is listed only
 * once it has a message. A read-only chat looks like any other (decided in #365).
 *
 * @property pairId the chat's pair ID, which opens it (#431).
 * @property latest the latest message: "You: …" for the student's own, the text alone for the
 *   friend's, since the row names them.
 */
data class OneToOneChatRow(
    val pairId: String,
    val friend: ChatListFriend,
    val latest: LatestMessage,
) : ChatListRow

/** Who a one-to-one row is with, as it names them. */
sealed interface ChatListFriend {
  /** Their public profile hasn't answered yet. */
  data object Loading : ChatListFriend

  /** Their public-profile name. */
  data class Named(val name: String) : ChatListFriend

  /** Their public profile couldn't be read: the row still shows. */
  data object Unavailable : ChatListFriend

  /** Their account was deleted: "Deleted account". */
  data object Deleted : ChatListFriend
}

/** A chat's event, read separately from the chat. */
sealed interface ChatEvent {
  /** The event hasn't answered yet. */
  data object Loading : ChatEvent

  /** The event's title and category, for the row's title and tile. */
  data class Known(val title: String, val category: EventCategory) : ChatEvent

  /**
   * The student can't read the event any more (deleted, or a private event they were removed from),
   * or it isn't saved on this device while offline: the row says "Group chat", as the group chat's
   * own header does. The chat stays the student's.
   */
  data object Unavailable : ChatEvent
}

/** A row's latest message: who sent it, its text and when. */
data class LatestMessage(val sender: Sender, val text: String, val time: ChatTime)

/** Who sent a latest message, as the row names them. */
sealed interface Sender {
  /** The signed-in student: "You: …". */
  data object You : Sender

  /** Another member, by first name: "Léa: …". */
  data class Named(val firstName: String) : Sender

  /** An account deleted since, whose messages keep an empty sender (#241). */
  data object DeletedAccount : Sender

  /** A member whose public profile couldn't be read: the text alone. */
  data object Unknown : Sender
}

/**
 * Holds the Chats tab (#364, Figma section 16): every group chat the student is in
 * ([ChatsRepository.observeMyChats]), each with its event's title and category
 * ([EventRepository.getEvent], one listener per event) and its members' and latest sender's first
 * names ([PublicProfileRepository.getPublicProfiles], read once per student), and their one-to-one
 * chats with a message ([observeChatPreviews], #432), each named by the friend's public profile.
 *
 * The two kinds share one list, ordered by their latest activity. It shows once both have answered;
 * titles and names fill in as their own reads answer. If one kind can't be read, or hasn't answered
 * within [LOAD_TIMEOUT], the other is listed with a note; if neither can, it shows the error state.
 * [retry] reads both again. A name that can't be read leaves its member unnamed (a friend's row
 * says so); an event that can't be read gives the row "Group chat". Times are taken from [clock]
 * when each list is built, and again at midnight, so "23:58" turns into "Yesterday".
 */
@HiltViewModel
class ChatsViewModel
@Inject
constructor(
    private val chats: ChatsRepository,
    private val oneToOneChats: OneToOneChatRepository,
    private val events: EventRepository,
    private val profiles: PublicProfileRepository,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : ViewModel() {
  private val _state = MutableStateFlow<ChatsUiState>(ChatsUiState.Loading)
  val state: StateFlow<ChatsUiState> = _state.asStateFlow()

  /** Everything one reading of the chats started: cancelled by [retry]. */
  private var session: Job? = null

  private var me: String? = null
  private var groups: Read<MyChatsResult.Success> = Read.Waiting
  private var oneToOne: Read<OneToOneChatPreviewsResult.Success> = Read.Waiting
  private var eventsById: Map<String, ChatEvent> = emptyMap()
  private var profilesById: Map<String, PublicProfile> = emptyMap()

  /** The profiles whose last read failed: a friend's row says so until a read succeeds. */
  private var unreadable: Set<String> = emptySet()

  /** The events listened to and the profiles read or being read, so each is read once. */
  private val listened = mutableSetOf<String>()
  private val requested = mutableSetOf<String>()

  init {
    observe()
  }

  /**
   * Reads the chats again, after the error, offline or partly failed state. Does nothing otherwise.
   */
  fun retry() {
    val state = _state.value
    val failed =
        state == ChatsUiState.Error ||
            state == ChatsUiState.OfflineNothingSaved ||
            (state as? ChatsUiState.Chats)?.partlyFailed == true
    if (!failed) return
    _state.value = ChatsUiState.Loading
    observe()
  }

  private fun observe() {
    session?.cancel()
    me = auth.currentUser()?.uid
    groups = Read.Waiting
    oneToOne = Read.Waiting
    listened.clear()
    requested.clear()
    session = viewModelScope.launch {
      val reads = this
      launch {
        delay(LOAD_TIMEOUT)
        if (groups == Read.Waiting) groups = Read.Failed
        if (oneToOne == Read.Waiting) oneToOne = Read.Failed
        show()
      }
      // At midnight "23:58" becomes "Yesterday", as the group chat's days turn (#444 review).
      launch {
        while (true) {
          delay(untilMidnight())
          show()
        }
      }
      launch {
        oneToOneChats.observeChatPreviews().collect { result ->
          when (result) {
            OneToOneChatPreviewsResult.Loading -> Unit
            is OneToOneChatPreviewsResult.Success -> {
              oneToOne = Read.Done(result)
              reads.readProfiles(result.previews.mapNotNull { it.chat.otherUid })
              show()
            }
            OneToOneChatPreviewsResult.Failed -> {
              oneToOne = Read.Failed
              show()
            }
          }
        }
      }
      chats.observeMyChats().collect { result ->
        when (result) {
          MyChatsResult.Loading -> Unit
          is MyChatsResult.Success -> {
            groups = Read.Done(result)
            reads.listenToEvents(result.chats)
            reads.readProfiles(
                result.chats.flatMap {
                  it.group.memberUids + listOfNotNull(it.latestMessage?.senderId)
                }
            )
            show()
          }
          MyChatsResult.NetworkError,
          MyChatsResult.UnexpectedError -> {
            groups = Read.Failed
            show()
          }
        }
      }
    }
  }

  private fun CoroutineScope.listenToEvents(previews: List<ChatPreview>) {
    previews
        .map { it.group.eventId }
        .filter(listened::add)
        .forEach { eventId ->
          launch {
            events.getEvent(eventId).collect { result ->
              val event =
                  when (result) {
                    is EventResult.Found ->
                        ChatEvent.Known(result.event.title, result.event.category)
                    EventResult.NotFound,
                    EventResult.NotCached -> ChatEvent.Unavailable
                  }
              eventsById = eventsById + (eventId to event)
              show()
            }
          }
        }
  }

  /**
   * Reads the public profiles of [uids] not read yet: the members, latest senders and friends. A
   * failed read forgets them, so the next list tries again.
   */
  private fun CoroutineScope.readProfiles(uids: List<String>) {
    val wanted = uids.filter { it != DELETED_MESSAGE_SENDER && it != me && requested.add(it) }
    if (wanted.isEmpty()) return
    launch {
      when (val result = profiles.getPublicProfiles(wanted)) {
        is PublicProfilesResult.Found -> {
          profilesById = profilesById + result.profiles
          unreadable = unreadable - wanted.toSet() + wanted.filterNot(result.profiles::containsKey)
          show()
        }
        PublicProfilesResult.NetworkError,
        PublicProfilesResult.UnexpectedError -> {
          requested.removeAll(wanted.toSet())
          unreadable = unreadable + wanted
          show()
        }
      }
    }
  }

  /** The time left until the next midnight in [clock]'s zone, at least 1 ms. */
  private fun untilMidnight(): Long {
    val now = clock.instant()
    val midnight = now.atZone(clock.zone).toLocalDate().plusDays(1).atStartOfDay(clock.zone)
    return JavaDuration.between(now, midnight.toInstant()).toMillis().coerceAtLeast(1)
  }

  private fun show() {
    val groups = groups
    val oneToOne = oneToOne
    // The first list waits for both kinds, so its order doesn't change when the slower one answers.
    if (groups == Read.Waiting || oneToOne == Read.Waiting) return
    if (groups == Read.Failed && oneToOne == Read.Failed) {
      _state.value = ChatsUiState.Error
      return
    }
    val groupChats = (groups as? Read.Done)?.value
    val friendChats = (oneToOne as? Read.Done)?.value
    val now = clock.instant()
    val rows =
        (groupChats?.chats.orEmpty().map {
              (it.latestMessage?.createdAt ?: it.group.createdAt) to row(it, now)
            } + friendChats?.previews.orEmpty().map { it.latestMessage.createdAt to row(it, now) })
            .sortedWith(compareByDescending(nullsLast()) { it.first })
            .map { it.second }
    val isOffline =
        (groupChats?.fromCache == true || friendChats?.fromCache == true) && !network.isOnline()
    val partlyFailed = groups == Read.Failed || oneToOne == Read.Failed
    _state.value =
        when {
          // Nothing to list beside the failure: the error state, with Retry.
          rows.isEmpty() && partlyFailed -> ChatsUiState.Error
          rows.isEmpty() && isOffline -> ChatsUiState.OfflineNothingSaved
          else -> ChatsUiState.Chats(rows, isOffline, partlyFailed)
        }
  }

  private fun row(preview: ChatPreview, now: Instant): ChatRow {
    val group = preview.group
    val others = group.memberUids.filter { it != me }
    return ChatRow(
        eventId = group.eventId,
        groupId = group.id,
        event = eventsById[group.eventId] ?: ChatEvent.Loading,
        otherNames = others.mapNotNull { profilesById[it]?.firstName() },
        otherCount = others.size,
        latest =
            preview.latestMessage?.let { message ->
              LatestMessage(
                  sender =
                      when {
                        message.senderId == DELETED_MESSAGE_SENDER -> Sender.DeletedAccount
                        message.senderId == me -> Sender.You
                        else ->
                            profilesById[message.senderId]?.firstName()?.let(Sender::Named)
                                ?: Sender.Unknown
                      },
                  text = message.text,
                  time = chatTime(message.createdAt, now, clock.zone),
              )
            },
    )
  }

  private fun row(preview: OneToOneChatPreview, now: Instant): OneToOneChatRow {
    val friend = preview.chat.otherUid
    val message = preview.latestMessage
    return OneToOneChatRow(
        pairId = preview.chat.id,
        friend =
            when {
              friend == null -> ChatListFriend.Deleted
              friend in profilesById ->
                  ChatListFriend.Named(profilesById.getValue(friend).displayName)
              friend in unreadable -> ChatListFriend.Unavailable
              else -> ChatListFriend.Loading
            },
        latest =
            LatestMessage(
                // The row names the friend, so only the student's own message gets a sender.
                sender = if (message.senderId == me) Sender.You else Sender.Unknown,
                text = message.text,
                time = chatTime(message.createdAt, now, clock.zone),
            ),
    )
  }

  /** Where one kind of chats stands. */
  private sealed interface Read<out T> {
    data object Waiting : Read<Nothing>

    data class Done<T>(val value: T) : Read<T>

    data object Failed : Read<Nothing>
  }

  companion object {
    /** How long the first list may take before the error state, as on the other tabs. */
    val LOAD_TIMEOUT: Duration = 10.seconds
  }
}
