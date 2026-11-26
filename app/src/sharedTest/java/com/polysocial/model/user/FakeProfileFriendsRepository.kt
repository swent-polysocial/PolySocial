// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #450 under Lucas Pinto Ricardo's supervision and
// approval).
package com.polysocial.model.user

/** Supplies distinct server and offline responses for profile screen tests. */
class FakeProfileFriendsRepository : ProfileFriendsRepository {
  var online: ProfileFriendsResult = ProfileFriendsResult.Found(emptyList())
  var cached: ProfileFriendsResult = ProfileFriendsResult.Offline

  override suspend fun read(profile: PublicProfile, online: Boolean): ProfileFriendsResult =
      if (online) this.online else cached
}
