// Contributors: Claude Opus 5.5 (wrote this fake for tests, #121; matched the real checks' order
// after review).
package com.polysocial.model.registration

import com.polysocial.model.event.isReadableEventId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * In-memory [RegistrationRepository] for tests. It follows the [RegistrationRepository] contracts
 * and checks in the same order as `FirestoreRegistrationRepository`: an ID that can't be a document
 * ID, then the signed-in user, then [registerFailure] or [cancelFailure], then the stored
 * registrations.
 * Only the events in [readableEventIds] can be registered for; any other ID is
 * [RegisterResult.NotFound], like a missing or private event.
 *
 * @property currentUid the signed-in student, or null when nobody is signed in.
 */
class FakeRegistrationRepository(var currentUid: String? = "test-student") :
    RegistrationRepository {
  /** The events a student may register for, like the events they can read. */
  val readableEventIds = mutableSetOf<String>()

  /** The stored registrations, as event ID to the UIDs of its registered students. */
  private val attendees = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

  /** When set, [register] returns this result instead of registering, like a failed write. */
  var registerFailure: RegisterResult? = null

  /** When set, [cancel] returns this result instead of cancelling, like a failed write. */
  var cancelFailure: CancelRegistrationResult? = null

  /** When true, [observeMyRegistrations] emits [MyRegistrationsResult.Error], like a failed query. */
  var readFailure = false

  /** The `fromCache` flag [observeMyRegistrations] reports, to simulate being offline. */
  var readFromCache = false

  /** The UIDs registered for [eventId]. */
  fun attendeesOf(eventId: String): Set<String> = attendees.value[eventId].orEmpty()

  override suspend fun register(eventId: String): RegisterResult {
    if (!isReadableEventId(eventId)) return RegisterResult.NotFound
    val uid = currentUid ?: return RegisterResult.NotSignedIn
    registerFailure?.let {
      return it
    }
    if (eventId !in readableEventIds) return RegisterResult.NotFound
    attendees.value += eventId to (attendeesOf(eventId) + uid)
    return RegisterResult.Registered
  }

  override suspend fun cancel(eventId: String): CancelRegistrationResult {
    if (!isReadableEventId(eventId)) return CancelRegistrationResult.Cancelled
    val uid = currentUid ?: return CancelRegistrationResult.NotSignedIn
    cancelFailure?.let {
      return it
    }
    attendees.value += eventId to (attendeesOf(eventId) - uid)
    return CancelRegistrationResult.Cancelled
  }

  override fun observeMyRegistrations(): Flow<MyRegistrationsResult> {
    val uid = currentUid
    if (uid == null || readFailure) return flowOf(MyRegistrationsResult.Error)
    return attendees.map { stored ->
      MyRegistrationsResult.Registrations(
          stored.filterValues { uid in it }.keys,
          readFromCache,
      )
    }
  }
}
