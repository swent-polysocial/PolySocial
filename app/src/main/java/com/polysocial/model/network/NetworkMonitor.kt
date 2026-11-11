// Contributors: Claude (drafted the network check); OpenAI Codex (added observation for the roster,
// #234).
package com.polysocial.model.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Tells whether the device can reach the internet right now.
 *
 * Firestore queues writes made offline instead of failing them, so a repository that must report an
 * offline failure (for example `EventRepository.createEvent`) checks this before starting a write.
 */
interface NetworkMonitor {
  /** True if the device has a network connection with working internet access. */
  fun isOnline(): Boolean

  /** Emits the current connection and any changes while collected. Static monitors emit once. */
  fun observeOnline(): Flow<Boolean> = flow { emit(isOnline()) }
}
