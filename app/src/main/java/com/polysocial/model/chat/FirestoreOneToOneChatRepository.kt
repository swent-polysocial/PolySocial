// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Firestore one-to-one chat repository,
// #428).
// Contributors: Claude (Opus 5.5, Anthropic) (unsending a message, #435; its transaction moved to
// FirestoreChatMessages.kt to share it with group chats, #439).
package com.polysocial.model.chat

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.isReadableEventId
import com.polysocial.model.friend.isFriendshipIdOf
import com.polysocial.model.friend.uidsOfFriendshipId
import com.polysocial.model.network.NetworkMonitor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * [OneToOneChatRepository] backed by Firestore: chats in `chats/{pairId}` (see [CHATS_COLLECTION]),
 * written with [newChatFirestoreMap] and read with [oneToOneChatFromFirestore], and their messages
 * in `chats/{pairId}/messages`, with group chat's mapping and listener (#237).
 *
 * Like `FirestoreMessageRepository`, sending and unsending check [NetworkMonitor] first and write
 * in a transaction tried once, which Firestore only runs against the server, so a write fails
 * offline instead of being queued.
 */
class FirestoreOneToOneChatRepository(
    private val database: () -> FirebaseFirestore,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
) : OneToOneChatRepository {
  constructor(
      db: FirebaseFirestore,
      auth: AuthRepository,
      network: NetworkMonitor,
  ) : this({ db }, auth, network)

  private val db: FirebaseFirestore
    get() = database()

  private fun chat(pairId: String): DocumentReference =
      db.collection(CHATS_COLLECTION).document(pairId)

  /**
   * Listens to `chats where uids array-contains <signed-in UID>`, the only list the rules allow; a
   * single-field query, so it needs no composite index. A chat that can't be read is skipped.
   */
  override fun observeMyChats(): Flow<MyOneToOneChatsResult> =
      listenToChat(
          auth,
          loading = MyOneToOneChatsResult.Loading,
          offline = MyOneToOneChatsResult.NetworkError,
          unexpected = MyOneToOneChatsResult.UnexpectedError,
          query = { uid -> db.collection(CHATS_COLLECTION).whereArrayContains(CHAT_UIDS, uid) },
      ) { snapshot, uid ->
        MyOneToOneChatsResult.Success(
            snapshot.documents.mapNotNull { oneToOneChatFromFirestore(it.id, it.data, uid) },
            snapshot.metadata.isFromCache,
        )
      }

  /** The group chat's listener on `chats/{pairId}/messages`, in the server's order. */
  override fun observeMessages(pairId: String): Flow<MessagesResult> =
      listen(pairId) { it.inServerOrder() }

  /** The same listener on the newest message only, so the Chats tab reads one per chat. */
  override fun observeLatestMessage(pairId: String): Flow<LatestMessageResult> =
      listen(pairId) { it.newestOnly() }.map { it.toLatestMessage() }

  /**
   * Listens to [query] of the chat's messages, or ends with [MessagesResult.UnexpectedError]
   * without a query when [pairId] isn't the signed-in student's pair ID.
   */
  private fun listen(pairId: String, query: (CollectionReference) -> Query) =
      listenToMessages(auth) { uid ->
        if (isFriendshipIdOf(pairId, uid)) query(chat(pairId).collection(MESSAGES_COLLECTION))
        else null
      }

  /**
   * Validates and trims [text], checks the IDs, the user and the connection, then sends in one
   * transaction tried once (see [attempt]).
   *
   * The contract's "Firestore error other than being offline" is, exactly: an attempt that set
   * `creating` (it read the chat as missing and wrote it) and failed with a
   * [FirebaseFirestoreException] whose code isn't `UNAVAILABLE` or `DEADLINE_EXCEEDED` runs once
   * more. Any other exception, the caller's cancellation included, isn't rerun; nor is an attempt
   * that failed before trying to create the chat (for example its read denied under a deletion
   * marker). A write the rules deny is [SendMessageResult.UnexpectedError]; losing the connection
   * is [SendMessageResult.NetworkError].
   */
  override suspend fun sendMessage(
      pairId: String,
      text: String,
      messageId: String?,
  ): SendMessageResult {
    validateMessageText(text)?.let {
      return SendMessageResult.Invalid(it)
    }
    if (messageId != null && !isReadableEventId(messageId)) return SendMessageResult.UnexpectedError
    val uid = auth.currentUser()?.uid ?: return SendMessageResult.UnexpectedError
    val uids = uidsOfFriendshipId(pairId)
    if (uids == null || uid !in uids) return SendMessageResult.UnexpectedError
    if (!network.isOnline()) return SendMessageResult.NetworkError
    return reportSendFailures {
      val chat = chat(pairId)
      val messages = chat.collection(MESSAGES_COLLECTION)
      val draft =
          Draft(
              chat = chat,
              message = messageId?.let(messages::document) ?: messages.document(),
              uids = uids,
              senderId = uid,
              text = text.trim(),
          )
      val creating = AtomicBoolean(false)
      try {
        attempt(draft, readsMessage = messageId != null, creating)
      } catch (e: FirebaseFirestoreException) {
        // The other student's first message created the chat in between: the commit failed whole
        // (ALREADY_EXISTS, or PERMISSION_DENIED from the rules), and the next attempt finds it. It
        // reads the message too, under the same ID, so it can't store it twice.
        if (e.isOffline() || !creating.get()) throw e
        attempt(draft, readsMessage = true, AtomicBoolean(false))
      }
    }
  }

  /**
   * One attempt at sending [draft], in a transaction tried once. It reads the chat and, if
   * [readsMessage], the message under the draft's ID: a stored message is answered by
   * [retriedDraft] without a write. Otherwise it sets the message and, when the chat is missing,
   * creates it with its `uids` in the same commit, after setting [creating], so the caller knows
   * the attempt tried to create the chat.
   */
  private suspend fun attempt(
      draft: Draft,
      readsMessage: Boolean,
      creating: AtomicBoolean,
  ): SendMessageResult =
      db.runTransaction(SINGLE_ATTEMPT) { transaction ->
            val chatExists = transaction.get(draft.chat).exists()
            val stored = if (readsMessage) transaction.get(draft.message) else null
            if (stored?.exists() == true) {
              retriedDraft(
                  messageFromFirestore(draft.message.id, stored.data),
                  draft.senderId,
                  draft.text,
              )
            } else {
              if (!chatExists) {
                creating.set(true)
                transaction.set(draft.chat, newChatFirestoreMap(draft.uids))
              }
              transaction.set(draft.message, newMessageFirestoreMap(draft.senderId, draft.text))
              SendMessageResult.Sent
            }
          }
          .await()

  /** Checks the IDs, the user and the connection, then unsends with [unsendChatMessage]. */
  override suspend fun unsendMessage(pairId: String, messageId: String): UnsendMessageResult {
    if (!isReadableEventId(messageId)) return UnsendMessageResult.UnexpectedError
    val uid = auth.currentUser()?.uid ?: return UnsendMessageResult.UnexpectedError
    if (!isFriendshipIdOf(pairId, uid)) return UnsendMessageResult.UnexpectedError
    if (!network.isOnline()) return UnsendMessageResult.NetworkError
    return unsendChatMessage(db, uid) {
      chat(pairId).collection(MESSAGES_COLLECTION).document(messageId)
    }
  }

  /** A message to send: [text] (trimmed) from [senderId] to [chat], of the students [uids]. */
  private class Draft(
      val chat: DocumentReference,
      val message: DocumentReference,
      val uids: List<String>,
      val senderId: String,
      val text: String,
  )
}
