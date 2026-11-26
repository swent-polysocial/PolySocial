// Contributors: OpenAI Codex (GPT-6.2 Sol, high; profile visibility and bio for #446 under
// Lucas Pinto Ricardo’s supervision and approval).
// Contributors: Claude (public profile model and repository contract, #229; reads by UID only and
// what a missing profile means, after review, #229; interests, #230); Claude Opus 5.5 (the
// cache-only read, #368 review); Konstantinos Perdikeas (directed, reviewed and owns it).
// Contributors: OpenAI Codex (6.2 Sol, high; shared bounded profile lookup, #360 review).
// Contributors: Claude (Opus 5.5, Anthropic) (the username field, #448).
package com.polysocial.model.user

import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads a profile with a bounded server wait and checks the cache when offline, when the server
 * stalls, or when the server read reports a network error. A cache miss cannot confirm absence.
 */
internal suspend fun PublicProfileRepository.getPublicProfileWithCache(
    uid: String,
    timeoutMillis: Long,
    online: Boolean = true,
): PublicProfileResult {
  val result = if (online) withTimeoutOrNull(timeoutMillis) { getPublicProfile(uid) } else null
  return if (result == null || result == PublicProfileResult.NetworkError) {
    getCachedPublicProfile(uid) as? PublicProfileResult.Found ?: PublicProfileResult.NetworkError
  } else result
}

/**
 * What other students see of a student, `publicProfiles/{uid}` (#229): the fields matching needs
 * and a group shows, never the email. [uid] is the document ID, the student's Firebase Auth UID.
 * [section] and [year] are codes such as `IN` and `BA3`, as in [UserProfile]. [interests] are codes
 * from [INTERESTS] (#230), empty when the student picked none. [username] is the student's username
 * without the `@`, or null if they have none (#448). [profileVisibility] defaults to private, and
 * [bio] is visible to students at either setting.
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
    val username: String? = null,
    val profileVisibility: ProfileVisibility = ProfileVisibility.PRIVATE,
    val bio: String? = null,
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
   * The public profile of [uid] as this device last saved it, without asking the server: for a
   * screen whose server read stalled on a bad connection, before Firestore notices it is offline.
   * [PublicProfileResult.NetworkError] when it isn't saved. A [PublicProfileResult.NotFound] here
   * only means the device last saw no profile, which the server may no longer confirm. A repository
   * without an offline cache has nothing saved.
   */
  suspend fun getCachedPublicProfile(uid: String): PublicProfileResult =
      PublicProfileResult.NetworkError

  /**
   * The public profiles of [uids] (duplicates count once), one document read per UID, in parallel:
   * the Security Rules allow reads by UID only, never a list of the collection. No UIDs give an
   * empty [PublicProfilesResult.Found] without reading anything.
   */
  suspend fun getPublicProfiles(uids: Collection<String>): PublicProfilesResult
}
