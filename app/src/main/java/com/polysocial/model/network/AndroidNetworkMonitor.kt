// Contributors: Claude (drafted the network check); OpenAI Codex (observed default-network changes,
// #234).
package com.polysocial.model.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [NetworkMonitor] backed by Android's [ConnectivityManager]. The device counts as online only if
 * its active network has internet access that Android has validated, so a Wi-Fi network stuck
 * behind a login page counts as offline.
 */
class AndroidNetworkMonitor @Inject constructor(@ApplicationContext context: Context) :
    NetworkMonitor {
  private val connectivity: ConnectivityManager =
      context.getSystemService(ConnectivityManager::class.java)

  override fun isOnline(): Boolean {
    val capabilities =
        connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
    return capabilities.hasValidatedInternet()
  }

  override fun observeOnline(): Flow<Boolean> = callbackFlow {
    val callback =
        object : ConnectivityManager.NetworkCallback() {
          override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            trySend(capabilities.hasValidatedInternet())
          }

          override fun onLost(network: Network) {
            trySend(false)
          }
        }
    connectivity.registerDefaultNetworkCallback(callback)
    try {
      trySend(isOnline())
      awaitClose()
    } finally {
      connectivity.unregisterNetworkCallback(callback)
    }
  }
      // If observation is unavailable, keep the one-off network check for this screen.
      .catch { emit(isOnline()) }
      .distinctUntilChanged()

  private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
      hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
          hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
