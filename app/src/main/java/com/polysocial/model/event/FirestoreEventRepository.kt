// Contributors: OpenAI Codex (refresh Firestore after cache reset, #109).
// Contributors: Claude (drafted the Firestore event repository; verified association badge, #45;
// the public upcoming-events listener, #49; unexpected failures reported as errors, #182; the
// single-event listener and its failures, #112).
package com.polysocial.model.event

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.TransactionOptions
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfileRepository
import java.time.Clock
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

/** Firestore collection holding the events, one document per event. */
const val EVENTS_COLLECTION = "events"

/**
 * [EventRepository] backed by Firestore, following the [EventRepository.createEvent] contract: the
 * event is validated, then the signed-in user and the connection are checked, then the creator's
 * profile is read for the association badge, and only then is the event written.
 *
 * The write runs in a transaction. Firestore only runs transactions against the server, so if the
 * connection drops after the [NetworkMonitor] check, the write fails with a network error instead
 * of being queued and finished later. The transaction is tried only once: it only creates a new
 * document, so it can't conflict with another write, and retries would just delay the offline
 * error.
 *
 * @param clock the current time, used to reject events that start in the past and to place the
 *   upcoming-events window.
 */
class FirestoreEventRepository(
    private val database: () -> FirebaseFirestore,
    private val auth: AuthRepository,
    private val profiles: UserProfileRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) : EventRepository {
  constructor(
      db: FirebaseFirestore,
      auth: AuthRepository,
      profiles: UserProfileRepository,
      network: NetworkMonitor,
      clock: Clock,
  ) : this({ db }, auth, profiles, network, clock)

  private val db: FirebaseFirestore
    get() = database()

  override suspend fun createEvent(event: Event): CreateEventResult {
    val errors = validateNewEvent(event, clock.instant())
    if (errors.isNotEmpty()) return CreateEventResult.Invalid(errors)
    val user = auth.currentUser() ?: return CreateEventResult.NotSignedIn
    if (!network.isOnline()) return CreateEventResult.NetworkError
    val isVerifiedAssociation =
        when (val profile = profiles.getProfile(user.uid)) {
          is ProfileResult.Found -> profile.profile.isVerifiedAssociation
          ProfileResult.NotFound -> false
          ProfileResult.NetworkError -> return CreateEventResult.NetworkError
          ProfileResult.UnexpectedError -> return CreateEventResult.UnexpectedError
        }

    return try {
      val document = db.collection(EVENTS_COLLECTION).document()
      val fields = event.withCreator(user.uid, isVerifiedAssociation).toFirestoreMap()
      db.runTransaction(SINGLE_ATTEMPT) { it.set(document, fields) }.await()
      CreateEventResult.Created(document.id)
    } catch (e: FirebaseException) {
      if (e.isOffline()) CreateEventResult.NetworkError else CreateEventResult.UnexpectedError
    } catch (e: CancellationException) {
      // A task cancelled while the caller still runs is a failure; a cancelled caller rethrows.
      currentCoroutineContext().ensureActive()
      CreateEventResult.UnexpectedError
    } catch (e: Exception) {
      CreateEventResult.UnexpectedError
    }
  }

  /**
   * Listens to the query Firestore needs for [isUpcomingPublicEvent]: `isPrivate == false` (the
   * Security Rules reject a query that could return a private event) and a `startTime` range. This
   * equality plus range needs the composite index in `firebase/firestore/firestore.indexes.json`.
   *
   * Metadata changes are included, so the flow also emits when only [PublicEventsResult.Events]'s
   * `fromCache` changes, for example when the device comes back online.
   *
   * The flow is conflated: a slow collector gets only the latest result, never a backlog of
   * outdated snapshots. Sending never fails or blocks the listener's callback, so the terminal
   * [PublicEventsResult.Error] replaces any result still waiting and always reaches the collector
   * before the flow ends. Any failure, including an unexpected exception while building the query
   * or reading a snapshot, ends the flow with that error instead of throwing.
   */
  override fun getUpcomingPublicEvents(windowDays: Int): Flow<PublicEventsResult> {
    require(windowDays > 0) { "windowDays must be positive, was $windowDays" }
    return callbackFlow {
          fun fail() {
            trySend(PublicEventsResult.Error)
            close()
          }
          // Nothing in this try suspends, so it can't catch the collector's cancellation, and
          // awaitClose still ends a cancelled collection.
          val registration =
              try {
                val now = clock.instant()
                db.collection(EVENTS_COLLECTION)
                    .whereEqualTo("isPrivate", false)
                    .whereGreaterThanOrEqualTo("startTime", now.toTimestamp())
                    .whereLessThan("startTime", upcomingWindowEnd(now, windowDays).toTimestamp())
                    .orderBy("startTime")
                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                      if (error != null || snapshot == null) fail()
                      else
                          try {
                            val events =
                                snapshot.documents.mapNotNull {
                                  eventFromFirestore(it.id, it.data.orEmpty())
                                }
                            trySend(
                                PublicEventsResult.Events(events, snapshot.metadata.isFromCache)
                            )
                          } catch (e: Exception) {
                            // Thrown here, it would crash the app on Firestore's callback thread.
                            fail()
                          }
                    }
              } catch (e: Exception) {
                fail()
                null
              }
          awaitClose { registration?.remove() }
        }
        .buffer(Channel.CONFLATED)
  }

  /**
   * Listens to `events/[id]`. Metadata changes are included, so the flow also emits when only
   * [EventResult.Found]'s `fromCache` changes, for example when the device comes back online.
   *
   * A missing document is [EventResult.NotCached] when the snapshot comes from the cache (offline,
   * and never read before) and [EventResult.NotFound] when it comes from the server. Any listener
   * error, in practice a read the Security Rules deny, is [EventResult.NotFound]: Firestore stops a
   * listener after an error, so the flow ends. #52's read rule checks the document's fields, so a
   * document that doesn't exist may also reach the app as a denied read, with the same result. Like
   * [getUpcomingPublicEvents], the flow is conflated and the final result is never dropped, and an
   * unexpected failure while registering the listener or reading a snapshot (#182) is also
   * [EventResult.NotFound] and ends the flow, instead of crashing the app.
   */
  override fun getEvent(id: String): Flow<EventResult> {
    if (!isReadableEventId(id)) return flowOf(EventResult.NotFound)
    return callbackFlow {
          fun notFound() {
            trySend(EventResult.NotFound)
            close()
          }
          // Like getUpcomingPublicEvents (#182): nothing in this try suspends, so it can't catch
          // the
          // collector's cancellation, and awaitClose still ends a cancelled collection.
          val registration =
              try {
                db.collection(EVENTS_COLLECTION).document(id).addSnapshotListener(
                    MetadataChanges.INCLUDE
                ) { snapshot, error ->
                  if (error != null || snapshot == null) notFound()
                  else
                      try {
                        val fromCache = snapshot.metadata.isFromCache
                        val event =
                            if (snapshot.exists()) eventFromFirestore(id, snapshot.data.orEmpty())
                            else null
                        trySend(
                            when {
                              event != null -> EventResult.Found(event, fromCache)
                              !snapshot.exists() && fromCache -> EventResult.NotCached
                              else -> EventResult.NotFound
                            }
                        )
                      } catch (e: Exception) {
                        // Thrown here, it would crash the app on Firestore's callback thread.
                        notFound()
                      }
                }
              } catch (e: Exception) {
                // For example a client terminated at logout (#185): ending beats crashing.
                notFound()
                null
              }
          awaitClose { registration?.remove() }
        }
        .buffer(Channel.CONFLATED)
  }

  private fun FirebaseException.isOffline() =
      this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.UNAVAILABLE

  private companion object {
    val SINGLE_ATTEMPT: TransactionOptions = TransactionOptions.Builder().setMaxAttempts(1).build()
  }
}
