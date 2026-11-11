// Contributors: Claude (wrote the one-off check tests); OpenAI Codex (tested live connectivity,
// #234).
package com.polysocial.model.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@OptIn(ExperimentalCoroutinesApi::class)
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
  fun observesValidationAndLossUntilCollectionStops() = runTest {
    activeNetworkHas(NET_CAPABILITY_INTERNET)
    val observed = mutableListOf<Boolean>()
    val collecting = backgroundScope.launch { monitor.observeOnline().collect { observed += it } }
    runCurrent()
    val platform = shadowOf(connectivity)
    val callback = platform.networkCallbacks.single()
    val network = connectivity.activeNetwork!!
    val validated = ShadowNetworkCapabilities.newInstance()
    shadowOf(validated).addCapability(NET_CAPABILITY_INTERNET)
    shadowOf(validated).addCapability(NET_CAPABILITY_VALIDATED)

    callback.onCapabilitiesChanged(network, validated)
    runCurrent()
    callback.onLost(network)
    runCurrent()

    assertEquals(listOf(false, true, false), observed)
    collecting.cancelAndJoin()
    assertTrue(platform.networkCallbacks.isEmpty())
  }

  @Test
  fun noActiveNetwork_isOffline() {
    shadowOf(connectivity).setDefaultNetworkActive(false)

    assertFalse(monitor.isOnline())
  }
}
