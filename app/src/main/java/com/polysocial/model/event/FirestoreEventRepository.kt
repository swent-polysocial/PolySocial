// Contributors: Claude (drafted the Firestore event repository).
package com.polysocial.model.event

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.network.NetworkMonitor
import java.time.Clock
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
 * @param clock the current time used to reject events that start in the past.
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

  private fun FirebaseException.isOffline() =
      this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.UNAVAILABLE
}
