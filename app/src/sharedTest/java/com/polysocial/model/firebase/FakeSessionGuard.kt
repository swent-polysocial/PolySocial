// Contributors: Claude (Opus 5.5, Anthropic) wrote this test fake, #198; Mohamed Khellaf (reviewed
// and revised).
package com.polysocial.model.firebase

import kotlinx.coroutines.CompletableDeferred

/**
 * [SessionGuard] for tests: returns [result] without touching any cache, and records each call. Set
 * [gate] to keep [ensureSession] suspended until the test completes it, to check that nothing reads
 * before the guard is done.
 */
class FakeSessionGuard(var result: SessionGuardResult = SessionGuardResult.Ready) : SessionGuard {
  var gate: CompletableDeferred<Unit>? = null

  /** The UIDs passed to [ensureSession], in call order. */
  val requestedUids = mutableListOf<String?>()

  val calls: Int
    get() = requestedUids.size

  override suspend fun ensureSession(uid: String?): SessionGuardResult {
    requestedUids += uid
    gate?.await()
    return result
  }
}
