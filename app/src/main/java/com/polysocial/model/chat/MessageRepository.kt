// Contributors: Claude (Opus 5.5, Anthropic) (drafted the group chat repository contract, #237).
// Contributors: OpenAI Codex (added the retry ID and removed its legacy Kotlin bridge, #238).
package com.polysocial.model.chat

import kotlinx.coroutines.flow.Flow

/** What [MessageRepository.observeMessages] emits. */
sealed interface MessagesResult {
  /** Nothing has been read yet. Emitted first, unless a newer result already replaced it. */
  data object Loading : MessagesResult

  /**
   * The group's messages, oldest first by the server's time. A stored message that can't be read (a
   * missing or wrong-typed field) is skipped.
   *
   * @property fromCache true when the messages come from Firestore's offline cache, for example
   *   while the device is offline. They may then be out of date.
   */
  data class Success(val messages: List<Message>, val fromCache: Boolean) : MessagesResult

  /** The messages couldn't be read because the server can't be reached. The flow then ends. */
  data object NetworkError : MessagesResult

  /**
   * Any other failure, for example nobody signed in, or a student who isn't a member of the group.
   * A student who leaves while listening gets it only at the chat's next change (see
   * [MessageRepository.observeMessages]). The flow then ends.
   */
  data object UnexpectedError : MessagesResult
}

/** Outcome of [MessageRepository.sendMessage]. */
sealed interface SendMessageResult {
  /** The server stored the message or had already stored this draft under the same ID. */
  data object Sent : SendMessageResult

  /** The text can't be sent (see [validateMessageText]). Nothing was written. */
  data class Invalid(val error: MessageTextError) : SendMessageResult

  /**
   * The server could not confirm the write. Nothing was queued. Retrying the same draft with its
   * original message ID can't duplicate the message.
   */
  data object NetworkError : SendMessageResult

  /**
   * Any other failure, for example nobody signed in, or a write the Security Rules deny because the
   * student isn't a member of the group. Nothing was written.
   */
  data object UnexpectedError : SendMessageResult
}

/**
 * Stores a group's chat (#237) in `events/{eventId}/groups/{groupId}/messages/{id}`: each message
 * has its sender's UID, its text and the server's time.
 *
 * Only the group's members read and send, each message as themselves, and nobody edits or deletes
 * one; associations never reach messages. The Security Rules enforce it, so a non-member's read or
 * send fails with an `UnexpectedError`.
 *
 * All callers compile with the app, so the default argument needs only the interface's JVM
 * implementation.
 */
@JvmDefaultWithoutCompatibility
interface MessageRepository {
  /**
   * Observes the messages of the group [groupId] of the event [eventId], oldest first by the
   * server's time.
   *
   * The flow emits [MessagesResult.Loading], then on every change, including from the offline
   * cache. A slow collector gets only the latest result. It ends after a
   * [MessagesResult.NetworkError] or [MessagesResult.UnexpectedError], which is always delivered.
   * Cancelling the collection stops listening. The user is read when collection starts.
   *
   * Leaving the group doesn't end the flow at once: Firestore revokes the listener only when the
   * chat next changes (a new message), and then the flow ends with [MessagesResult.UnexpectedError]
   * without that message. To notice that the student left, observe their group instead
   * (`GroupRepository.observeMyGroup`).
   */
  fun observeMessages(eventId: String, groupId: String): Flow<MessagesResult>

  /**
   * Sends [text] to the group [groupId] of the event [eventId] as the signed-in student.
   *
   * The text is trimmed first (leading and trailing whitespace, line breaks included) and stored
   * trimmed. Before anything is written, it checks, in this order: the trimmed text
   * ([SendMessageResult.Invalid] when blank or too long, see [validateMessageText]), the IDs and
   * the signed-in user ([SendMessageResult.UnexpectedError]), and the connection
   * ([SendMessageResult.NetworkError] offline). Offline, the message is refused rather than queued
   * (#228): Firestore would otherwise keep the write until the device is back online, and a chat
   * waiting for it would never finish sending.
   *
   * For a retryable send, generate a Firestore document ID such as `UUID.randomUUID().toString()`
   * for each new draft. Reuse it on Try again while the trimmed text is unchanged. If the student
   * changes that text, use a new ID. If the original send already reached the server, its message
   * remains in the chat. An existing ID returns [SendMessageResult.Sent] only if it holds the same
   * sender and trimmed text, and [SendMessageResult.UnexpectedError] otherwise.
   *
   * A null [messageId] gets a fresh repository-generated ID on every call, so such calls cannot
   * deduplicate a retry.
   */
  suspend fun sendMessage(
      eventId: String,
      groupId: String,
      text: String,
      messageId: String? = null,
  ): SendMessageResult
}
