// Contributors: Claude (drafted the network check).
package com.polysocial.model.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

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
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }
}
