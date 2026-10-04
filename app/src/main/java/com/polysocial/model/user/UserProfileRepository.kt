// Contributors: Claude (profile lookup needed by app-start routing, #32; extended by #34).
package com.polysocial.model.user

/** A student's profile document `users/{uid}`. #34 adds the remaining fields. */
data class UserProfile(val uid: String, val email: String)

/** Outcome of [UserProfileRepository.getProfile]. */
sealed interface ProfileResult {
  data class Found(val profile: UserProfile) : ProfileResult

  /** The user is verified but has not completed the profile step yet. */
  data object NotFound : ProfileResult

  data object NetworkError : ProfileResult

  data object UnexpectedError : ProfileResult
}

/**
 * Reads and writes user profiles. #34 adds createProfile, updateProfile and the Firestore version.
 */
interface UserProfileRepository {
  suspend fun getProfile(uid: String): ProfileResult
}

/**
 * Temporary [UserProfileRepository] that reports every profile as found, so Log in's routing can be
 * built and tested before #34 adds the real Firestore repository (see #32's dependency note).
 */
class PendingUserProfileRepository : UserProfileRepository {
  override suspend fun getProfile(uid: String): ProfileResult =
      ProfileResult.Found(UserProfile(uid = uid, email = ""))
}
