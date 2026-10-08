// Contributors: Claude (drafted the Firestore event repository and the public upcoming-events
// listener, #49).
package com.polysocial.model.event

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.network.NetworkMonitor
import java.time.Clock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Firestore collection holding the events, one document per event. */
const val EVENTS_COLLECTION = "events"

/**
 * [EventRepository] backed by Firestore, following the [EventRepository.createEvent] contract: the
 * event is validated, then the signed-in user and the connection are checked, and only then
 * written.
 *
 * The write runs in a transaction. Firestore only runs transactions against the server, so if the
 * connection drops after the [NetworkMonitor] check, the write fails with a network error instead
 * of being queued and finished later.
 *
 * Every event is created without the association badge for now: the creator's verified-association
 * status comes with the profile (#90).
 *
 * @param clock the current time, used to reject events that start in the past and to place the
 *   upcoming-events window.
 */
class FirestoreEventRepository(
    private val db: FirebaseFirestore,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : EventRepository {

  override suspend fun createEvent(event: Event): CreateEventResult {
    val errors = validateNewEvent(event, clock.instant())
    if (errors.isNotEmpty()) return CreateEventResult.Invalid(errors)
    val user = auth.currentUser() ?: return CreateEventResult.NotSignedIn
    if (!network.isOnline()) return CreateEventResult.NetworkError

    val document = db.collection(EVENTS_COLLECTION).document()
    val fields = event.withCreator(user.uid, isVerifiedAssociation = false).toFirestoreMap()
    return try {
      db.runTransaction { it.set(document, fields) }.await()
      CreateEventResult.Created(document.id)
    } catch (e: FirebaseException) {
      if (e.isOffline()) CreateEventResult.NetworkError else CreateEventResult.UnexpectedError
    }
  }

  /**
   * Listens to the query Firestore needs for [isUpcomingPublicEvent]: `isPrivate == false` (the
   * Security Rules reject a query that could return a private event) and a `startTime` range. This
   * equality plus range needs the composite index in `firebase/firestore/firestore.indexes.json`.
   *
   * Metadata changes are included, so the flow also emits when only [PublicEventsResult.Events]'s
   * `fromCache` changes, for example when the device comes back online.
   */
  override fun getUpcomingPublicEvents(windowDays: Int): Flow<PublicEventsResult> {
    require(windowDays > 0) { "windowDays must be positive, was $windowDays" }
    return callbackFlow {
      val now = clock.instant()
      val registration =
          db.collection(EVENTS_COLLECTION)
              .whereEqualTo("isPrivate", false)
              .whereGreaterThanOrEqualTo("startTime", now.toTimestamp())
              .whereLessThan("startTime", upcomingWindowEnd(now, windowDays).toTimestamp())
              .orderBy("startTime")
              .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null || snapshot == null) {
                  trySend(PublicEventsResult.Error)
                  close()
                } else {
                  val events =
                      snapshot.documents.mapNotNull { eventFromFirestore(it.id, it.data.orEmpty()) }
                  trySend(PublicEventsResult.Events(events, snapshot.metadata.isFromCache))
                }
              }
      awaitClose { registration.remove() }
    }
  }

  private fun FirebaseException.isOffline() =
      this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.UNAVAILABLE
}
