// Contributors: Claude (Fable 5.2, Anthropic) (drafted the chat list read, #363).
// Contributors: Claude (Opus 5.5, Anthropic) (one listener per chat, and a chat left while
// disconnected no longer fails the list, after review, #363).
package com.polysocial.model.chat

import com.polysocial.model.group.Group
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.MyGroupsResult
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * A chat as a chat list shows it: a group the signed-in student is in, with its latest message.
 *
 * @property latestMessage the group's newest message, or null while it has none.
 */
data class ChatPreview(val group: Group, val latestMessage: Message?)

/** What [ChatsRepository.observeMyChats] emits. */
sealed interface MyChatsResult {
  /**
   * Nothing is listed yet: the groups, or the latest message of one of them, haven't answered.
   * Emitted first, unless a newer result already replaced it.
   */
  data object Loading : MyChatsResult

  /**
   * The signed-in student's chats, one per group they are in, across events; empty when they are in
   * none. Newest activity first: by the latest message's time, or by the group's creation time
   * while it has no message, then by event and group ID so the order is stable. A chat whose latest
   * message is denied is left out, and so is a group joined after the first list until its latest
   * message has answered (see [ChatsRepository.observeMyChats]).
   *
   * @property fromCache true when the groups or any listed latest message come from Firestore's
   *   offline cache, for example while the device is offline. They may then be out of date.
   */
  data class Success(val chats: List<ChatPreview>, val fromCache: Boolean) : MyChatsResult

  /** The chats couldn't be read because the server can't be reached. The flow then ends. */
  data object NetworkError : MyChatsResult

  /**
   * Any other failure, for example nobody signed in or an association, whose reads the Security
   * Rules deny, or a chat the student can't read although the server still lists them in its group.
   * The flow then ends.
   */
  data object UnexpectedError : MyChatsResult
}

/**
 * The signed-in student's chats, for the Chats tab (#364): every group they are in, across events
 * ([GroupRepository.observeMyGroups]), each with its latest message
 * ([MessageRepository.observeLatestMessage], one document per group).
 *
 * It reads nothing a member couldn't already read: the groups come from the student's own
 * collection-group query, and each latest message is read under its group's chat rule. The event's
 * name and the members' names aren't here: read them from their own repositories, by ID. A listed
 * chat's event may be gone, deleted or a private event the student was removed from: `getEvent`
 * then answers `EventResult.NotFound`, so the tab needs a label for a chat without its event.
 *
 * If every chat is denied, the list is a [MyChatsResult.Success] with no chats, shown as "no chats"
 * rather than as an error, until the groups next change on the server (see [observeMyChats]).
 */
class ChatsRepository
@Inject
constructor(
    private val groups: GroupRepository,
    private val messages: MessageRepository,
) {
  /**
   * Observes the signed-in student's chats.
   *
   * The flow emits [MyChatsResult.Loading], then a [MyChatsResult.Success] once the groups and the
   * latest message of each have answered, and again on every change: a group joined or left, its
   * members changing, a new message. Each chat has its own latest-message listener, started when
   * its group is listed and stopped when it no longer is; nothing else is listened to again. After
   * the first list, a group just joined is added once its own latest message has answered, while
   * the other chats keep updating. Offline, everything comes from the cache (`fromCache`).
   * Firestore can stay silent while it looks for the server, so a screen waiting for the first list
   * needs its own timeout. A slow collector gets only the latest result.
   *
   * It ends after a [MyChatsResult.NetworkError] or [MyChatsResult.UnexpectedError], which is
   * always delivered last: when the groups can't be read, or when a latest message can't be read
   * because the server can't be reached. A latest message that is denied
   * ([LatestMessageResult.UnexpectedError]) only leaves its chat out of the list: after a
   * reconnection, Firestore reports the chat of a group the student left on another device as
   * denied before it delivers the groups without that group. If the groups then drop it, the list
   * goes on without it. If a later groups result from the server still lists it, the student is a
   * member who can't read their chat, and the list ends with [MyChatsResult.UnexpectedError]. That
   * result comes with the groups' next change from the server (a member joining or leaving, a group
   * joined, the device reconnecting), so until then the chat is only missing.
   *
   * Cancelling the collection stops every listener. The student is the one signed in when
   * collection starts, so a new account needs a new collection.
   */
  fun observeMyChats(): Flow<MyChatsResult> = channelFlow {
    send(MyChatsResult.Loading)
    val chats = MyChats()
    val listeners = mutableMapOf<ChatId, Job>()
    // Every answer arrives here in order, so the state above changes in one coroutine at a time.
    val updates = Channel<Update>(Channel.UNLIMITED)
    launch { groups.observeMyGroups().collect { updates.send(Update.Groups(it)) } }
    for (update in updates) {
      when (update) {
        is Update.Groups -> {
          chats.groups(update.result)
          // Only the chats that came or went: the groups also emit when a member joins or the
          // device goes offline, and a listener stopped then would make an in-memory cache forget
          // its message (see CONTEXT.md, Traps).
          val ids = chats.ids
          (listeners - ids).values.forEach { it.cancel() }
          listeners.keys.retainAll(ids)
          (ids - listeners.keys).forEach { listeners[it] = listen(it, updates) }
        }
        is Update.Latest -> {
          // A stopped listener's last answer: its chat left, and may be back with a new listener.
          if (listeners[update.chat] !== update.listener) continue
          chats.latest(update.chat, update.result)
        }
      }
      val result = chats.result() ?: continue
      send(result)
      if (result !is MyChatsResult.Success) break
    }
    // Stops the groups' listener and every chat's, so the flow ends.
    coroutineContext.cancelChildren()
  }
      .conflate()
      .distinctUntilChanged()

  /** Listens to [chat]'s latest message, sending each answer to [updates] with its listener. */
  private fun CoroutineScope.listen(chat: ChatId, updates: SendChannel<Update>): Job = launch {
    val listener = coroutineContext.job
    messages.observeLatestMessage(chat.eventId, chat.groupId).collect {
      updates.send(Update.Latest(chat, listener, it))
    }
  }
}

/** A chat's group: a group ID is unique only within its event. */
private data class ChatId(val eventId: String, val groupId: String)

private val Group.chat: ChatId
  get() = ChatId(eventId, id)

/** What [ChatsRepository.observeMyChats] hears from its listeners. */
private sealed interface Update {
  class Groups(val result: MyGroupsResult) : Update

  /** An answer of [listener], the job listening to [chat]'s latest message. */
  class Latest(val chat: ChatId, val listener: Job, val result: LatestMessageResult) : Update
}

/** What [ChatsRepository.observeMyChats] knows so far, and the result it shows for it. */
private class MyChats {
  /** The last groups answer, or null before the first. */
  private var myGroups: MyGroupsResult.Success? = null

  /** The last latest message of each listed chat that has answered. */
  private val answers = mutableMapOf<ChatId, LatestMessageResult.Success>()

  /** Listed chats whose latest message was denied, until the groups drop or confirm them. */
  private val denied = mutableSetOf<ChatId>()

  /** The failure that ends the list, once there is one. */
  private var failure: MyChatsResult? = null

  /** Whether a list was shown: until then, every listed chat's latest message is waited for. */
  private var shown = false

  /** The chats to listen to: those of the last groups answer, none once the list failed. */
  val ids: Set<ChatId>
    get() =
        if (failure == null) myGroups?.groups.orEmpty().mapTo(HashSet()) { it.chat } else emptySet()

  fun groups(result: MyGroupsResult) {
    when (result) {
      MyGroupsResult.Loading -> Unit
      MyGroupsResult.NetworkError -> failure = MyChatsResult.NetworkError
      MyGroupsResult.UnexpectedError -> failure = MyChatsResult.UnexpectedError
      is MyGroupsResult.Success -> {
        myGroups = result
        val listed = result.groups.mapTo(HashSet()) { it.chat }
        answers.keys.retainAll(listed)
        denied.retainAll(listed)
        // The server still lists the student in a group whose chat denied them after that.
        if (!result.fromCache && denied.isNotEmpty()) failure = MyChatsResult.UnexpectedError
      }
    }
  }

  fun latest(chat: ChatId, result: LatestMessageResult) {
    when (result) {
      LatestMessageResult.Loading -> Unit
      is LatestMessageResult.Success -> answers[chat] = result
      LatestMessageResult.NetworkError -> failure = MyChatsResult.NetworkError
      LatestMessageResult.UnexpectedError -> denied += chat
    }
  }

  /** The result to show, or null while there is none yet. */
  fun result(): MyChatsResult? {
    failure?.let {
      return it
    }
    val current = myGroups ?: return null
    val readable = current.groups.filter { it.chat !in denied }
    if (!shown && readable.any { it.chat !in answers }) return null
    shown = true
    val chats = readable.mapNotNull { group -> answers[group.chat]?.let { group to it } }
    return MyChatsResult.Success(
        chats =
            chats
                .map { (group, latest) -> ChatPreview(group, latest.message) }
                .sortedWith(NEWEST_ACTIVITY_FIRST),
        fromCache = current.fromCache || chats.any { (_, latest) -> latest.fromCache },
    )
  }
}

/**
 * The order of [MyChatsResult.Success.chats]. A group the server hasn't timestamped yet counts as
 * the newest.
 */
private val NEWEST_ACTIVITY_FIRST: Comparator<ChatPreview> =
    compareByDescending<ChatPreview, Instant?>(nullsLast()) {
          it.latestMessage?.createdAt ?: it.group.createdAt
        }
        .thenBy { it.group.eventId }
        .thenBy { it.group.id }
