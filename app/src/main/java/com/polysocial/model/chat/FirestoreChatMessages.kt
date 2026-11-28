// Contributors: Claude (Opus 5.5, Anthropic) (the group chat's listener, send failures and
// single-attempt transaction, in FirestoreMessageRepository, #237).
// Contributors: OpenAI Codex (the same-ID retry check, #238).
// Contributors: Claude (Fable 5.2, Anthropic) (the shared listener and the latest message's
// limit(1), #363).
// Contributors: Claude (Opus 5.5, Anthropic) (moved them out of FirestoreMessageRepository, so the
// one-to-one chat repository shares them, #428).
// Contributors: Claude (Opus 5.5, Anthropic) (the failures of any chat write, for unsend, #435).
// Contributors: Claude (Opus 5.5, Anthropic) (unsend's transaction and rerun, moved here from
// FirestoreOneToOneChatRepository to share them with group chats, #439).
package com.polysocial.model.chat

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

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

/**
 * Unsends the message [message] gives as [uid], in either kind of chat (#435, #439), after the
 * caller checked the IDs, the user and the connection: one transaction tried once
 * ([unsendAttempt]), its failures reported as [reportWriteFailures] does.
 *
 * The contracts' "for any reason but being offline" is, exactly: an attempt that read the message
 * and failed with a [FirebaseFirestoreException] whose code isn't `UNAVAILABLE` or
 * `DEADLINE_EXCEEDED` runs once more. Any other exception, the caller's cancellation included,
 * isn't rerun; nor is an attempt whose read failed (for example denied under a deletion marker, or
 * for a student out of the group).
 */
internal suspend fun unsendChatMessage(
    db: FirebaseFirestore,
    uid: String,
    message: () -> DocumentReference,
): UnsendMessageResult =
    reportWriteFailures(UnsendMessageResult.NetworkError, UnsendMessageResult.UnexpectedError) {
      val reference = message()
      val read = AtomicBoolean(false)
      try {
        unsendAttempt(db, reference, uid, read)
      } catch (e: FirebaseFirestoreException) {
        // The student's other device unsent it in between (FAILED_PRECONDITION): the rerun reads
        // it unsent and writes nothing. A refusal by the rules fails the rerun too.
        if (e.isOffline() || !read.get()) throw e
        unsendAttempt(db, reference, uid, AtomicBoolean(false))
      }
    }

/**
 * One attempt at unsending [message] as [uid], in a transaction tried once: it reads the message,
 * sets [read], then empties only its text, unless it isn't [uid]'s or is already unsent.
 */
private suspend fun unsendAttempt(
    db: FirebaseFirestore,
    message: DocumentReference,
    uid: String,
    read: AtomicBoolean,
): UnsendMessageResult =
    db.runTransaction(SINGLE_ATTEMPT) { transaction ->
          val stored = messageFromFirestore(message.id, transaction.get(message).data)
          read.set(true)
          when {
            stored == null || stored.senderId != uid -> UnsendMessageResult.UnexpectedError
            stored.isUnsent -> UnsendMessageResult.Unsent
            else -> {
              transaction.update(message, MESSAGE_TEXT, UNSENT_MESSAGE_TEXT)
              UnsendMessageResult.Unsent
            }
          }
        }
        .await()

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
