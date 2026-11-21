// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Firestore group chat repository, #237).
// Contributors: OpenAI Codex (made explicit message IDs safe to retry, #238).
// Contributors: Claude (Fable 5.2, Anthropic) (shared the message listener and read a group's
// latest message with limit(1), #363).
// Contributors: Claude (Opus 5.5, Anthropic) (the listener and send failures moved to
// FirestoreChatMessages.kt, to share them with one-to-one chats, #428).
package com.polysocial.model.chat

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.isReadableEventId
import com.polysocial.model.group.GROUPS_COLLECTION
import com.polysocial.model.network.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * [MessageRepository] backed by Firestore: messages in `events/{eventId}/groups/{groupId}/messages`
 * (see [MESSAGES_COLLECTION]), with `senderId`, `text` and the server's `createdAt`.
 *
 * Like `FirestoreEventRepository.createEvent`, sending checks [NetworkMonitor] first and writes in
 * a transaction tried once: Firestore only runs transactions against the server, so a message fails
 * offline instead of being queued. With an explicit ID, the transaction reads the document before
 * writing. It creates a missing message or checks that a stored message matches the draft.
 */
class FirestoreMessageRepository(
    private val database: () -> FirebaseFirestore,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
) : MessageRepository {
  constructor(
      db: FirebaseFirestore,
      auth: AuthRepository,
      network: NetworkMonitor,
  ) : this({ db }, auth, network)

  private val db: FirebaseFirestore
    get() = database()

  private fun messages(eventId: String, groupId: String): CollectionReference =
      db.collection(EVENTS_COLLECTION)
          .document(eventId)
          .collection(GROUPS_COLLECTION)
          .document(groupId)
          .collection(MESSAGES_COLLECTION)

  /**
   * Listens to the group's messages ordered by `createdAt`, metadata changes included, so the flow
   * also emits when only `fromCache` changes. The query's order is kept: the server's time, then
   * the document ID for messages stored at the same time.
   *
   * `createdAt` is read with [ServerTimestampBehavior.ESTIMATE]: a message whose server time hasn't
   * reached this device yet shows at Firestore's local estimate instead of being skipped as
   * malformed. The app's own messages are written in a transaction, so they arrive from the server
   * with its time.
   *
   * Nobody signed in, IDs that can't be document IDs, a listener error (a denied read is
   * [MessagesResult.UnexpectedError], an unreachable server [MessagesResult.NetworkError]) or a
   * snapshot that can't be read end the flow instead of throwing. The flow is conflated, like the
   * other repositories' listeners.
   */
  override fun observeMessages(eventId: String, groupId: String): Flow<MessagesResult> =
      listen(eventId, groupId) { it.inServerOrder() }

  /**
   * The same listener as [observeMessages] on the newest message only: `createdAt` descending with
   * `limit(1)`, so a chat list reads one document per group. No message, or a newest message that
   * can't be read, is a null [LatestMessageResult.Success.message].
   */
  override fun observeLatestMessage(eventId: String, groupId: String): Flow<LatestMessageResult> =
      listen(eventId, groupId) { it.newestOnly() }.map { it.toLatestMessage() }

  /**
   * A snapshot listener on [query] of the group's messages as a flow, as [observeMessages]
   * describes: its results keep the query's order.
   */
  private fun listen(
      eventId: String,
      groupId: String,
      query: (CollectionReference) -> Query,
  ): Flow<MessagesResult> {
    if (!isReadableEventId(eventId) || !isReadableEventId(groupId)) {
      return flowOf(MessagesResult.Loading, MessagesResult.UnexpectedError)
    }
    return listenToMessages(auth) { query(messages(eventId, groupId)) }
  }

  /**
   * Validates and trims [text], checks the IDs, the user and the connection, then writes the new
   * message in a transaction tried once. A write the rules deny (not a member) is
   * [SendMessageResult.UnexpectedError]; losing the connection during the write is
   * [SendMessageResult.NetworkError].
   */
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
    val user = auth.currentUser() ?: return SendMessageResult.UnexpectedError
    if (!network.isOnline()) return SendMessageResult.NetworkError
    return reportSendFailures {
      val collection = messages(eventId, groupId)
      val message = messageId?.let(collection::document) ?: collection.document()
      val trimmed = text.trim()
      db.runTransaction(SINGLE_ATTEMPT) { transaction ->
            val existing = messageId?.let { transaction.get(message) }
            when {
              existing?.exists() != true -> {
                transaction.set(message, newMessageFirestoreMap(user.uid, trimmed))
                SendMessageResult.Sent
              }
              else ->
                  retriedDraft(messageFromFirestore(message.id, existing.data), user.uid, trimmed)
            }
          }
          .await()
    }
  }
}
