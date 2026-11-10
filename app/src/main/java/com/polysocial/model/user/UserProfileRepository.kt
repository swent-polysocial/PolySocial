// Contributors: Claude (profile lookup needed by app-start routing, #32; profile model, create and
// update, #34; isAssociationVerified, #45; the public profile written with it, #229; interests,
// #230; the missing public profile repaired, #291).
package com.polysocial.model.user

import java.time.Instant

/** Whether an account belongs to a student or to an association, stored as [value]. */
enum class AccountType(val value: String) {
  STUDENT("student"),
  ASSOCIATION("association"),
}

/**
 * A profile document `users/{uid}`, private to its owner. [section] and [year] are codes such as
 * `IN` and `BA3`. [interests] are codes from [INTERESTS], chosen on the profile step (#230); a
 * profile saved before then has none. [createdAt] is set by the server when the profile is created,
 * so it is `null` in a profile that hasn't been saved yet.
 *
 * [isAssociationVerified] is set only by a PolySocial admin, by hand in the Firebase console. The
 * app reads it but never writes it, and the Security Rules forbid users to set it (#35).
 */
data class UserProfile(
    val uid: String,
    val email: String,
    val displayName: String,
    val section: String,
    val year: String,
    val accountType: AccountType = AccountType.STUDENT,
    val isAssociationVerified: Boolean = false,
    val createdAt: Instant? = null,
    val interests: Set<String> = emptySet(),
) {
  /** Whether this account may publish events under its association's name, with the badge. */
  val isVerifiedAssociation: Boolean
    get() = accountType == AccountType.ASSOCIATION && isAssociationVerified
}

/** Outcome of [UserProfileRepository.getProfile]. */
sealed interface ProfileResult {
  data class Found(val profile: UserProfile) : ProfileResult

  /** The user is verified but has not completed the profile step yet. */
  data object NotFound : ProfileResult

  data object NetworkError : ProfileResult

  data object UnexpectedError : ProfileResult
}

/** Outcome of [UserProfileRepository.createProfile]. */
sealed interface CreateProfileResult {
  data object Created : CreateProfileResult

  /** A profile already exists for this uid; it was left unchanged. */
  data object AlreadyExists : CreateProfileResult

  /** The device is offline. Nothing was written or queued, so the user can try again. */
  data object NetworkError : CreateProfileResult

  /** Any other failure, for example a write the Security Rules deny. */
  data object UnexpectedError : CreateProfileResult
}

/** Outcome of [UserProfileRepository.updateProfile]. */
sealed interface UpdateProfileResult {
  data object Updated : UpdateProfileResult

  /** No profile exists for this uid yet; create it with [UserProfileRepository.createProfile]. */
  data object NotFound : UpdateProfileResult

  /** The device is offline. Nothing was written or queued, so the user can try again. */
  data object NetworkError : UpdateProfileResult

  data object UnexpectedError : UpdateProfileResult
}

/** Outcome of [UserProfileRepository.ensurePublicProfile]. */
sealed interface EnsurePublicProfileResult {
  /** The student had no public profile; it was created as a copy of their stored profile. */
  data object Created : EnsurePublicProfileResult

  /** Nothing to do: the public profile already exists, or the account is an association's. */
  data object NotNeeded : EnsurePublicProfileResult

  /** There is no `users/{uid}` to copy. */
  data object NotFound : EnsurePublicProfileResult

  /** The device is offline. Nothing was written or queued; the next app start tries again. */
  data object NetworkError : EnsurePublicProfileResult

  /**
   * Any other failure: an unreadable stored profile, or a write the Security Rules deny (for
   * example a deleted account's, or a stored section the app no longer offers). An interest code
   * removed from [INTERESTS] would be denied too: it is dropped from the copy but still stored in
   * `users/{uid}`, so the two lists differ. Every such failure is retried at each start, quietly.
   */
  data object UnexpectedError : EnsurePublicProfileResult
}

/**
 * Reads and writes profiles in `users/{uid}`, where the document ID is always the Firebase Auth
 * UID. The Security Rules let a user reach only their own document, and only once their email is
 * verified (#35).
 */
interface UserProfileRepository {
  /** The profile of [uid], or [ProfileResult.NotFound] if it hasn't been created yet. */
  suspend fun getProfile(uid: String): ProfileResult

  /**
   * Creates `users/{profile.uid}` from [profile], once: if the document already exists it is left
   * unchanged and [CreateProfileResult.AlreadyExists] is returned. The server sets
   * [UserProfile.createdAt]. A student's public profile (`publicProfiles/{uid}`, see
   * [PublicProfile]) is created in the same write; an association's isn't. Offline, nothing is
   * queued and [CreateProfileResult.NetworkError] is returned, so a profile is never left
   * half-created.
   */
  suspend fun createProfile(profile: UserProfile): CreateProfileResult

  /**
   * Saves the fields a user can edit ([UserProfile.displayName], [UserProfile.section],
   * [UserProfile.year] and [UserProfile.interests]) in the existing `users/{profile.uid}`, all of
   * them: start from the profile [getProfile] returned, or an edit of the name erases the
   * interests. The other fields never change after creation. Unless the stored profile is an
   * association's, the public profile is saved with the same values in the same write, and created
   * if it is missing (a profile saved before #229). Offline, nothing is queued and
   * [UpdateProfileResult.NetworkError] is returned.
   */
  suspend fun updateProfile(profile: UserProfile): UpdateProfileResult

  /**
   * Gives a student whose profile was saved before #229 their public profile (#291): if
   * `users/{uid}` is a student's and `publicProfiles/{uid}` is missing, creates it as a copy of the
   * stored profile, without the email, and changes nothing else. An existing public profile is
   * never overwritten, and an association never gets one ([EnsurePublicProfileResult.NotNeeded]).
   * App start calls it for every student, so it reads the two documents each time. The copy and its
   * check run in one transaction, so a concurrent edit can't make it stale; offline, nothing is
   * queued and [EnsurePublicProfileResult.NetworkError] is returned.
   */
  suspend fun ensurePublicProfile(uid: String): EnsurePublicProfileResult
}
