// Contributors: Claude (Opus 5.5, Anthropic) (drafted the one-to-one chat contract, #428).
// Contributors: Claude (Opus 5.5, Anthropic) (unsending a message, #435).
package com.polysocial.model.chat

import kotlinx.coroutines.flow.Flow

/**
 * A chat between two friends (#365), `chats/{id}`, as the signed-in student sees it.
 *
 * @property id the pair ID, the same as the two students' friendship's (`friendshipId`):
 *   `{lowerUid}~{higherUid}`. It keeps both UIDs, also once one account was deleted.
 * @property otherUid the other student's UID, or null once their account was deleted:
 *   `deleteAccount` then takes it out of the chat's `uids`, and the chat stays for the signed-in
 *   student, read-only. Show "Deleted account" for null, never for a public profile that can't be
 *   read (#291).
 */
data class OneToOneChat(val id: String, val otherUid: String?)

/** What [OneToOneChatRepository.observeMyChats] emits. */
sealed interface MyOneToOneChatsResult {
  /** Nothing has been read yet. Emitted first, unless a newer result already replaced it. */
  data object Loading : MyOneToOneChatsResult

  /**
   * Every one-to-one chat of the signed-in student, in no particular order; empty when they have
   * none. A stored chat that can't be read (a missing or wrong-typed `uids`) is skipped.
   *
   * @property fromCache true when they come from Firestore's offline cache, for example while the
   *   device is offline. They may then be out of date.
   */
  data class Success(val chats: List<OneToOneChat>, val fromCache: Boolean) : MyOneToOneChatsResult

  /** The chats couldn't be read because the server can't be reached. The flow then ends. */
  data object NetworkError : MyOneToOneChatsResult

  /**
   * Any other failure, for example nobody signed in, or an association, whose read the Security
   * Rules deny. The flow then ends.
   */
  data object UnexpectedError : MyOneToOneChatsResult
}

/**
 * Stores chats between two friends (#365): `chats/{pairId}`, under the pair's friendship ID
 * (`friendshipId`), holding exactly `uids` (the two UIDs, in the ID's order), and its messages in
 * `chats/{pairId}/messages/{id}` with a group chat's fields (see [Message]).
 *
 * Only the two students read a chat, friends or not, so a conversation stays readable after a
 * friend is removed. Only while they are friends (their friendship is accepted) can either start
 * the chat, which its first message creates, send, or unsend one of their own messages (its text
 * emptied, [unsendMessage]). Nobody otherwise edits or deletes a chat or a message, and
 * associations never reach them. The Security Rules enforce it, so a refused access is an
 * `UnexpectedError`. Writes are refused offline with a `NetworkError` rather than queued.
 *
 * When one of the two deletes their account, `deleteAccount` takes their UID out of the chat's
 * `uids` ([OneToOneChat.otherUid] becomes null) and empties their messages' `senderId`
 * ([DELETED_MESSAGE_SENDER]): the other student keeps the conversation, read-only.
 *
 * Every chat is named by its pair ID, which unsending (#435) and delete for me (#437) take too.
 *
 * All callers compile with the app, so the default argument needs only the interface's JVM
 * implementation, as in [MessageRepository].
 */
@JvmDefaultWithoutCompatibility
interface OneToOneChatRepository {
  /**
   * Observes the signed-in student's one-to-one chats: every chat whose `uids` contains their UID,
   * the only list of chats the Security Rules allow, each with the other student's UID, or null
   * once that account was deleted ([OneToOneChat.otherUid]). Read-only chats are listed too (a
   * friend removed, a new request still pending, the other account deleted), and so is a chat
   * without a message, which the app never creates: the Chats tab lists only the chats that have
   * one (see [observeLatestMessage]) and orders them by it.
   *
   * The flow emits [MyOneToOneChatsResult.Loading], then on every change, including from the
   * offline cache and when only [MyOneToOneChatsResult.Success.fromCache] changes. A slow collector
   * gets only the latest result. It ends after a [MyOneToOneChatsResult.NetworkError] or
   * [MyOneToOneChatsResult.UnexpectedError], which is always delivered. Cancelling the collection
   * stops listening. The user is read when collection starts, so a new account needs a new
   * collection.
   */
  fun observeMyChats(): Flow<MyOneToOneChatsResult>

  /**
   * Observes the messages of the chat [pairId], oldest first by the server's time, with the
   * results, emissions and end of [MessageRepository.observeMessages].
   *
   * [pairId] must be the pair ID of the signed-in student and another student (`friendshipId`); any
   * other ID ends the flow with [MessagesResult.UnexpectedError] before anything is read. A chat
   * that doesn't exist yet has no messages: an empty [MessagesResult.Success], so a chat opened
   * from the friends list shows an empty history until its first message.
   *
   * Reading doesn't depend on the friendship, so removing a friend doesn't end the flow: to know
   * whether the student can send, observe the friendship (`FriendRepository.observeFriendships`).
   */
  fun observeMessages(pairId: String): Flow<MessagesResult>

  /**
   * Observes only the latest message of the chat [pairId], for the Chats tab's row: it reads one
   * message, not the whole chat: [LatestMessageResult.Success.message] is null while the chat has
   * no message.
   *
   * The flow emits and ends like [observeMessages], with its own results.
   */
  fun observeLatestMessage(pairId: String): Flow<LatestMessageResult>

  /**
   * Sends [text] to the chat [pairId] as the signed-in student, and creates the chat with this
   * message when it doesn't exist yet, so the app never creates a chat without a message.
   *
   * The checks of [MessageRepository.sendMessage] come first, in its order, before anything is read
   * or written: the trimmed text ([SendMessageResult.Invalid]), the IDs and the signed-in user
   * ([SendMessageResult.UnexpectedError]: [pairId] must be the pair ID of the student and another
   * student, see [observeMessages]), then the connection ([SendMessageResult.NetworkError] offline,
   * never queued).
   *
   * Then one transaction reads the chat and, with a [messageId], the message under it. [messageId]
   * is a retry ID as in [MessageRepository.sendMessage]: an ID that already holds the same sender
   * and trimmed text returns [SendMessageResult.Sent] without a write, and one holding anything
   * else [SendMessageResult.UnexpectedError]. Otherwise the transaction stores the message and,
   * when the chat is missing, creates it with `uids`, the pair's two UIDs. The Security Rules
   * accept that write only while the two are friends, so after a friend is removed, while a new
   * request is pending or once the other account was deleted, a new message returns
   * [SendMessageResult.UnexpectedError] and nothing is written.
   *
   * If both students send their first message at once, both try to create the chat, and the
   * transaction that commits second fails. An attempt that tried to create the chat and failed with
   * a Firestore error other than being offline therefore runs once more: it then finds the chat and
   * adds only its message, so both messages are [SendMessageResult.Sent], in the server's order.
   */
  suspend fun sendMessage(
      pairId: String,
      text: String,
      messageId: String? = null,
  ): SendMessageResult

  /**
   * Unsends the signed-in student's message [messageId] of the chat [pairId] (#365): its text
   * becomes [UNSENT_MESSAGE_TEXT] for both students, and its sender, time and place in the chat
   * stay. Both students' message listeners then receive it with the empty text
   * ([Message.isUnsent]).
   *
   * The IDs and the signed-in user are checked first ([UnsendMessageResult.UnexpectedError]:
   * [pairId] must be the pair ID of the student and another student, see [observeMessages]), then
   * the connection ([UnsendMessageResult.NetworkError] offline, never queued).
   *
   * Then one transaction, tried once, reads the message: one that is missing, can't be read or
   * isn't the student's (another student's, or one whose author's account was deleted) is
   * [UnsendMessageResult.UnexpectedError]; one already unsent is [UnsendMessageResult.Unsent]
   * without a write (a retry after a lost answer); otherwise only its text is emptied. The Security
   * Rules accept that write only while the two are friends, so after a friend is removed, while a
   * new request is pending or once the other account was deleted, it is
   * [UnsendMessageResult.UnexpectedError] and nothing changes.
   *
   * An attempt that read the message and then failed for any reason but being offline runs once
   * more, as group writes do (#232): the student's other device may have unsent the same message in
   * between, so the commit failed, and the rerun finds it unsent and writes nothing.
   */
  suspend fun unsendMessage(pairId: String, messageId: String): UnsendMessageResult
}
