// Contributors: Claude (drafted the network check).
package com.polysocial.model.network

/**
 * Tells whether the device can reach the internet right now.
 *
 * Firestore queues writes made offline instead of failing them, so a repository that must report an
 * offline failure (for example `EventRepository.createEvent`) checks this before starting a write.
 * It is a one-off check: nothing is monitored in the background.
 */
interface NetworkMonitor {
  /** True if the device has a network connection with working internet access. */
  fun isOnline(): Boolean
}
