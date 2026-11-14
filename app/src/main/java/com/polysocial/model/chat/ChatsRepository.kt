// Contributors: Claude (Fable 5.2, Anthropic) (drafted the chat list read, #363).
package com.polysocial.model.chat

import com.polysocial.model.group.Group
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.MyGroupsResult
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile

/**
 * A chat as a chat list shows it: a group the signed-in student is in, with its latest message.
 *
 * @property latestMessage the group's newest message, or null while it has none.
 */
data class ChatPreview(val group: Group, val latestMessage: Message?)

/** What [ChatsRepository.observeMyChats] emits. */
sealed interface MyChatsResult {
  /**
   * The groups, or the latest message of one of them, haven't been read yet. Emitted first, unless
   * a newer result already replaced it.
   */
  data object Loading : MyChatsResult

  /**
   * The signed-in student's chats, one per group they are in, across events; empty when they are in
   * none. Newest activity first: by the latest message's time, or by the group's creation time
   * while it has no message, then by event and group ID so the order is stable.
   *
   * @property fromCache true when the groups or any latest message come from Firestore's offline
   *   cache, for example while the device is offline. They may then be out of date.
   */
  data class Success(val chats: List<ChatPreview>, val fromCache: Boolean) : MyChatsResult

  /** The chats couldn't be read because the server can't be reached. The flow then ends. */
  data object NetworkError : MyChatsResult

  /**
   * Any other failure, for example nobody signed in, or an association, whose reads the Security
   * Rules deny. The flow then ends.
   */
  data object UnexpectedError : MyChatsResult
}

/**
 * The signed-in student's chats, for the Chats tab (#364): every group they are in, across events
 * ([GroupRepository.observeMyGroups]), each with its latest message
 * ([MessageRepository.observeLatestMessage], one document per group). A one-to-one chat is a
 * two-member group, so it is listed the same way.
 *
 * It reads nothing a member couldn't already read: the groups come from the student's own
 * collection-group query, and each latest message is read under its group's chat rule. The event's
 * name and the members' names aren't here: read them from their own repositories, by ID.
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
   * members changing, a new message. Offline, everything comes from the cache (`fromCache`).
   * Firestore can stay silent while it looks for the server, so a screen waiting for the first list
   * needs its own timeout. A slow collector gets only the latest result.
   *
   * It ends after a [MyChatsResult.NetworkError] or [MyChatsResult.UnexpectedError], which is
   * always delivered: when the groups can't be read, or the latest message of any of them. A member
   * can always read their group's chat, so a message that can't be read fails the list instead of
   * showing that chat as empty. Cancelling the collection stops every listener. The student is the
   * one signed in when collection starts, so a new account needs a new collection.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun observeMyChats(): Flow<MyChatsResult> = channelFlow {
    // Nothing below emits before the groups and their latest messages have both answered.
    send(MyChatsResult.Loading)
    // Shared, so the groups are listened to once: for the list, and for the chats to read.
    val myGroups = groups.observeMyGroups().shareIn(this, SharingStarted.Lazily, replay = 1)
    // The latest messages are listened to again only when the student's chats change, not each
    // time the groups emit: a member joining, or just their cache state when the device goes
    // offline, must not drop a listener, whose messages an in-memory cache then forgets.
    val latestMessages =
        myGroups
            .map { result -> (result as? MyGroupsResult.Success)?.groups.orEmpty().map { it.chat } }
            .distinctUntilChanged()
            .flatMapLatest { chats -> latestMessagesOf(chats) }
    combine(myGroups, latestMessages) { result, latest -> chats(result, latest) }
        .collect { result -> if (result != null) send(result) }
  }
      .transformWhile { result ->
        emit(result)
        result is MyChatsResult.Loading || result is MyChatsResult.Success
      }
      .conflate()
      .distinctUntilChanged()

  /** The latest message of each of [chats], once all have answered and on every change. */
  private fun latestMessagesOf(chats: List<ChatId>): Flow<Map<ChatId, LatestMessageResult>> {
    // combine() never emits without a flow to combine.
    if (chats.isEmpty()) return flowOf(emptyMap())
    return combine(
        chats.map { chat ->
          messages
              .observeLatestMessage(chat.eventId, chat.groupId)
              .filter { it != LatestMessageResult.Loading }
              .map { chat to it }
        }
    ) {
      it.toMap()
    }
  }
}

/** A chat's group: a group ID is unique only within its event. */
private data class ChatId(val eventId: String, val groupId: String)

private val Group.chat: ChatId
  get() = ChatId(eventId, id)

/**
 * The chats of [myGroups] with their [latestMessages], or the failure of either read. Null while a
 * group's latest message is missing: right after the groups changed, [latestMessages] still holds
 * the previous chats' answers, which also means a chat the student left can't fail the list.
 */
private fun chats(
    myGroups: MyGroupsResult,
    latestMessages: Map<ChatId, LatestMessageResult>,
): MyChatsResult? =
    when (myGroups) {
      MyGroupsResult.Loading -> MyChatsResult.Loading
      MyGroupsResult.NetworkError -> MyChatsResult.NetworkError
      MyGroupsResult.UnexpectedError -> MyChatsResult.UnexpectedError
      is MyGroupsResult.Success -> {
        val latest = myGroups.groups.map { latestMessages[it.chat] }
        val read = latest.filterIsInstance<LatestMessageResult.Success>()
        when {
          LatestMessageResult.NetworkError in latest -> MyChatsResult.NetworkError
          LatestMessageResult.UnexpectedError in latest -> MyChatsResult.UnexpectedError
          read.size < latest.size -> null
          else ->
              MyChatsResult.Success(
                  chats =
                      myGroups.groups
                          .zip(read) { group, answer -> ChatPreview(group, answer.message) }
                          .sortedWith(NEWEST_ACTIVITY_FIRST),
                  fromCache = myGroups.fromCache || read.any { it.fromCache },
              )
        }
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
