// Contributors: OpenAI Codex (wrote this store fake in VerifyEmailViewModelTest.kt, #31); Claude
// (Opus 5.5, Anthropic) added its address-keyed timings, #225; Claude (moved it here unchanged so
// device tests can share it, #276).
package com.polysocial.auth

import com.polysocial.model.auth.VerificationStore
import com.polysocial.model.auth.VerificationTiming
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [VerificationStore] for unit and device tests: resend timings per UID and per address
 * (trimmed and lowercased, like the real store's digest). Set [failure], [clearFailure] or
 * [readGate] to make its operations fail or wait; by default they all succeed at once.
 */
class MemoryVerificationStore : VerificationStore {
  val timings = mutableMapOf<String, VerificationTiming>()
  /** Keyed by the normalised address, like the real store's digest. */
  val addressTimings = mutableMapOf<String, VerificationTiming>()
  var failure: Exception? = null
  var readGate: CompletableDeferred<Unit>? = null
  var clearFailure: Exception? = null

  override suspend fun read(uid: String): VerificationTiming {
    readGate?.await()
    failure?.let { throw it }
    return timings[uid] ?: VerificationTiming()
  }

  override suspend fun write(uid: String, timing: VerificationTiming) {
    failure?.let { throw it }
    timings[uid] = timing
  }

  override suspend fun clear(uid: String) {
    clearFailure?.let { throw it }
    timings.remove(uid)
  }

  override suspend fun readAddress(email: String): VerificationTiming {
    failure?.let { throw it }
    return addressTimings[email.trim().lowercase()] ?: VerificationTiming()
  }

  override suspend fun writeAddress(email: String, timing: VerificationTiming) {
    failure?.let { throw it }
    addressTimings[email.trim().lowercase()] = timing
  }

  override suspend fun clearAddress(email: String) {
    clearFailure?.let { throw it }
    addressTimings.remove(email.trim().lowercase())
  }
}
