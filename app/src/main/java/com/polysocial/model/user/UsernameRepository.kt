// Contributors: Claude (Opus 5.5, Anthropic) (drafted the username repository contract, #448).
package com.polysocial.model.user

/** Outcome of [UsernameRepository.lookUp]. */
sealed interface UsernameLookupResult {
  /**
   * The name belongs to the student [uid]. A deleted account's name is free at once and someone
   * else may claim it, so show that student's card before sending a request (Sprint 6 PO, #445).
   */
  data class Found(val uid: String) : UsernameLookupResult

  /** Nobody holds the name. A name [usernameError] refuses is never held, so it isn't read. */
  data object NotFound : UsernameLookupResult

  /**
   * The server couldn't be reached. The cache isn't used: a name may have changed hands since the
   * device last read it.
   */
  data object NetworkError : UsernameLookupResult

  /** Any other failure, for example a read the Security Rules deny to an association. */
  data object UnexpectedError : UsernameLookupResult
}

/** Outcome of [UsernameRepository.setUsername]. */
sealed interface SetUsernameResult {
  /**
   * The signed-in student now holds the name, and their previous one, if any, is free. Setting the
   * name they already hold writes nothing.
   */
  data object Saved : SetUsernameResult

  /** Another student holds the name. Nothing was written. */
  data object Taken : SetUsernameResult

  /** [usernameError] refuses the name. Nothing was read or written. */
  data class Invalid(val error: UsernameError) : SetUsernameResult

  /** The server couldn't be reached or didn't confirm the write. Nothing was written or queued. */
  data object NetworkError : SetUsernameResult

  /**
   * Any other failure. Nothing was written. This covers nobody signed in, no `users/{uid}` yet, an
   * association's account (associations get no username), and a write the Security Rules deny, for
   * example from a deleted account.
   */
  data object UnexpectedError : SetUsernameResult
}

/**
 * Usernames (#366, Architecture Diagram 3.9): `usernames/{name}` holds the `uid` of the student who
 * holds the name, and that student's `users/{uid}.username` and `publicProfiles/{uid}.username`
 * name it, so the ID makes each name unique. The Security Rules let a student get one name, never
 * list them, so there is no student directory, and tie a name and its profile both ways, so nobody
 * keeps two names. `deleteAccount` frees the name of a deleted account.
 *
 * A student reads their own name from [UserProfile.username], and another student's from
 * [PublicProfile.username]. Only [setUsername] writes them.
 */
interface UsernameRepository {
  /**
   * The student who holds [name], one `get` of `usernames/{name}` from the server. [name] must
   * already be normalized ([normalizeUsername]). Never search as the student types: one call per
   * submitted name (#452).
   */
  suspend fun lookUp(name: String): UsernameLookupResult

  /**
   * Gives the signed-in student the username [name] (already normalized, see [normalizeUsername]),
   * in one transaction: it creates `usernames/{name}`, sets `username` in their private and public
   * profiles and, if they held another name, deletes it, so it is free at once. Offline, nothing is
   * queued and [SetUsernameResult.NetworkError] is returned.
   */
  suspend fun setUsername(name: String): SetUsernameResult
}
