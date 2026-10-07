// Contributors: Claude Opus 5.5 (test fake for app-start routing, #32; create and update, and a
// create gate for the profile step, #34).
package com.polysocial.model.user

import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [UserProfileRepository] for tests. Set the next result of each operation, and check how
 * often each one was called and with what. Set [gate] (or [createGate]) to keep [getProfile] (or
 * [createProfile]) suspended until the test completes it, to observe the loading (or saving) state.
 */
class FakeUserProfileRepository(
    var getProfileResult: ProfileResult = ProfileResult.NotFound,
) : UserProfileRepository {
  var createProfileResult: CreateProfileResult = CreateProfileResult.Created
  var updateProfileResult: UpdateProfileResult = UpdateProfileResult.Updated

  var gate: CompletableDeferred<Unit>? = null
  var createGate: CompletableDeferred<Unit>? = null

  var getProfileCalls = 0
    private set

  var lastRequestedUid: String? = null
    private set

  /** The profiles passed to [createProfile], in call order. */
  val createdProfiles = mutableListOf<UserProfile>()

  /** The profiles passed to [updateProfile], in call order. */
  val updatedProfiles = mutableListOf<UserProfile>()

  override suspend fun getProfile(uid: String): ProfileResult {
    getProfileCalls++
    lastRequestedUid = uid
    gate?.await()
    return getProfileResult
  }

  override suspend fun createProfile(profile: UserProfile): CreateProfileResult {
    createdProfiles += profile
    createGate?.await()
    return createProfileResult
  }

  override suspend fun updateProfile(profile: UserProfile): UpdateProfileResult {
    updatedProfiles += profile
    return updateProfileResult
  }
}
