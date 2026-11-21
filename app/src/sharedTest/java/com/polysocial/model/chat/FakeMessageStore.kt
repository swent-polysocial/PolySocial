// Contributors: Claude (Opus 5.5, Anthropic) (moved out of FakeMessageRepository, #237 and #238,
// so the one-to-one chat fake shares it, #428).
package com.polysocial.model.chat

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The stored messages of the in-memory chats of a chat fake ([FakeMessageRepository],
 * [FakeOneToOneChatRepository]), each chat identified by a [K] of that fake: what the server holds
 * once a send got through the fake's checks.
 *
 * Messages are listed oldest first, then by ID for equal times, as Firestore orders them. Messages
 * [add] stores get the IDs `message-1`, `message-2`… and deterministic server times that put them
 * last, as the server's time would: n seconds after 2030-01-01 12:00 UTC for the n-th, or 1 s after
 * the chat's latest message when that is later.
 */
class FakeMessageStore<K> {
  private val messages = MutableStateFlow<Map<K, List<Message>>>(emptyMap())

  private var sent = 0

  /** The stored messages of [chat], oldest first. */
  fun messagesOf(chat: K): List<Message> = messages.value[chat].orEmpty()

  /** The messages of [chat], oldest first, each time any chat's messages change. */
  fun observe(chat: K): Flow<List<Message>> = messages.map { it[chat].orEmpty() }

  /** Stores [message] in [chat] directly, in its place by time. */
  fun store(chat: K, message: Message) {
    val stored = messagesOf(chat) + message
    messages.value +=
        chat to stored.sortedWith(compareBy<Message> { it.createdAt }.thenBy { it.id })
  }

  /**
   * What the send transaction answers when [chat] already holds a message under [messageId]:
   * [SendMessageResult.Sent] when it has the same [senderId] and [text], so a retry of a stored
   * draft adds nothing, and [SendMessageResult.UnexpectedError] otherwise, without changing it.
   * Null when [messageId] is null or holds nothing, so the message is new.
   */
  fun retryOf(chat: K, senderId: String, text: String, messageId: String?): SendMessageResult? {
    val previous = messagesOf(chat).firstOrNull { it.id == messageId } ?: return null
    return if (previous.senderId == senderId && previous.text == text) SendMessageResult.Sent
    else SendMessageResult.UnexpectedError
  }

  /**
   * Stores a new message [senderId] sent with [text] in [chat], as the newest at the server's time,
   * under [messageId] or, when it is null, the next generated ID. The caller checked everything
   * else first, [retryOf] included.
   */
  fun add(chat: K, senderId: String, text: String, messageId: String?) {
    sent++
    // Like the server's time, after every message already stored in the chat.
    val afterLatest = messagesOf(chat).lastOrNull()?.createdAt?.plusSeconds(1)
    val createdAt = listOfNotNull(FIRST_SENT_AT.plusSeconds(sent.toLong()), afterLatest).max()
    store(chat, Message(messageId ?: "message-$sent", senderId, text, createdAt))
  }

  private companion object {
    val FIRST_SENT_AT: Instant = Instant.parse("2030-01-01T12:00:00Z")
  }
}
