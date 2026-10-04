// Contributors: Claude Opus 5.5 (test fake for app-start routing, #32; reused by #34).
package com.polysocial.model.user

import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [UserProfileRepository] for tests. Set the next [getProfileResult], check how often
 * [getProfile] was called and with which uid. Set [gate] to keep [getProfile] suspended until the
 * test completes it, to observe the state while the profile is loading.
 */
class FakeUserProfileRepository(
    var getProfileResult: ProfileResult = ProfileResult.NotFound,
) : UserProfileRepository {
  var gate: CompletableDeferred<Unit>? = null

  var getProfileCalls = 0
    private set

  var lastRequestedUid: String? = null
    private set

  override suspend fun getProfile(uid: String): ProfileResult {
    getProfileCalls++
    lastRequestedUid = uid
    gate?.await()
    return getProfileResult
  }
}
