// Contributors: Claude (tested the one-off check); OpenAI Codex (tested connectivity and fallback,
// #234).
package com.polysocial.model.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
  fun registrationFailureFallsBackToTheCurrentConnection() = runTest {
    activeNetworkHas(NET_CAPABILITY_INTERNET, NET_CAPABILITY_VALIDATED)
    val active = connectivity.activeNetwork!!
    val capabilities = connectivity.getNetworkCapabilities(active)
    val failing =
        monitorWithRegistrationFailure(IllegalStateException("Callback unavailable")) {
          every { activeNetwork } returns active
          every { getNetworkCapabilities(active) } returns capabilities
        }
    assertTrue(failing.observeOnline().first())
  }

  @Test
  fun deniedNetworkPermissionReportsOffline() = runTest {
    val denied = SecurityException("Network permission denied")
    val failing = monitorWithRegistrationFailure(denied) { every { activeNetwork } throws denied }
    assertFalse(failing.observeOnline().first())
  }

  @Test
  fun fatalErrorsAndCancellationPropagate() {
    listOf(AssertionError("Test fatal error"), CancellationException("Test cancellation"))
        .forEach { failure ->
          val failing = monitorWithRegistrationFailure(failure)
          val thrown =
              assertThrows(failure.javaClass) { runBlocking { failing.observeOnline().first() } }
          assertEquals(failure.message, thrown.message)
        }
  }

  private fun monitorWithRegistrationFailure(
      failure: Throwable,
      setup: ConnectivityManager.() -> Unit = {},
  ): AndroidNetworkMonitor {
    val manager = mockk<ConnectivityManager>().apply(setup)
    every {
      manager.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>())
    } throws failure
    val context = mockk<Context>()
    every { context.getSystemService(ConnectivityManager::class.java) } returns manager
    return AndroidNetworkMonitor(context)
  }

  @Test
  fun noActiveNetwork_isOffline() {
    shadowOf(connectivity).setDefaultNetworkActive(false)

    assertFalse(monitor.isOnline())
  }
}
