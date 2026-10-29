// Contributors: Claude Opus 5.5 (drafted the registration interface, #121).
package com.polysocial.model.registration

import kotlinx.coroutines.flow.Flow

/** Outcome of [RegistrationRepository.register]. */
sealed interface RegisterResult {
  /** The student is registered, now or already before (registering twice changes nothing). */
  data object Registered : RegisterResult

  /** No user is signed in. Nothing was written. */
  data object NotSignedIn : RegisterResult

  /**
   * There is no event the student can read under this ID: it never existed, it was deleted, or it
   * is private and the student isn't allowed in. These look the same on purpose, like
   * `EventResult.NotFound`. Nothing was written.
   */
  data object NotFound : RegisterResult

  /** The device is offline. Nothing was written or queued, so the student can try again. */
  data object NetworkError : RegisterResult

  /** Any other failure, for example a write the Security Rules deny. Nothing was written. */
  data object UnexpectedError : RegisterResult
}

/** Outcome of [RegistrationRepository.cancel]. */
sealed interface CancelRegistrationResult {
  /** The student isn't registered anymore, or never was (cancelling twice changes nothing). */
  data object Cancelled : CancelRegistrationResult

  /** No user is signed in. Nothing was deleted. */
  data object NotSignedIn : CancelRegistrationResult

  /** The device is offline. Nothing was deleted or queued. */
  data object NetworkError : CancelRegistrationResult

  /** Any other failure. Nothing was deleted. */
  data object UnexpectedError : CancelRegistrationResult
}

/** What [RegistrationRepository.observeMyRegistrations] emits. */
sealed interface MyRegistrationsResult {
  /**
   * The IDs of the events the signed-in student is registered for. An event may have been deleted
   * or become unreadable since; the caller reads each event and skips those.
   *
   * @property fromCache true when the IDs come from Firestore's offline cache, for example while
   *   the device is offline. They may then be out of date.
   */
  data class Registrations(val eventIds: Set<String>, val fromCache: Boolean) :
      MyRegistrationsResult

  /** The registrations couldn't be read: nobody is signed in, or the query failed. */
  data object Error : MyRegistrationsResult
}

/**
 * Stores students' registrations for events ("I'm going") in `events/{eventId}/attendees/{uid}`,
 * one document per registered student, so an event's organizers can count its attendees.
 *
 * Only a student registers, for an event they can read, and only for themselves; the Security Rules
 * enforce it. The event's capacity isn't checked yet (#121's second part).
 */
interface RegistrationRepository {
  /**
   * Registers the signed-in student for the event [eventId].
   *
   * The checks run in this order, and each one stops before anything is written: an ID that can't
   * be a document ID returns [RegisterResult.NotFound], a missing user
   * [RegisterResult.NotSignedIn], and an offline device [RegisterResult.NetworkError]. Firestore
   * would queue a write made offline instead of failing, so the repository must not start one.
   * Then, in one step, the event is read and the registration written if it isn't there yet.
   */
  suspend fun register(eventId: String): RegisterResult

  /**
   * Removes the signed-in student's registration for the event [eventId], if there is one. It works
   * even once the event is deleted or no longer readable, so a student can always remove their own
   * registration.
   *
   * Like [register], a missing user returns [CancelRegistrationResult.NotSignedIn] and an offline
   * device [CancelRegistrationResult.NetworkError], before anything is deleted. An ID that can't be
   * a document ID has no registration, so it is [CancelRegistrationResult.Cancelled].
   */
  suspend fun cancel(eventId: String): CancelRegistrationResult

  /**
   * Observes the events the signed-in student is registered for, for "I'm going" and My events.
   *
   * The flow emits on every change, including from the offline cache while the device is offline. A
   * slow collector gets only the latest result. It ends after a [MyRegistrationsResult.Error],
   * which is always delivered first. The user is read when collection starts, and cancelling the
   * collection stops listening.
   */
  fun observeMyRegistrations(): Flow<MyRegistrationsResult>
}
