// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Firestore check-in repository, #467).
package com.polysocial.model.checkin

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.eventFromFirestore
import com.polysocial.model.event.isReadableEventId
import com.polysocial.model.event.toTimestamp
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.ATTENDEES_COLLECTION
import java.time.Clock
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

/** Subcollection of `events/{eventId}` holding its check-ins, one document per student UID. */
const val CHECK_INS_COLLECTION = "checkIns"

/** The stored `method` of a check-in made with the student's location. */
private const val LOCATION_METHOD = "location"

private const val CHECKED_IN_AT_FIELD = "checkedInAt"

/**
 * [CheckInRepository] backed by Firestore. A check-in is the document
 * `events/{eventId}/checkIns/{uid}` with exactly `uid` (the document ID again, for #472's
 * collection-group cleanup), `checkedInAt` (set by the server), `method` and `expiresAt`, the
 * window's end + 30 days, which the Security Rules require to the nanosecond and a TTL policy
 * deletes the check-in at. No location is ever stored.
 *
 * Like `FirestoreRegistrationRepository`, [checkIn] checks [NetworkMonitor] first and runs in a
 * transaction: Firestore only runs transactions against the server, so a check-in fails offline
 * instead of being queued. The transaction uses the SDK's default attempts, so a commit overtaken
 * by a change of the event it read (a registration changes its `attendeeCount`) reads it again.
 *
 * @param clock the device's time, for the app's own window check. The rules use the server's.
 */
class FirestoreCheckInRepository(
    private val database: () -> FirebaseFirestore,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : CheckInRepository {
  constructor(
      db: FirebaseFirestore,
      auth: AuthRepository,
      network: NetworkMonitor,
      clock: Clock,
  ) : this({ db }, auth, network, clock)

  private val db: FirebaseFirestore
    get() = database()

  /**
   * One transaction reads the event, the student's check-in and their registration, in the order of
   * the [CheckInRepository.checkIn] contract, and writes the check-in only when all three allow it.
   * The event is read through the rules, which refuse a missing event and a private one the student
   * isn't allowed in alike: that refusal is [CheckInResult.NotFound], while a refusal of any later
   * read or of the write is [CheckInResult.UnexpectedError], since the app's checks passed.
   */
  override suspend fun checkIn(eventId: String): CheckInResult {
    if (!isReadableEventId(eventId)) return CheckInResult.NotFound
    val user = auth.currentUser() ?: return CheckInResult.NotSignedIn
    if (!network.isOnline()) return CheckInResult.NetworkError
    return try {
      val event = db.collection(EVENTS_COLLECTION).document(eventId)
      val checkIn = event.collection(CHECK_INS_COLLECTION).document(user.uid)
      val attendee = event.collection(ATTENDEES_COLLECTION).document(user.uid)
      db.runTransaction(DEFAULT_ATTEMPTS) { transaction ->
            val snapshot =
                try {
                  transaction.get(event)
                } catch (e: FirebaseFirestoreException) {
                  if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    return@runTransaction CheckInResult.NotFound
                  }
                  throw e
                }
            val stored =
                if (snapshot.exists()) eventFromFirestore(eventId, snapshot.data.orEmpty())
                else null
            when {
              stored == null -> CheckInResult.NotFound
              transaction.get(checkIn).exists() -> CheckInResult.CheckedIn
              !transaction.get(attendee).exists() -> CheckInResult.NotRegistered
              clock.instant() !in checkInWindow(stored) -> CheckInResult.OutsideWindow
              else -> {
                transaction.set(
                    checkIn,
                    mapOf(
                        "uid" to user.uid,
                        CHECKED_IN_AT_FIELD to FieldValue.serverTimestamp(),
                        "method" to LOCATION_METHOD,
                        "expiresAt" to checkInWindow(stored).expiresAt.toTimestamp(),
                    ),
                )
                CheckInResult.CheckedIn
              }
            }
          }
          .await()
    } catch (e: FirebaseException) {
      if (e.isOffline()) CheckInResult.NetworkError else CheckInResult.UnexpectedError
    } catch (e: CancellationException) {
      // A task cancelled while the caller still runs is a failure; a cancelled caller rethrows.
      currentCoroutineContext().ensureActive()
      CheckInResult.UnexpectedError
    } catch (e: Exception) {
      CheckInResult.UnexpectedError
    }
  }

  /**
   * Listens to the document `events/{eventId}/checkIns/{uid}`, reading it by ID as the rules
   * require (nobody lists check-ins). Like
   * `FirestoreRegistrationRepository.observeMyRegistrations`, the flow is conflated, and any
   * failure (nobody signed in, an invalid ID, a listener error such as a refused read, a missing or
   * wrong-typed `checkedInAt`) ends it with [CheckInStatus.Error] instead of throwing. The time is
   * read with [ServerTimestampBehavior.ESTIMATE], so it is never missing before the server has set
   * it.
   */
  override fun observeCheckIn(eventId: String, uid: String): Flow<CheckInStatus> = callbackFlow {
    fun fail() {
      trySend(CheckInStatus.Error)
      close()
    }
    // Nothing in this try suspends, so it can't catch the collector's cancellation, and
    // awaitClose still ends a cancelled collection.
    val registration =
        try {
          // The student's UID can't name a document for the same reasons as an event ID.
          if (
              !isReadableEventId(eventId) || !isReadableEventId(uid) || auth.currentUser() == null
          ) {
            fail()
            null
          } else {
            db.collection(EVENTS_COLLECTION)
                .document(eventId)
                .collection(CHECK_INS_COLLECTION)
                .document(uid)
                .addSnapshotListener { snapshot, failure ->
                  if (failure != null || snapshot == null) fail()
                  else
                      try {
                        statusOf(snapshot)?.let { trySend(it) } ?: fail()
                      } catch (e: Exception) {
                        // Thrown here, it would crash the app on Firestore's callback thread.
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
        emit(CheckInStatus.Error)
      }

  /** The status a stored check-in [snapshot] shows, or null when its time can't be read. */
  private fun statusOf(snapshot: DocumentSnapshot): CheckInStatus? =
      if (!snapshot.exists()) CheckInStatus.NotCheckedIn
      else
          snapshot.getTimestamp(CHECKED_IN_AT_FIELD, ServerTimestampBehavior.ESTIMATE)?.let {
            CheckInStatus.CheckedIn(it.toInstant())
          }

  /** The server couldn't be reached, or didn't answer in time. */
  private fun FirebaseException.isOffline() =
      this is FirebaseFirestoreException &&
          (code == FirebaseFirestoreException.Code.UNAVAILABLE ||
              code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED)

  private companion object {
    /** The SDK's own number of attempts, which retries a commit another write overtook. */
    val DEFAULT_ATTEMPTS: TransactionOptions = TransactionOptions.Builder().build()
  }
}
