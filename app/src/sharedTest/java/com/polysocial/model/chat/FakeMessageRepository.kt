// Contributors: Claude (Opus 5.5, Anthropic) (wrote this fake for tests, #237).
// Contributors: OpenAI Codex (modeled same-ID retries in the chat fake, #238).
// Contributors: Claude (Fable 5.2, Anthropic) (a group's latest message, #363).
// Contributors: Claude (Opus 5.5, Anthropic) (members read from the groups, after review, #363).
// Contributors: Claude (Opus 5.5, Anthropic) (stored messages moved to FakeMessageStore, #428).
package com.polysocial.model.chat

import com.polysocial.model.event.isReadableEventId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * In-memory [MessageRepository] for tests. It follows the [MessageRepository] contracts and checks
 * in the same order as `FirestoreMessageRepository`: the text, the IDs, the signed-in user, then
 * [online], then [sendFailure], then membership.
 *
 * Like the Security Rules, only a group's members (see [setMembers] and [membersOf]) read and send
 * its messages: anyone else's [observeMessages] ends with [MessagesResult.UnexpectedError], and
 * their [sendMessage] returns [SendMessageResult.UnexpectedError]. [observeMessages] reads
 * [currentUid], [readFailure] and the members when its collection starts, as the real listener
 * reads the user and is checked by the rules then, and keeps emitting for that user: changing
 * [currentUid] later doesn't affect a running collection, so a ViewModel test can't simulate an
 * account change with it. [observeLatestMessage] follows the same checks and emits the last of
 * those messages, with its own results.
 *
 * The messages are kept in a [FakeMessageStore], which sets their order, IDs and server times.
 * Each fake has its own store, whose server times start at the same instant, so a test of a list
 * merging group and one-to-one chats newest first seeds their messages with explicit times
 * ([seedMessage]) rather than sending them.
 * [observeMessages] emits only when its own group changes.
 *
 * @property currentUid the signed-in student, or null when nobody is signed in.
 */
class FakeMessageRepository(var currentUid: String? = "test-student") : MessageRepository {
  /** False simulates an offline device: sending returns [SendMessageResult.NetworkError]. */
  var online = true

  /** When set, sending returns this result instead of writing, like a failed transaction. */
  var sendFailure: SendMessageResult? = null

  /** When set, the observe flows emit Loading, then this failure and end, like a failed listener. */
  var readFailure: ReadFailure? = null

  /** The `fromCache` flag the observe flows report, to simulate reading from the offline cache. */
  var readFromCache = false

  /** The two ways a listener can fail. */
  enum class ReadFailure {
    NETWORK,
    UNEXPECTED,
  }

  /** A group of an event. */
  private data class GroupKey(val eventId: String, val groupId: String)

  private val members = mutableMapOf<GroupKey, Set<String>>()

  private val messages = FakeMessageStore<GroupKey>()

  /**
   * Where the members of a group that [setMembers] never named come from, or null (the default) for
   * nobody. The rules read a chat's members from its group, so a test that also uses a
   * `FakeGroupRepository` can wire it to that group: `membersOf = groups::memberUidsOf`. Without
   * it, a group created, joined or seeded there has no members here until [setMembers] names them,
   * so its chat is denied.
   */
  var membersOf: ((eventId: String, groupId: String) -> Collection<String>?)? = null

  /**
   * Makes [memberUids] the members of the group [groupId] of [eventId], replacing any others, also
   * those [membersOf] gives: an empty set denies the chat even to the group's members.
   */
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
    messages.store(GroupKey(eventId, groupId), message)
    return message
  }

  /** The stored messages of the group [groupId] of [eventId], oldest first. */
  fun messagesOf(eventId: String, groupId: String): List<Message> =
      messages.messagesOf(GroupKey(eventId, groupId))

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
                  .observe(key)
                  .map { MessagesResult.Success(it, readFromCache) }
                  .distinctUntilChanged()
          )
    }
  }

  override fun observeLatestMessage(eventId: String, groupId: String): Flow<LatestMessageResult> =
      observeMessages(eventId, groupId).map { it.toLatestMessage() }.distinctUntilChanged()

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
    messages.retryOf(key, uid, trimmed, messageId)?.let {
      return it
    }
    messages.add(key, uid, trimmed, messageId)
    return SendMessageResult.Sent
  }

  private fun isMember(key: GroupKey, uid: String?) =
      uid != null &&
          isReadableEventId(key.eventId) &&
          isReadableEventId(key.groupId) &&
          uid in (members[key] ?: membersOf?.invoke(key.eventId, key.groupId)).orEmpty()
}
