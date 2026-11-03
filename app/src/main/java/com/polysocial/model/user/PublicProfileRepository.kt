// Contributors: Claude (public profile model and repository contract, #229; reads by UID only and
// what a missing profile means, after review, #229; interests, #230).
package com.polysocial.model.user

/**
 * What other students see of a student, `publicProfiles/{uid}` (#229): the fields matching needs
 * and a group shows, never the email. [uid] is the document ID, the student's Firebase Auth UID.
 * [section] and [year] are codes such as `IN` and `BA3`, as in [UserProfile]. [interests] are codes
 * from [INTERESTS] (#230), empty when the student picked none.
 *
 * The Security Rules let any student read it and associations none, and let its owner write it only
 * as a copy of their `users/{uid}` in the same write, so it is written by [UserProfileRepository],
 * never on its own.
 */
data class PublicProfile(
    val uid: String,
    val displayName: String,
    val section: String,
    val year: String,
    val interests: Set<String> = emptySet(),
)

/** Outcome of [PublicProfileRepository.getPublicProfile]. */
sealed interface PublicProfileResult {
  data class Found(val profile: PublicProfile) : PublicProfileResult

  /**
   * There is no public profile for this UID: a deleted account, an association, or a student whose
   * profile was saved before #229 and not since. So a missing profile doesn't mean the account was
   * deleted.
   */
  data object NotFound : PublicProfileResult

  /** The device is offline and the profile isn't in the offline cache. */
  data object NetworkError : PublicProfileResult

  /** Any other failure, including a stored document with a missing or wrong-typed field. */
  data object UnexpectedError : PublicProfileResult
}

/** Outcome of [PublicProfileRepository.getPublicProfiles]. */
sealed interface PublicProfilesResult {
  /**
   * The public profiles found, by UID. A UID without a public profile (see
   * [PublicProfileResult.NotFound]), or whose stored document is malformed, is left out, so one bad
   * document doesn't hide a whole group.
   */
  data class Found(val profiles: Map<String, PublicProfile>) : PublicProfilesResult

  /**
   * The device is offline and at least one of the profiles isn't in the offline cache, so whether
   * it exists is unknown: no partial answer is given.
   */
  data object NetworkError : PublicProfilesResult

  /** Any other failure, for example an association reading, which the Security Rules deny. */
  data object UnexpectedError : PublicProfilesResult
}

/**
 * Reads the public profiles in `publicProfiles/{uid}` (#229), for matching (#231) and for showing a
 * group's members (#233). Only students may read them; for an association every read fails with an
 * `UnexpectedError`. Writing goes through [UserProfileRepository], with the private profile.
 */
interface PublicProfileRepository {
  /** The public profile of [uid], or [PublicProfileResult.NotFound] if it has none. */
  suspend fun getPublicProfile(uid: String): PublicProfileResult

  /**
   * The public profiles of [uids] (duplicates count once), one document read per UID, in parallel:
   * the Security Rules allow reads by UID only, never a list of the collection. No UIDs give an
   * empty [PublicProfilesResult.Found] without reading anything.
   */
  suspend fun getPublicProfiles(uids: Collection<String>): PublicProfilesResult
}
