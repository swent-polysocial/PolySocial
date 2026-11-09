// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Firestore group chat repository, #237).
package com.polysocial.model.chat

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.isReadableEventId
import com.polysocial.model.group.GROUPS_COLLECTION
import com.polysocial.model.network.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/**
 * [MessageRepository] backed by Firestore: messages in `events/{eventId}/groups/{groupId}/messages`
 * (see [MESSAGES_COLLECTION]), with `senderId`, `text` and the server's `createdAt`.
 *
 * Like `FirestoreEventRepository.createEvent`, sending checks [NetworkMonitor] first and writes in
 * a transaction tried once: Firestore only runs transactions against the server, so a message fails
 * offline instead of being queued. The transaction only creates a new document, so it can't
 * conflict with another write, and retries would just delay the offline error.
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
  override fun observeMessages(eventId: String, groupId: String): Flow<MessagesResult> {
    if (!isReadableEventId(eventId) || !isReadableEventId(groupId)) {
      return flowOf(MessagesResult.Loading, MessagesResult.UnexpectedError)
    }
    return callbackFlow {
          trySend(MessagesResult.Loading)
          fun fail(error: Exception?) {
            trySend(
                if ((error as? FirebaseFirestoreException)?.isOffline() == true)
                    MessagesResult.NetworkError
                else MessagesResult.UnexpectedError
            )
            close()
          }
          // Nothing in this try suspends, so it can't catch the collector's cancellation, and
          // awaitClose still ends a cancelled collection.
          val registration =
              try {
                val user = auth.currentUser()
                if (user == null) fail(null)
                user?.let {
                  messages(eventId, groupId).orderBy(MESSAGE_CREATED_AT).addSnapshotListener(
                      MetadataChanges.INCLUDE
                  ) { snapshot, error ->
                    if (error != null || snapshot == null) fail(error)
                    else
                        try {
                          trySend(snapshot.toMessagesResult())
                        } catch (e: Exception) {
                          // Thrown here, it would crash the app on Firestore's callback thread.
                          fail(e)
                        }
                  }
                }
              } catch (e: Exception) {
                // For example a database blocked while the session's cache is cleared (#198).
                fail(e)
                null
              }
          awaitClose { registration?.remove() }
        }
        .buffer(Channel.CONFLATED)
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
  ): SendMessageResult {
    validateMessageText(text)?.let {
      return SendMessageResult.Invalid(it)
    }
    if (!isReadableEventId(eventId) || !isReadableEventId(groupId)) {
      return SendMessageResult.UnexpectedError
    }
    val user = auth.currentUser() ?: return SendMessageResult.UnexpectedError
    if (!network.isOnline()) return SendMessageResult.NetworkError
    return try {
      val message = messages(eventId, groupId).document()
      val fields = newMessageFirestoreMap(user.uid, text.trim())
      db.runTransaction(SINGLE_ATTEMPT) { it.set(message, fields) }.await()
      SendMessageResult.Sent
    } catch (e: FirebaseFirestoreException) {
      if (e.isOffline()) SendMessageResult.NetworkError else SendMessageResult.UnexpectedError
    } catch (_: CancellationException) {
      // A task cancelled while the caller still runs is a failure; a cancelled caller rethrows.
      currentCoroutineContext().ensureActive()
      SendMessageResult.UnexpectedError
    } catch (_: Exception) {
      SendMessageResult.UnexpectedError
    }
  }

  /**
   * The snapshot's readable messages, in the query's order, with
   * [ServerTimestampBehavior.ESTIMATE].
   */
  private fun QuerySnapshot.toMessagesResult() =
      MessagesResult.Success(
          documents.mapNotNull {
            messageFromFirestore(it.id, it.getData(ServerTimestampBehavior.ESTIMATE))
          },
          metadata.isFromCache,
      )

  private fun FirebaseFirestoreException.isOffline() =
      code == FirebaseFirestoreException.Code.UNAVAILABLE ||
          code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED

  private companion object {
    val SINGLE_ATTEMPT: TransactionOptions = TransactionOptions.Builder().setMaxAttempts(1).build()
  }
}
