// Contributors: Claude Opus 5.5 (drafted the Firestore registration repository, #121).
package com.polysocial.model.registration

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.isReadableEventId
import com.polysocial.model.network.NetworkMonitor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.tasks.await

/** Subcollection of `events/{eventId}` holding its registrations, one document per student UID. */
const val ATTENDEES_COLLECTION = "attendees"

/**
 * [RegistrationRepository] backed by Firestore. A registration is the document
 * `events/{eventId}/attendees/{uid}` with the fields `uid` (the document ID again, so
 * [observeMyRegistrations] can find a student's registrations with a collection-group query) and
 * `registeredAt` (set by the server).
 *
 * Like `FirestoreEventRepository`, writes check [NetworkMonitor] first and run in a transaction
 * tried once: Firestore only runs transactions against the server, so a write fails offline instead
 * of being queued.
 */
class FirestoreRegistrationRepository(
    private val database: () -> FirebaseFirestore,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
) : RegistrationRepository {
  constructor(
      db: FirebaseFirestore,
      auth: AuthRepository,
      network: NetworkMonitor,
  ) : this({ db }, auth, network)

  private val db: FirebaseFirestore
    get() = database()

  /**
   * Reads the event and the student's registration in one transaction, and writes the registration
   * only if it isn't there yet, so registering twice changes nothing (and keeps the first
   * `registeredAt`). The event is read first: a read the Security Rules deny is
   * [RegisterResult.NotFound], since #52's read rule denies a missing event as well as a private
   * one the student isn't allowed in. A write the rules deny after that is
   * [RegisterResult.UnexpectedError].
   */
  override suspend fun register(eventId: String): RegisterResult {
    if (!isReadableEventId(eventId)) return RegisterResult.NotFound
    val user = auth.currentUser() ?: return RegisterResult.NotSignedIn
    if (!network.isOnline()) return RegisterResult.NetworkError
    // Set on Firestore's transaction thread, read here once the transaction has ended.
    val eventWasRead = AtomicBoolean(false)
    return try {
      val event = db.collection(EVENTS_COLLECTION).document(eventId)
      val attendee = event.collection(ATTENDEES_COLLECTION).document(user.uid)
      db.runTransaction(SINGLE_ATTEMPT) { transaction ->
            val stored = transaction.get(event)
            eventWasRead.set(true)
            when {
              !stored.exists() -> RegisterResult.NotFound
              transaction.get(attendee).exists() -> RegisterResult.Registered
              else -> {
                transaction.set(
                    attendee,
                    mapOf("uid" to user.uid, "registeredAt" to FieldValue.serverTimestamp()),
                )
                RegisterResult.Registered
              }
            }
          }
          .await()
    } catch (e: FirebaseException) {
      when {
        e.isOffline() -> RegisterResult.NetworkError
        e.isDenied() && !eventWasRead.get() -> RegisterResult.NotFound
        else -> RegisterResult.UnexpectedError
      }
    } catch (e: CancellationException) {
      // A task cancelled while the caller still runs is a failure; a cancelled caller rethrows.
      currentCoroutineContext().ensureActive()
      RegisterResult.UnexpectedError
    } catch (e: Exception) {
      RegisterResult.UnexpectedError
    }
  }

  /**
   * Deletes the student's registration in a transaction, only so that it fails offline instead of
   * being queued. Deleting a registration that doesn't exist succeeds, so there is nothing to read
   * first.
   */
  override suspend fun cancel(eventId: String): CancelRegistrationResult {
    if (!isReadableEventId(eventId)) return CancelRegistrationResult.Cancelled
    val user = auth.currentUser() ?: return CancelRegistrationResult.NotSignedIn
    if (!network.isOnline()) return CancelRegistrationResult.NetworkError
    return try {
      val attendee =
          db.collection(EVENTS_COLLECTION)
              .document(eventId)
              .collection(ATTENDEES_COLLECTION)
              .document(user.uid)
      db.runTransaction(SINGLE_ATTEMPT) { transaction -> transaction.delete(attendee) }.await()
      CancelRegistrationResult.Cancelled
    } catch (e: FirebaseException) {
      if (e.isOffline()) CancelRegistrationResult.NetworkError
      else CancelRegistrationResult.UnexpectedError
    } catch (e: CancellationException) {
      // Like register: a task cancelled while the caller still runs is a failure.
      currentCoroutineContext().ensureActive()
      CancelRegistrationResult.UnexpectedError
    } catch (e: Exception) {
      CancelRegistrationResult.UnexpectedError
    }
  }

  /**
   * Listens to the collection-group query `attendees where uid == <signed-in UID>`: the shape the
   * read rule requires, so a student never queries other students' registrations. It needs the
   * collection-group field index in `firebase/firestore/firestore.indexes.json`. The event ID is
   * the registration's parent document. Metadata changes are included, so the flow also emits when
   * only `fromCache` changes.
   *
   * Like `FirestoreEventRepository`'s listeners, the flow is conflated, and any failure (nobody
   * signed in, a listener error, an unexpected exception) ends it with
   * [MyRegistrationsResult.Error] instead of throwing.
   */
  override fun observeMyRegistrations(): Flow<MyRegistrationsResult> = callbackFlow {
    fun fail() {
      trySend(MyRegistrationsResult.Error)
      close()
    }
    // Nothing in this try suspends, so it can't catch the collector's cancellation, and
    // awaitClose still ends a cancelled collection.
    val registration =
        try {
          val user = auth.currentUser()
          if (user == null) fail()
          user?.let {
            db.collectionGroup(ATTENDEES_COLLECTION)
                .whereEqualTo("uid", it.uid)
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
                  if (failure != null || snapshot == null) fail()
                  else
                      try {
                        val eventIds =
                            snapshot.documents
                                .mapNotNull { document -> document.reference.parent.parent?.id }
                                .toSet()
                        trySend(
                            MyRegistrationsResult.Registrations(
                                eventIds,
                                snapshot.metadata.isFromCache,
                            )
                        )
                      } catch (e: Exception) {
                        // Thrown here, it would crash the app on Firestore's callback
                        // thread.
                        fail()
                      }
                }
          }
        } catch (e: Exception) {
          fail()
          null
        }
    awaitClose { registration?.remove() }
  }
      .buffer(Channel.CONFLATED)
      .catch { failure ->
        if (failure is CancellationException) throw failure
        emit(MyRegistrationsResult.Error)
      }

  private fun FirebaseException.isOffline() =
      this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.UNAVAILABLE

  private fun FirebaseException.isDenied() =
      this is FirebaseFirestoreException &&
          code == FirebaseFirestoreException.Code.PERMISSION_DENIED

  private companion object {
    val SINGLE_ATTEMPT: TransactionOptions = TransactionOptions.Builder().setMaxAttempts(1).build()
  }
}
