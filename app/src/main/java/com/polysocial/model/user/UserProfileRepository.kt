// Contributors: Claude (profile lookup needed by app-start routing, #32; profile model, create and
// update, #34).
package com.polysocial.model.user

import java.time.Instant

/** Whether an account belongs to a student or to an association, stored as [value]. */
enum class AccountType(val value: String) {
  STUDENT("student"),
  ASSOCIATION("association"),
}

/**
 * A profile document `users/{uid}`, private to its owner. [section] and [year] are codes such as
 * `IN` and `BA3`. [createdAt] is set by the server when the profile is created, so it is `null` in
 * a profile that hasn't been saved yet.
 */
data class UserProfile(
    val uid: String,
    val email: String,
    val displayName: String,
    val section: String,
    val year: String,
    val accountType: AccountType = AccountType.STUDENT,
    val createdAt: Instant? = null,
)

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
   * [UserProfile.createdAt]. Offline, nothing is queued and [CreateProfileResult.NetworkError] is
   * returned, so a profile is never left half-created.
   */
  suspend fun createProfile(profile: UserProfile): CreateProfileResult

  /**
   * Saves the fields a user can edit ([UserProfile.displayName], [UserProfile.section] and
   * [UserProfile.year]) in the existing `users/{profile.uid}`. The other fields never change after
   * creation. Offline, nothing is queued and [UpdateProfileResult.NetworkError] is returned.
   */
  suspend fun updateProfile(profile: UserProfile): UpdateProfileResult
}
