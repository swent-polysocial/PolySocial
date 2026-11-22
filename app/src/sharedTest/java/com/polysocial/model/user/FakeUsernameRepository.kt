// Contributors: Claude (Opus 5.5, Anthropic) (wrote this fake for tests, #448).
package com.polysocial.model.user

/**
 * In-memory [UsernameRepository] for tests. It follows the [UsernameRepository] contracts and checks
 * in the order the Firestore repository must: the name's format ([usernameError]), then
 * [lookUpFailure] or [setFailure] (like an offline device), then the signed-in student, then who
 * holds the name.
 *
 * It doesn't change any [UserProfile]: a screen test that shows the new name also sets it in its
 * [FakeUserProfileRepository].
 *
 * @property currentUid the signed-in student, or null when nobody is signed in.
 */
class FakeUsernameRepository(var currentUid: String? = "test-student") : UsernameRepository {
  /** The names held, each with the UID of the student who holds it. */
  val names: MutableMap<String, String> = mutableMapOf()

  /** When set, [lookUp] returns this result instead of reading, like a failed read. */
  var lookUpFailure: UsernameLookupResult? = null

  /** When set, [setUsername] returns this result instead of writing, like a failed write. */
  var setFailure: SetUsernameResult? = null

  /** The names passed to [lookUp], in call order. */
  val lookedUp = mutableListOf<String>()

  /** The names passed to [setUsername], in call order. */
  val setNames = mutableListOf<String>()

  override suspend fun lookUp(name: String): UsernameLookupResult {
    lookedUp += name
    if (usernameError(name) != null) return UsernameLookupResult.NotFound
    lookUpFailure?.let {
      return it
    }
    return names[name]?.let { UsernameLookupResult.Found(it) } ?: UsernameLookupResult.NotFound
  }

  override suspend fun setUsername(name: String): SetUsernameResult {
    setNames += name
    usernameError(name)?.let {
      return SetUsernameResult.Invalid(it)
    }
    setFailure?.let {
      return it
    }
    val uid = currentUid ?: return SetUsernameResult.UnexpectedError
    val holder = names[name]
    if (holder != null) return if (holder == uid) SetUsernameResult.Saved else SetUsernameResult.Taken
    names.values.removeAll { it == uid }
    names[name] = uid
    return SetUsernameResult.Saved
  }
}
