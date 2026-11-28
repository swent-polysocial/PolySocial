// Contributors: Claude (Opus 5.5, Anthropic) (wrote this fake for tests, #428; unsend, #435).
package com.polysocial.model.chat

import com.polysocial.model.event.isReadableEventId
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.isFriendshipIdOf
import com.polysocial.model.friend.uidsOfFriendshipId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * In-memory [OneToOneChatRepository] for tests. It follows the [OneToOneChatRepository] contracts
 * and checks [sendMessage] in the order the Firestore repository must: the text, the IDs and the
 * signed-in user, then [online], then [sendFailure], then the stored message under a retry ID, then
 * the friendship, as the Security Rules check the write. [unsendMessage] checks in the same order,
 * with [unsendFailure], then the stored message, then the friendship.
 *
 * Like the Security Rules, only the two students of a pair ID read its chat, whether or not it
 * exists and whether or not they are friends, and only while their friendship (see [friendshipOf])
 * is accepted can either send or unsend their own message; the first message creates the chat. Each call checks and writes in
 * one step, so two first messages never race here. Unlike the Security Rules, it doesn't check
 * deletion markers or account types.
 *
 * The observe flows read [currentUid] and [readFailure] when their collection starts, as the real
 * listeners read the user and are checked by the rules then, and keep emitting for that user:
 * changing [currentUid] later doesn't affect a running collection. They emit when their own data
 * changes and when [readFromCache] does. [observeMyChats] lists the chats by pair ID, so that tests
 * are deterministic, but that order isn't part of the contract (no particular order): a test must
 * not depend on it. The messages are kept in a [FakeMessageStore], which sets their order, IDs and
 * server times. Each fake has its own store, whose server times start at the same instant, so a
 * test of a list merging group and one-to-one chats newest first seeds their messages with explicit
 * times ([seedMessage]) rather than sending them.
 *
 * @property currentUid the signed-in student, or null when nobody is signed in.
 */
class FakeOneToOneChatRepository(var currentUid: String? = "test-student") :
    OneToOneChatRepository {
  /** False simulates an offline device: sending returns [SendMessageResult.NetworkError]. */
  var online = true

  /** When set, sending returns this result instead of writing, like a failed transaction. */
  var sendFailure: SendMessageResult? = null

  /** When set, unsending returns this result instead of writing, like a failed transaction. */
  var unsendFailure: UnsendMessageResult? = null

  /**
   * When set, the observe flows emit Loading, then this failure and end, like a failed listener:
   * the same two failures as [FakeMessageRepository]'s listeners.
   */
  var readFailure: FakeMessageRepository.ReadFailure? = null

  /**
   * The pair's friendship, as the Security Rules read `friendships/{pairId}`: sending needs one
   * that is [FriendshipStatus.ACCEPTED]. None by default. Wire it to a `FakeFriendRepository`, as
   * `friendshipOf = friends::friendship`, so that accepting or removing a friend there changes what
   * can be sent here; tests make two students friends with its `seedAcceptedFriendship`.
   */
  var friendshipOf: (pairId: String) -> Friendship? = { null }

  private val fromCache = MutableStateFlow(false)

  /**
   * The `fromCache` flag the observe flows report, to simulate being offline. Changing it emits a
   * new result, like a listener that includes metadata changes.
   */
  var readFromCache: Boolean
    get() = fromCache.value
    set(value) {
      fromCache.value = value
    }

  /** The stored chats' `uids`, by pair ID. */
  private val chats = MutableStateFlow<Map<String, List<String>>>(emptyMap())

  private val messages = FakeMessageStore<String>()

  /**
   * Stores the chat [pairId] directly with [uids], by default the pair's two UIDs, replacing a
   * stored one, like a chat another student started. [uids] without the other student is a chat
   * whose other account was deleted. [pairId] must be a pair ID and [uids] one or both of its UIDs.
   */
  fun seedChat(pairId: String, uids: List<String> = uidsOfFriendshipId(pairId).orEmpty()) {
    require(uids.isNotEmpty() && uids.all { isFriendshipIdOf(pairId, it) }) {
      "A chat holds one or both students of its pair ID"
    }
    chats.value += pairId to uids
  }

  /** The stored `uids` of the chat [pairId], or null when it doesn't exist. */
  fun uidsOf(pairId: String): List<String>? = chats.value[pairId]

  /**
   * Stores a message directly in the chat [pairId], like one a student sent at [createdAt], and
   * returns it. It doesn't create the chat: [seedChat] does.
   */
  fun seedMessage(
      pairId: String,
      id: String,
      senderId: String,
      text: String,
      createdAt: Instant,
  ): Message {
    val message = Message(id, senderId, text, createdAt)
    messages.store(pairId, message)
    return message
  }

  /** The stored messages of the chat [pairId], oldest first. */
  fun messagesOf(pairId: String): List<Message> = messages.messagesOf(pairId)

  override fun observeMyChats(): Flow<MyOneToOneChatsResult> = flow {
    emit(MyOneToOneChatsResult.Loading)
    val failure = readFailure
    val uid = currentUid
    when {
      failure != null ->
          emit(
              if (failure == FakeMessageRepository.ReadFailure.NETWORK)
                  MyOneToOneChatsResult.NetworkError
              else MyOneToOneChatsResult.UnexpectedError
          )
      uid == null -> emit(MyOneToOneChatsResult.UnexpectedError)
      else ->
          emitAll(
              combine(chats, fromCache) { stored, cached ->
                    val mine = stored.filterValues { uid in it }.toSortedMap()
                    MyOneToOneChatsResult.Success(
                        mine.map { (id, uids) -> OneToOneChat(id, uids.firstOrNull { it != uid }) },
                        cached,
                    )
                  }
                  .distinctUntilChanged()
          )
    }
  }

  override fun observeMessages(pairId: String): Flow<MessagesResult> = flow {
    emit(MessagesResult.Loading)
    val failure = readFailure
    val uid = currentUid
    when {
      failure != null ->
          emit(
              if (failure == FakeMessageRepository.ReadFailure.NETWORK) MessagesResult.NetworkError
              else MessagesResult.UnexpectedError
          )
      uid == null || !isFriendshipIdOf(pairId, uid) -> emit(MessagesResult.UnexpectedError)
      else ->
          emitAll(
              combine(messages.observe(pairId), fromCache) { stored, cached ->
                    MessagesResult.Success(stored, cached)
                  }
                  .distinctUntilChanged()
          )
    }
  }

  override fun observeLatestMessage(pairId: String): Flow<LatestMessageResult> =
      observeMessages(pairId).map { it.toLatestMessage() }.distinctUntilChanged()

  override suspend fun sendMessage(
      pairId: String,
      text: String,
      messageId: String?,
  ): SendMessageResult {
    validateMessageText(text)?.let {
      return SendMessageResult.Invalid(it)
    }
    val uid = currentUid
    if (
        uid == null ||
            !isFriendshipIdOf(pairId, uid) ||
            (messageId != null && !isReadableEventId(messageId))
    ) {
      return SendMessageResult.UnexpectedError
    }
    if (!online) return SendMessageResult.NetworkError
    sendFailure?.let {
      return it
    }
    val trimmed = text.trim()
    // The transaction reads the message first: a retry of a stored draft writes nothing, so the
    // rules' friendship check never sees it.
    messages.retryOf(pairId, uid, trimmed, messageId)?.let {
      return it
    }
    if (friendshipOf(pairId)?.status != FriendshipStatus.ACCEPTED) {
      return SendMessageResult.UnexpectedError
    }
    if (pairId !in chats.value) seedChat(pairId)
    messages.add(pairId, uid, trimmed, messageId)
    return SendMessageResult.Sent
  }

  override suspend fun unsendMessage(pairId: String, messageId: String): UnsendMessageResult {
    val uid = currentUid
    if (uid == null || !isFriendshipIdOf(pairId, uid) || !isReadableEventId(messageId)) {
      return UnsendMessageResult.UnexpectedError
    }
    if (!online) return UnsendMessageResult.NetworkError
    unsendFailure?.let {
      return it
    }
    // The transaction reads the message first: one already unsent is answered without a write,
    // so the rules' friendship check never sees it.
    val stored =
        messages.messagesOf(pairId).firstOrNull { it.id == messageId }
            ?: return UnsendMessageResult.UnexpectedError
    if (stored.senderId != uid) return UnsendMessageResult.UnexpectedError
    if (stored.isUnsent) return UnsendMessageResult.Unsent
    if (friendshipOf(pairId)?.status != FriendshipStatus.ACCEPTED) {
      return UnsendMessageResult.UnexpectedError
    }
    messages.unsend(pairId, messageId)
    return UnsendMessageResult.Unsent
  }
}
