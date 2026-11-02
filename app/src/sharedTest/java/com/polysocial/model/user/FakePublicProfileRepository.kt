// Contributors: Claude (test fake for public profiles, #229; keyed by the requested UID, after
// review, #229).
package com.polysocial.model.user

/**
 * In-memory [PublicProfileRepository] for tests. Put profiles in [profiles], keyed by UID; set
 * [failure] to make every read fail with that result's kind instead. Each call is recorded in
 * [requestedUids].
 */
class FakePublicProfileRepository(
    val profiles: MutableMap<String, PublicProfile> = mutableMapOf(),
) : PublicProfileRepository {
  /** When set, every read fails: [Failure.Offline] as a `NetworkError`, otherwise unexpected. */
  var failure: Failure? = null

  enum class Failure {
    Offline,
    Unexpected,
  }

  /** The UIDs each call asked for, in call order. */
  val requestedUids = mutableListOf<Collection<String>>()

  override suspend fun getPublicProfile(uid: String): PublicProfileResult {
    requestedUids += listOf(uid)
    return when (failure) {
      Failure.Offline -> PublicProfileResult.NetworkError
      Failure.Unexpected -> PublicProfileResult.UnexpectedError
      null -> profiles[uid]?.let { PublicProfileResult.Found(it) } ?: PublicProfileResult.NotFound
    }
  }

  override suspend fun getPublicProfiles(uids: Collection<String>): PublicProfilesResult {
    requestedUids += uids
    return when (failure) {
      Failure.Offline -> PublicProfilesResult.NetworkError
      Failure.Unexpected -> PublicProfilesResult.UnexpectedError
      null ->
          PublicProfilesResult.Found(
              uids.toSet().mapNotNull { uid -> profiles[uid]?.let { uid to it } }.toMap()
          )
    }
  }
}
