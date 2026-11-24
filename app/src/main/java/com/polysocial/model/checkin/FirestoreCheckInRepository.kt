// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Firestore check-in repository, #467; the
// entrance code, #468).
package com.polysocial.model.checkin

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.chat.isOffline
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

/** Subcollection of `events/{eventId}` holding its entrance code (#468). */
const val CHECK_IN_CODE_COLLECTION = "checkInCode"

/** The one document of [CHECK_IN_CODE_COLLECTION]: the event's current entrance code. */
const val ENTRANCE_CODE_DOCUMENT = "current"

/**
 * [CheckInRepository] backed by Firestore. A check-in is the document
 * `events/{eventId}/checkIns/{uid}` with exactly `uid` (the document ID again, for #472's
 * collection-group cleanup), `checkedInAt` (set by the server), `method` and `expiresAt`, the
 * window's end + 30 days, which the Security Rules require exactly and a TTL policy deletes the
 * check-in at. No location is ever stored.
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
      // The chats' helper: the server unreachable or too slow (UNAVAILABLE, DEADLINE_EXCEEDED).
      if ((e as? FirebaseFirestoreException)?.isOffline() == true) CheckInResult.NetworkError
      else CheckInResult.UnexpectedError
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

  /**
   * Listens to `events/{eventId}/checkInCode/current`, by ID as the rules require, including
   * metadata changes, so the screen learns at once when the code it shows comes only from the
   * cache. Like [observeCheckIn], any failure ends the flow with [EntranceCodeStatus.Error]: nobody
   * signed in, an invalid ID, a refused read (not an organizer, or outside the window), or a
   * missing or wrong-typed `token` or `rotatedAt`.
   */
  override fun observeEntranceCode(eventId: String): Flow<EntranceCodeStatus> = callbackFlow {
    fun fail() {
      trySend(EntranceCodeStatus.Error)
      close()
    }
    // Nothing in this try suspends, so it can't catch the collector's cancellation.
    val registration =
        try {
          if (!isReadableEventId(eventId) || auth.currentUser() == null) {
            fail()
            null
          } else {
            codeDocument(eventId).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure
              ->
              if (failure != null || snapshot == null) fail()
              else
                  try {
                    codeStatusOf(snapshot)?.let { trySend(it) } ?: fail()
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
        emit(EntranceCodeStatus.Error)
      }

  /**
   * One transaction reads the event (for `expiresAt`, the window's end + 30 days, which the rules
   * require exactly) and the code, and writes a new token only if the stored one is still
   * [replacing]: a phone that comes second finds the other's token and writes nothing. The new code
   * keeps [replacing] as `previousToken` and gets the server's `rotatedAt`. A missing or unreadable
   * event, and any refusal by the rules (not an organizer, outside the window, under 50 s since the
   * last rotation), are [RotateCodeResult.UnexpectedError].
   *
   * When two phones race, the loser's write reaches the server after the winner's and the rules
   * refuse it (its `previousToken` no longer matches), rather than Firestore retrying it (see
   * CONTEXT.md, Traps). So a refusal reads the code again: if it no longer holds [replacing],
   * another organizer rotated it first, which is [RotateCodeResult.Done].
   */
  override suspend fun rotateEntranceCode(eventId: String, replacing: String?): RotateCodeResult {
    if (!isReadableEventId(eventId) || auth.currentUser() == null) {
      return RotateCodeResult.UnexpectedError
    }
    if (!network.isOnline()) return RotateCodeResult.NetworkError
    return try {
      val event = db.collection(EVENTS_COLLECTION).document(eventId)
      val code = codeDocument(eventId)
      db.runTransaction(DEFAULT_ATTEMPTS) { transaction ->
            val snapshot = transaction.get(event)
            val stored =
                if (snapshot.exists()) eventFromFirestore(eventId, snapshot.data.orEmpty())
                else null
            val current = transaction.get(code)
            val storedToken = if (current.exists()) current.getString(TOKEN_FIELD) else null
            when {
              stored == null -> RotateCodeResult.UnexpectedError
              storedToken != replacing -> RotateCodeResult.Done
              else -> {
                val data =
                    mutableMapOf<String, Any>(
                        TOKEN_FIELD to newEntranceToken(),
                        ROTATED_AT_FIELD to FieldValue.serverTimestamp(),
                        "expiresAt" to checkInWindow(stored).expiresAt.toTimestamp(),
                    )
                replacing?.let { data[PREVIOUS_TOKEN_FIELD] = it }
                transaction.set(code, data)
                RotateCodeResult.Done
              }
            }
          }
          .await()
    } catch (e: FirebaseException) {
      when {
        e.isOfflineFailure() -> RotateCodeResult.NetworkError
        e.isDenied() -> whyRotationRefused(eventId, replacing)
        else -> RotateCodeResult.UnexpectedError
      }
    } catch (e: CancellationException) {
      // A task cancelled while the caller still runs is a failure; a cancelled caller rethrows.
      currentCoroutineContext().ensureActive()
      RotateCodeResult.UnexpectedError
    } catch (e: Exception) {
      RotateCodeResult.UnexpectedError
    }
  }

  /**
   * After a refused rotation, reads the code again in one transaction that only reads, tried once:
   * a code that no longer holds [replacing] was rotated by another organizer first
   * ([RotateCodeResult.Done]); otherwise the refusal stands.
   */
  private suspend fun whyRotationRefused(eventId: String, replacing: String?): RotateCodeResult =
      try {
        val code = codeDocument(eventId)
        db.runTransaction(SINGLE_ATTEMPT) { transaction ->
              val current = transaction.get(code)
              val storedToken = if (current.exists()) current.getString(TOKEN_FIELD) else null
              if (storedToken != replacing) RotateCodeResult.Done
              else RotateCodeResult.UnexpectedError
            }
            .await()
      } catch (e: FirebaseException) {
        if (e.isOfflineFailure()) RotateCodeResult.NetworkError
        else RotateCodeResult.UnexpectedError
      } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        RotateCodeResult.UnexpectedError
      } catch (e: Exception) {
        RotateCodeResult.UnexpectedError
      }

  /** The chats' offline check (`UNAVAILABLE`, `DEADLINE_EXCEEDED`) for any Firebase failure. */
  private fun FirebaseException.isOfflineFailure() =
      (this as? FirebaseFirestoreException)?.isOffline() == true

  private fun FirebaseException.isDenied() =
      this is FirebaseFirestoreException &&
          code == FirebaseFirestoreException.Code.PERMISSION_DENIED

  private fun codeDocument(eventId: String) =
      db.collection(EVENTS_COLLECTION)
          .document(eventId)
          .collection(CHECK_IN_CODE_COLLECTION)
          .document(ENTRANCE_CODE_DOCUMENT)

  /** The status a stored code [snapshot] shows, or null when its fields can't be read. */
  private fun codeStatusOf(snapshot: DocumentSnapshot): EntranceCodeStatus? {
    if (!snapshot.exists()) return EntranceCodeStatus.Missing
    val token = snapshot.get(TOKEN_FIELD) as? String ?: return null
    val previous = snapshot.get(PREVIOUS_TOKEN_FIELD)
    if (previous != null && previous !is String) return null
    val rotatedAt =
        snapshot.getTimestamp(ROTATED_AT_FIELD, ServerTimestampBehavior.ESTIMATE) ?: return null
    return EntranceCodeStatus.Code(
        EntranceCode(token, previous as String?, rotatedAt.toInstant()),
        snapshot.metadata.isFromCache,
    )
  }

  /** The status a stored check-in [snapshot] shows, or null when its time can't be read. */
  private fun statusOf(snapshot: DocumentSnapshot): CheckInStatus? =
      if (!snapshot.exists()) CheckInStatus.NotCheckedIn
      else
          snapshot.getTimestamp(CHECKED_IN_AT_FIELD, ServerTimestampBehavior.ESTIMATE)?.let {
            CheckInStatus.CheckedIn(it.toInstant())
          }

  private companion object {
    const val TOKEN_FIELD = "token"
    const val PREVIOUS_TOKEN_FIELD = "previousToken"
    const val ROTATED_AT_FIELD = "rotatedAt"

    /** For the read after a refused rotation, so a network failure answers at once. */
    val SINGLE_ATTEMPT: TransactionOptions = TransactionOptions.Builder().setMaxAttempts(1).build()

    /** The SDK's own number of attempts, which retries a commit another write overtook. */
    val DEFAULT_ATTEMPTS: TransactionOptions = TransactionOptions.Builder().build()
  }
}
