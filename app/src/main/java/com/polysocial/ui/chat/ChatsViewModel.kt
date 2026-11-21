// Contributors: Claude (Opus 5.5, Anthropic) (the Chats list, from #362's Figma section 16, #364;
// the first name moved to model/user for Find a group, #497).
package com.polysocial.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.chat.ChatPreview
import com.polysocial.model.chat.ChatsRepository
import com.polysocial.model.chat.DELETED_MESSAGE_SENDER
import com.polysocial.model.chat.MyChatsResult
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
import kotlinx.coroutines.cancelChildren
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
   * The student's chats, newest activity first, as [ChatsRepository] orders them; empty when they
   * are in no group ("Join a group to chat"). Offline with none saved is [OfflineNothingSaved].
   *
   * @property isOffline true when the list comes from the cache and the device is offline: the
   *   screen shows its offline banner above the chats.
   */
  data class Chats(val rows: List<ChatRow>, val isOffline: Boolean) : ChatsUiState
}

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
)

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
 * names ([PublicProfileRepository.getPublicProfiles], read once per student).
 *
 * The list shows as soon as the chats answer; titles and names fill in as their own reads answer.
 * If the chats don't answer within [LOAD_TIMEOUT], or can't be read, it shows the error state, and
 * [retry] reads them again. A name that can't be read leaves its member unnamed; an event that
 * can't be read gives the row "Group chat". Times are taken from [clock] when each list is built,
 * and again at midnight, so "23:58" turns into "Yesterday".
 */
@HiltViewModel
class ChatsViewModel
@Inject
constructor(
    private val chats: ChatsRepository,
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
  private var latest: MyChatsResult.Success? = null
  private var eventsById: Map<String, ChatEvent> = emptyMap()
  private var profilesById: Map<String, PublicProfile> = emptyMap()

  /** The events listened to and the profiles read or being read, so each is read once. */
  private val listened = mutableSetOf<String>()
  private val requested = mutableSetOf<String>()

  init {
    observe()
  }

  /** Reads the chats again, after the error or offline state. Does nothing otherwise. */
  fun retry() {
    val state = _state.value
    if (state != ChatsUiState.Error && state != ChatsUiState.OfflineNothingSaved) return
    _state.value = ChatsUiState.Loading
    observe()
  }

  private fun observe() {
    session?.cancel()
    me = auth.currentUser()?.uid
    latest = null
    listened.clear()
    requested.clear()
    session = viewModelScope.launch {
      val reads = this
      launch {
        delay(LOAD_TIMEOUT)
        if (latest == null) _state.value = ChatsUiState.Error
      }
      // At midnight "23:58" becomes "Yesterday", as the group chat's days turn (#444 review).
      launch {
        while (true) {
          delay(untilMidnight())
          show()
        }
      }
      chats.observeMyChats().collect { result ->
        when (result) {
          MyChatsResult.Loading -> Unit
          is MyChatsResult.Success -> {
            latest = result
            reads.listenToEvents(result.chats)
            reads.readProfiles(result.chats)
            show()
          }
          MyChatsResult.NetworkError,
          MyChatsResult.UnexpectedError -> {
            // The other reads have nothing left to show: stop them, and the timeout.
            reads.coroutineContext.cancelChildren()
            latest = null
            _state.value = ChatsUiState.Error
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
   * Reads the public profiles of the members and latest senders not read yet. A failed read forgets
   * them, so the next list tries again.
   */
  private fun CoroutineScope.readProfiles(previews: List<ChatPreview>) {
    val uids =
        previews
            .flatMap { it.group.memberUids + listOfNotNull(it.latestMessage?.senderId) }
            .filter { it != DELETED_MESSAGE_SENDER && it != me && requested.add(it) }
    if (uids.isEmpty()) return
    launch {
      when (val result = profiles.getPublicProfiles(uids)) {
        is PublicProfilesResult.Found -> {
          profilesById = profilesById + result.profiles
          show()
        }
        PublicProfilesResult.NetworkError,
        PublicProfilesResult.UnexpectedError -> requested.removeAll(uids.toSet())
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
    val result = latest ?: return
    val now = clock.instant()
    val isOffline = result.fromCache && !network.isOnline()
    _state.value =
        if (result.chats.isEmpty() && isOffline) ChatsUiState.OfflineNothingSaved
        else ChatsUiState.Chats(rows = result.chats.map { row(it, now) }, isOffline = isOffline)
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

  companion object {
    /** How long the first list may take before the error state, as on the other tabs. */
    val LOAD_TIMEOUT: Duration = 10.seconds
  }
}
