// Contributors: Claude Opus 5.5 (test fake for app-start routing, #32; create and update, and a
// create gate for the profile step, #34); Claude (the public profile repair, #291).
package com.polysocial.model.user

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [UserProfileRepository] for tests. Set the next result of each operation, and check how
 * often each one was called and with what. Set [gate] (or [createGate]) to keep [getProfile] (or
 * [createProfile]) suspended until the test completes it, to observe the loading (or saving) state.
 * Set [ensureGate] to keep [ensurePublicProfile] suspended, and read [ensureCancellations] to see
 * whether the caller cancelled it while it was waiting.
 */
class FakeUserProfileRepository(
    var getProfileResult: ProfileResult = ProfileResult.NotFound,
) : UserProfileRepository {
  var createProfileResult: CreateProfileResult = CreateProfileResult.Created
  var updateProfileResult: UpdateProfileResult = UpdateProfileResult.Updated
  var ensurePublicProfileResult: EnsurePublicProfileResult = EnsurePublicProfileResult.NotNeeded

  var gate: CompletableDeferred<Unit>? = null
  var createGate: CompletableDeferred<Unit>? = null
  var ensureGate: CompletableDeferred<Unit>? = null

  var getProfileCalls = 0
    private set

  var lastRequestedUid: String? = null
    private set

  /** The profiles passed to [createProfile], in call order. */
  val createdProfiles = mutableListOf<UserProfile>()

  /** The profiles passed to [updateProfile], in call order. */
  val updatedProfiles = mutableListOf<UserProfile>()

  /** The UIDs passed to [ensurePublicProfile], in call order. */
  val ensuredUids = mutableListOf<String>()

  /** How many [ensurePublicProfile] calls were cancelled while waiting on [ensureGate]. */
  var ensureCancellations = 0
    private set

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

  override suspend fun ensurePublicProfile(uid: String): EnsurePublicProfileResult {
    ensuredUids += uid
    try {
      ensureGate?.await()
    } catch (cancelled: CancellationException) {
      ensureCancellations++
      throw cancelled
    }
    return ensurePublicProfileResult
  }
}
