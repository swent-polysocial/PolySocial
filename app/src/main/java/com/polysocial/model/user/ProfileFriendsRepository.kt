// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #450 under Lucas Pinto Ricardo's supervision and
// approval).
package com.polysocial.model.user

/** What the caller may show for a student's profile friends list. */
sealed interface ProfileFriendsResult {
  /** The complete list of friend UIDs, or the last confirmed list when [fromCache] is true. */
  data class Found(val uids: List<String>, val fromCache: Boolean = false) : ProfileFriendsResult

  data object Denied : ProfileFriendsResult

  data object Offline : ProfileFriendsResult

  data object Error : ProfileFriendsResult
}

/** Reads the friends listed under a public profile through that profile's access rules. */
interface ProfileFriendsRepository {
  /** Falls back to a previously confirmed Firestore list when the server can't be reached. */
  suspend fun read(profile: PublicProfile, online: Boolean): ProfileFriendsResult
}
