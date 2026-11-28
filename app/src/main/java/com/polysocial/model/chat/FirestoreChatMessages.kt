// Contributors: Claude (Opus 5.5, Anthropic) (the group chat's listener, send failures and
// single-attempt transaction, in FirestoreMessageRepository, #237).
// Contributors: OpenAI Codex (the same-ID retry check, #238).
// Contributors: Claude (Fable 5.2, Anthropic) (the shared listener and the latest message's
// limit(1), #363).
// Contributors: Claude (Opus 5.5, Anthropic) (moved them out of FirestoreMessageRepository, so the
// one-to-one chat repository shares them, #428).
// Contributors: Claude (Opus 5.5, Anthropic) (the failures of any chat write, for unsend, #435).
package com.polysocial.model.chat

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * A snapshot listener as a flow, for the chat repositories: [loading] first, then [read] of each
 * snapshot of the query [query] builds for the signed-in student's UID, metadata changes included,
 * so the flow also emits when only `fromCache` changes.
 *
 * The user is read when collection starts. Nobody signed in, or a null [query] (a chat the student
 * can't be in), ends the flow with [unexpected] without a query. A listener error ends it with
 * [offline] when the server can't be reached and with [unexpected] otherwise (a denied read); a
 * snapshot [read] can't read, or an exception while starting to listen (for example a database
 * blocked while the session's cache is cleared, #198), ends it with [unexpected], never with a
 * crash. The flow is conflated: a slow collector gets only the latest result, and the last one is
 * always delivered. Cancelling the collection removes the listener.
 */
internal fun <T> listenToChat(
    auth: AuthRepository,
    loading: T,
    offline: T,
    unexpected: T,
    query: (uid: String) -> Query?,
    read: (snapshot: QuerySnapshot, uid: String) -> T,
): Flow<T> = callbackFlow {
  trySend(loading)
  fun fail(error: Exception?) {
    trySend(
        if ((error as? FirebaseFirestoreException)?.isOffline() == true) offline else unexpected
    )
    close()
  }
  // Nothing in this try suspends, so it can't catch the collector's cancellation, and
  // awaitClose still ends a cancelled collection.
  val registration =
      try {
        val uid = auth.currentUser()?.uid
        val listened = uid?.let(query)
        if (uid == null || listened == null) {
          fail(null)
          null
        } else {
          listened.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null || snapshot == null) fail(error)
            else
                try {
                  trySend(read(snapshot, uid))
                } catch (e: Exception) {
                  // Thrown here, it would crash the app on Firestore's callback thread.
                  fail(e)
                }
          }
        }
      } catch (e: Exception) {
        fail(e)
        null
      }
  awaitClose { registration?.remove() }
}
    .buffer(Channel.CONFLATED)

/**
 * [listenToChat] for a chat's messages, as `MessageRepository.observeMessages` describes them: the
 * readable messages of each snapshot, in the query's order.
 */
internal fun listenToMessages(
    auth: AuthRepository,
    query: (uid: String) -> Query?,
): Flow<MessagesResult> =
    listenToChat(
        auth,
        loading = MessagesResult.Loading,
        offline = MessagesResult.NetworkError,
        unexpected = MessagesResult.UnexpectedError,
        query = query,
    ) { snapshot, _ ->
      snapshot.toMessagesResult()
    }

/**
 * A chat's messages in the server's order: by `createdAt`, then by document ID for messages stored
 * at the same time.
 */
internal fun CollectionReference.inServerOrder(): Query = orderBy(MESSAGE_CREATED_AT)

/** Only a chat's newest message, so a chat list reads one document per chat. */
internal fun CollectionReference.newestOnly(): Query =
    orderBy(MESSAGE_CREATED_AT, Query.Direction.DESCENDING).limit(1)

/**
 * A send's transaction is tried once (#237): a failure is reported, and the screen offers Try again
 * under the same message ID, rather than Firestore trying it again.
 */
internal val SINGLE_ATTEMPT: TransactionOptions =
    TransactionOptions.Builder().setMaxAttempts(1).build()

/**
 * What a send answers when its message ID already holds [stored] (null when it can't be read):
 * [SendMessageResult.Sent] only for the same [senderId] and trimmed [text], a retry of a draft the
 * server already stored, and [SendMessageResult.UnexpectedError] otherwise. Nothing is written.
 */
internal fun retriedDraft(stored: Message?, senderId: String, text: String): SendMessageResult =
    if (stored?.senderId == senderId && stored.text == text) SendMessageResult.Sent
    else SendMessageResult.UnexpectedError

/** Runs a send, turning its failures into results as [reportWriteFailures] does. */
internal suspend fun reportSendFailures(send: suspend () -> SendMessageResult): SendMessageResult =
    reportWriteFailures(SendMessageResult.NetworkError, SendMessageResult.UnexpectedError, send)

/**
 * Runs a chat write, turning its failures into results: a Firestore failure because the server
 * can't be reached is [offline]; any other Firestore failure, a Firebase task cancelled while the
 * caller still runs, or any other exception is [unexpected]. Cancelling the caller's coroutine
 * still cancels it.
 */
internal suspend fun <T> reportWriteFailures(
    offline: T,
    unexpected: T,
    write: suspend () -> T,
): T =
    try {
      write()
    } catch (e: FirebaseFirestoreException) {
      if (e.isOffline()) offline else unexpected
    } catch (_: CancellationException) {
      // A task cancelled while the caller still runs is a failure; a cancelled caller rethrows.
      currentCoroutineContext().ensureActive()
      unexpected
    } catch (_: Exception) {
      unexpected
    }

/** Whether this failure means the server couldn't be reached. */
internal fun FirebaseFirestoreException.isOffline() =
    code == FirebaseFirestoreException.Code.UNAVAILABLE ||
        code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED

/**
 * The snapshot's readable messages, in the query's order, read with
 * [ServerTimestampBehavior.ESTIMATE]: a message whose server time hasn't reached this device yet
 * shows at Firestore's local estimate instead of being skipped as malformed.
 */
private fun QuerySnapshot.toMessagesResult() =
    MessagesResult.Success(
        documents.mapNotNull {
          messageFromFirestore(it.id, it.getData(ServerTimestampBehavior.ESTIMATE))
        },
        metadata.isFromCache,
    )
