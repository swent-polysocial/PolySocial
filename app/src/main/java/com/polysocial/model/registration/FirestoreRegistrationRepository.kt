// Contributors: Claude Opus 5.5 (drafted the Firestore registration repository, #121; the attendee
// count, #131).
// Contributors: Claude (Opus 5.5, Anthropic) (listing the attendees for co-organizers, #370).
// Contributors: Claude (Opus 5.5, Anthropic) (counted registrations and the classification of a
// refused one, #373, from #372's JS SDK model by Claude (Fable 5.2 and Opus 5.5, Anthropic)).
package com.polysocial.model.registration

import androidx.annotation.VisibleForTesting
import com.google.firebase.FirebaseException
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.ATTENDEE_COUNT_FIELD
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.eventFromFirestore
import com.polysocial.model.event.isFull
import com.polysocial.model.event.isReadableEventId
import com.polysocial.model.network.NetworkMonitor
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
 * Like `FirestoreEventRepository`, writes check [NetworkMonitor] first and run in a transaction:
 * Firestore only runs transactions against the server, so a write fails offline instead of being
 * queued.
 *
 * Registering and cancelling also change the event's `attendeeCount` by one in the same transaction
 * (#372, decision 21, "The app's transactions"). Neither reads the event: every registration writes
 * it, so a transaction that read it first would be overtaken by every other registration of that
 * moment. They send `FieldValue.increment`, and the Security Rules check the count against the
 * capacity as it is when the write commits. Both run with the SDK's default attempts, which retry a
 * commit another registration overtook (`ABORTED`), never a refusal.
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
   * Creates the registration and raises the event's `attendeeCount` with `increment(1)` in one
   * transaction that reads nothing. A failure that isn't the network's is a refusal, which
   * [whyRefused] explains.
   */
  override suspend fun register(eventId: String): RegisterResult {
    if (!isReadableEventId(eventId)) return RegisterResult.NotFound
    val user = auth.currentUser() ?: return RegisterResult.NotSignedIn
    if (!network.isOnline()) return RegisterResult.NetworkError
    return try {
      val event = db.collection(EVENTS_COLLECTION).document(eventId)
      val attendee = event.collection(ATTENDEES_COLLECTION).document(user.uid)
      db.runTransaction(DEFAULT_ATTEMPTS) { transaction ->
            transaction.set(
                attendee,
                mapOf("uid" to user.uid, "registeredAt" to FieldValue.serverTimestamp()),
            )
            transaction.update(event, ATTENDEE_COUNT_FIELD, FieldValue.increment(1))
          }
          .await()
      RegisterResult.Registered
    } catch (e: FirebaseException) {
      if (e.isOffline()) RegisterResult.NetworkError else whyRefused(eventId, user.uid)
    } catch (e: CancellationException) {
      // A task cancelled while the caller still runs is a failure; a cancelled caller rethrows.
      currentCoroutineContext().ensureActive()
      RegisterResult.UnexpectedError
    } catch (e: Exception) {
      RegisterResult.UnexpectedError
    }
  }

  /**
   * Says why [register]'s write was refused, with one transaction that only reads, tried once. It
   * reads the student's registration first, so a registered student is never answered
   * [RegisterResult.Full] or [RegisterResult.NotFound]: already registered (a second tap, another
   * device, a registration from before the count, or a first answer that was lost) is
   * [RegisterResult.Registered]. Then the event: missing or malformed is [RegisterResult.NotFound],
   * as in `getEvent`, and at its capacity [RegisterResult.Full]. Anything else, such as an event
   * that has ended, a deleted account or rules not deployed yet, is
   * [RegisterResult.UnexpectedError].
   *
   * A read the rules deny is [RegisterResult.NotFound]: #52's read rule denies a missing event and
   * a private one the student isn't allowed in alike, and an account that isn't a student may read
   * neither, so no event is open to it, as before #373. A transaction's reads are requests to the
   * server, unlike `get(Source.SERVER)`, which can wait for good (#194), so offline it fails with
   * [RegisterResult.NetworkError]. Its commit only checks what it read: an event changed in
   * between, for example a place freed meanwhile, fails it with [RegisterResult.UnexpectedError],
   * and the student's Retry registers. Internal only so the emulator tests can run it offline.
   */
  @VisibleForTesting
  internal suspend fun whyRefused(eventId: String, uid: String): RegisterResult =
      try {
        val event = db.collection(EVENTS_COLLECTION).document(eventId)
        val attendee = event.collection(ATTENDEES_COLLECTION).document(uid)
        db.runTransaction(SINGLE_ATTEMPT) { transaction ->
              if (transaction.get(attendee).exists()) RegisterResult.Registered
              else refusalFor(eventId, transaction.get(event))
            }
            .await()
      } catch (e: FirebaseException) {
        when {
          e.isOffline() -> RegisterResult.NetworkError
          e.isDenied() -> RegisterResult.NotFound
          else -> RegisterResult.UnexpectedError
        }
      } catch (e: CancellationException) {
        // Like register: a task cancelled while the caller still runs is a failure.
        currentCoroutineContext().ensureActive()
        RegisterResult.UnexpectedError
      } catch (e: Exception) {
        RegisterResult.UnexpectedError
      }

  /** Why the event [snapshot], read after a refused registration, refused it. */
  private fun refusalFor(eventId: String, snapshot: DocumentSnapshot): RegisterResult {
    val stored =
        if (snapshot.exists()) eventFromFirestore(eventId, snapshot.data.orEmpty()) else null
    return when {
      stored == null -> RegisterResult.NotFound
      stored.isFull() -> RegisterResult.Full
      else -> RegisterResult.UnexpectedError
    }
  }

  /**
   * Deletes the student's registration and lowers the event's `attendeeCount` with `increment(-1)`
   * in one transaction that reads nothing. If the rules refuse it, a second transaction deletes the
   * registration alone: the rules accept that exactly when there is nothing to count (no
   * registration, an event that is gone or no longer readable, or a count at 0 or absent, from
   * before the count), so no read is needed to choose, and a counted registration is never deleted
   * without its place.
   */
  override suspend fun cancel(eventId: String): CancelRegistrationResult {
    if (!isReadableEventId(eventId)) return CancelRegistrationResult.Cancelled
    val user = auth.currentUser() ?: return CancelRegistrationResult.NotSignedIn
    if (!network.isOnline()) return CancelRegistrationResult.NetworkError
    return try {
      val event = db.collection(EVENTS_COLLECTION).document(eventId)
      val attendee = event.collection(ATTENDEES_COLLECTION).document(user.uid)
      try {
        db.runTransaction(DEFAULT_ATTEMPTS) { transaction ->
              transaction.delete(attendee)
              transaction.update(event, ATTENDEE_COUNT_FIELD, FieldValue.increment(-1))
            }
            .await()
      } catch (e: FirebaseException) {
        if (e.isOffline()) return CancelRegistrationResult.NetworkError
        db.runTransaction(DEFAULT_ATTEMPTS) { transaction -> transaction.delete(attendee) }.await()
      }
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

  /**
   * Runs Firestore's `count()` aggregation on `events/{eventId}/attendees` against the server: it
   * reads no attendee document, so the organizer gets only the number. A count the rules deny (not
   * an organizer, or no such event) is [AttendeeCountResult.UnexpectedError].
   */
  override suspend fun countAttendees(eventId: String): AttendeeCountResult {
    if (!isReadableEventId(eventId)) return AttendeeCountResult.UnexpectedError
    if (!network.isOnline()) return AttendeeCountResult.NetworkError
    return try {
      val snapshot =
          db.collection(EVENTS_COLLECTION)
              .document(eventId)
              .collection(ATTENDEES_COLLECTION)
              .count()
              .get(AggregateSource.SERVER)
              .await()
      AttendeeCountResult.Count(snapshot.count)
    } catch (e: FirebaseException) {
      if (e.isOffline()) AttendeeCountResult.NetworkError else AttendeeCountResult.UnexpectedError
    } catch (e: CancellationException) {
      // Like register: a task cancelled while the caller still runs is a failure.
      currentCoroutineContext().ensureActive()
      AttendeeCountResult.UnexpectedError
    } catch (e: Exception) {
      AttendeeCountResult.UnexpectedError
    }
  }

  /**
   * Reads `events/{eventId}/attendees` from the server, so a student who just cancelled isn't
   * offered from the cache. Only the document IDs, the attendees' UIDs, are used. A list the rules
   * deny (not an organizer, or no such event) is [AttendeesResult.UnexpectedError].
   */
  override suspend fun getAttendeeUids(eventId: String): AttendeesResult {
    if (!isReadableEventId(eventId)) return AttendeesResult.UnexpectedError
    if (!network.isOnline()) return AttendeesResult.NetworkError
    return try {
      val snapshot =
          db.collection(EVENTS_COLLECTION)
              .document(eventId)
              .collection(ATTENDEES_COLLECTION)
              .get(Source.SERVER)
              .await()
      AttendeesResult.Attendees(snapshot.documents.map { it.id })
    } catch (e: FirebaseException) {
      if (e.isOffline()) AttendeesResult.NetworkError else AttendeesResult.UnexpectedError
    } catch (e: CancellationException) {
      // Like register: a task cancelled while the caller still runs is a failure.
      currentCoroutineContext().ensureActive()
      AttendeesResult.UnexpectedError
    } catch (e: Exception) {
      AttendeesResult.UnexpectedError
    }
  }

  /** The server couldn't be reached, or didn't answer in time. */
  private fun FirebaseException.isOffline() =
      this is FirebaseFirestoreException &&
          (code == FirebaseFirestoreException.Code.UNAVAILABLE ||
              code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED)

  private fun FirebaseException.isDenied() =
      this is FirebaseFirestoreException &&
          code == FirebaseFirestoreException.Code.PERMISSION_DENIED

  private companion object {
    /** For [whyRefused], so that a network failure answers at once. */
    val SINGLE_ATTEMPT: TransactionOptions = TransactionOptions.Builder().setMaxAttempts(1).build()

    /** The SDK's own number of attempts, which retries a commit another write overtook. */
    val DEFAULT_ATTEMPTS: TransactionOptions = TransactionOptions.Builder().build()
  }
}
