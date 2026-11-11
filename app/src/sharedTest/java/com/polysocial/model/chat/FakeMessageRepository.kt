// Contributors: Claude (Opus 5.5, Anthropic) (wrote this fake for tests, #237).
// Contributors: OpenAI Codex (modeled same-ID retries in the chat fake, #238).
package com.polysocial.model.chat

import com.polysocial.model.event.isReadableEventId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * In-memory [MessageRepository] for tests. It follows the [MessageRepository] contracts and checks
 * in the same order as `FirestoreMessageRepository`: the text, the IDs, the signed-in user, then
 * [online], then [sendFailure], then membership.
 *
 * Like the Security Rules, only a group's members (see [setMembers]) read and send its messages:
 * anyone else's [observeMessages] ends with [MessagesResult.UnexpectedError], and their
 * [sendMessage] returns [SendMessageResult.UnexpectedError]. [observeMessages] reads [currentUid],
 * [readFailure] and the members when its collection starts, as the real listener reads the user
 * and is checked by the rules then.
 *
 * Messages are listed oldest first, then by ID for equal times, as Firestore orders them. Sent
 * messages get the IDs `message-1`, `message-2`… and deterministic server times that put them
 * last, as the server's time would: n seconds after 2030-01-01 12:00 UTC for the n-th, or 1 s
 * after the group's latest message when that is later. [observeMessages] emits only when its own
 * group changes.
 *
 * @property currentUid the signed-in student, or null when nobody is signed in.
 */
class FakeMessageRepository(var currentUid: String? = "test-student") : MessageRepository {
  /** False simulates an offline device: sending returns [SendMessageResult.NetworkError]. */
  var online = true

  /** When set, sending returns this result instead of writing, like a failed transaction. */
  var sendFailure: SendMessageResult? = null

  /** When set, [observeMessages] emits Loading, then this failure and ends, like a failed listener. */
  var readFailure: ReadFailure? = null

  /** The `fromCache` flag [observeMessages] reports, to simulate reading from the offline cache. */
  var readFromCache = false

  /** The two ways a listener can fail. */
  enum class ReadFailure {
    NETWORK,
    UNEXPECTED,
  }

  /** A group of an event. */
  private data class GroupKey(val eventId: String, val groupId: String)

  private val members = mutableMapOf<GroupKey, Set<String>>()

  private val messages = MutableStateFlow<Map<GroupKey, List<Message>>>(emptyMap())

  private var sent = 0

  /** Makes [memberUids] the members of the group [groupId] of [eventId], replacing any others. */
  fun setMembers(eventId: String, groupId: String, memberUids: Set<String>) {
    members[GroupKey(eventId, groupId)] = memberUids
  }

  /**
   * Stores a message directly, like one another member sent at [createdAt], and returns it. Its ID
   * is [id], so a test can control the order of messages stored at the same time.
   */
  fun seedMessage(
      eventId: String,
      groupId: String,
      id: String,
      senderId: String,
      text: String,
      createdAt: Instant,
  ): Message {
    val message = Message(id, senderId, text, createdAt)
    store(GroupKey(eventId, groupId), message)
    return message
  }

  /** The stored messages of the group [groupId] of [eventId], oldest first. */
  fun messagesOf(eventId: String, groupId: String): List<Message> =
      messages.value[GroupKey(eventId, groupId)].orEmpty()

  override fun observeMessages(eventId: String, groupId: String): Flow<MessagesResult> = flow {
    emit(MessagesResult.Loading)
    val failure = readFailure
    val key = GroupKey(eventId, groupId)
    when {
      failure != null ->
          emit(
              if (failure == ReadFailure.NETWORK) MessagesResult.NetworkError
              else MessagesResult.UnexpectedError
          )
      !isMember(key, currentUid) -> emit(MessagesResult.UnexpectedError)
      else ->
          emitAll(
              messages
                  .map { MessagesResult.Success(it[key].orEmpty(), readFromCache) }
                  .distinctUntilChanged()
          )
    }
  }

  override suspend fun sendMessage(eventId: String, groupId: String, text: String): SendMessageResult =
      sendMessage(eventId, groupId, text, messageId = null)

  override suspend fun sendMessage(
      eventId: String,
      groupId: String,
      text: String,
      messageId: String?,
  ): SendMessageResult {
    validateMessageText(text)?.let {
      return SendMessageResult.Invalid(it)
    }
    if (!listOfNotNull(eventId, groupId, messageId).all(::isReadableEventId)) {
      return SendMessageResult.UnexpectedError
    }
    val uid = currentUid ?: return SendMessageResult.UnexpectedError
    if (!online) return SendMessageResult.NetworkError
    sendFailure?.let {
      return it
    }
    val key = GroupKey(eventId, groupId)
    if (!isMember(key, uid)) return SendMessageResult.UnexpectedError
    val trimmed = text.trim()
    val previous = messagesOf(eventId, groupId).firstOrNull { it.id == messageId }
    if (previous != null) {
      return if (previous.senderId == uid && previous.text == trimmed) SendMessageResult.Sent
      else SendMessageResult.UnexpectedError
    }
    sent++
    // Like the server's time, after every message already stored in the group.
    val afterLatest = messagesOf(eventId, groupId).lastOrNull()?.createdAt?.plusSeconds(1)
    val createdAt = listOfNotNull(FIRST_SENT_AT.plusSeconds(sent.toLong()), afterLatest).max()
    store(key, Message(messageId ?: "message-$sent", uid, trimmed, createdAt))
    return SendMessageResult.Sent
  }

  private fun isMember(key: GroupKey, uid: String?) =
      uid != null &&
          isReadableEventId(key.eventId) &&
          isReadableEventId(key.groupId) &&
          uid in members[key].orEmpty()

  private fun store(key: GroupKey, message: Message) {
    val stored = messagesOf(key.eventId, key.groupId) + message
    messages.value += key to stored.sortedWith(compareBy<Message> { it.createdAt }.thenBy { it.id })
  }

  private companion object {
    val FIRST_SENT_AT: Instant = Instant.parse("2030-01-01T12:00:00Z")
  }
}
