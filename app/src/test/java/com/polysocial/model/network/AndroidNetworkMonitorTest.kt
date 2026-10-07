// Contributors: Claude (wrote these tests).
package com.polysocial.model.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(AndroidJUnit4::class)
class AndroidNetworkMonitorTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val connectivity = context.getSystemService(ConnectivityManager::class.java)
  private val monitor = AndroidNetworkMonitor(context)

  private fun activeNetworkHas(vararg capabilities: Int) {
    val networkCapabilities = ShadowNetworkCapabilities.newInstance()
    capabilities.forEach { shadowOf(networkCapabilities).addCapability(it) }
    shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, networkCapabilities)
  }

  @Test
  fun validatedInternet_isOnline() {
    activeNetworkHas(NET_CAPABILITY_INTERNET, NET_CAPABILITY_VALIDATED)

    assertTrue(monitor.isOnline())
  }

  @Test
  fun internetNotValidatedYet_isOffline() {
    activeNetworkHas(NET_CAPABILITY_INTERNET)

    assertFalse(monitor.isOnline())
  }

  @Test
  fun networkWithoutInternet_isOffline() {
    activeNetworkHas(NET_CAPABILITY_VALIDATED)

    assertFalse(monitor.isOnline())
  }

  @Test
  fun noActiveNetwork_isOffline() {
    shadowOf(connectivity).setDefaultNetworkActive(false)

    assertFalse(monitor.isOnline())
  }
}
