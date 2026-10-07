// Contributors: OpenAI Codex (permission, one-off fix and cancellation tests for #51).
package com.polysocial.model.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.Coordinates
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidLocationServiceTest {
  private val context = mockk<Context>()
  private val manager = mockk<LocationManager>()
  private val callback = slot<Consumer<Location?>>()
  private val signal = slot<CancellationSignal>()
  private val provider = slot<String>()
  private lateinit var service: AndroidLocationService

  @Before
  fun setUp() {
    val app = ApplicationProvider.getApplicationContext<Context>()
    val preferences = app.getSharedPreferences("location_test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns preferences
    every { context.getSystemService(Context.LOCATION_SERVICE) } returns manager
    mockkStatic(ContextCompat::class)
    mockkStatic(LocationManagerCompat::class)
    permission(coarse = false, fine = false)
    every { ContextCompat.getMainExecutor(context) } returns Executor { it.run() }
    every { manager.isProviderEnabled(any()) } returns false
    every {
      LocationManagerCompat.getCurrentLocation(
          manager,
          capture(provider),
          capture(signal),
          any(),
          capture(callback),
      )
    } answers { Unit }
    service = AndroidLocationService(context)
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  private fun permission(coarse: Boolean, fine: Boolean) {
    every {
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    } returns if (coarse) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    every {
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    } returns if (fine) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
  }

  private fun fix(latitude: Double = 1.0, longitude: Double = 2.0) =
      Location(LocationManager.NETWORK_PROVIDER).apply {
        this.latitude = latitude
        this.longitude = longitude
      }

  @Test
  fun deniedPermission_doesNotReadDeviceLocation() = runTest {
    assertEquals(LocationResult.PermissionDenied, service.currentLocation())
    verify(exactly = 0) { context.getSystemService(Context.LOCATION_SERVICE) }
    assertFalse(callback.isCaptured)
  }

  @Test
  fun permissionPrompt_isOfferedOnceAndSurvivesServiceRecreation() {
    assertTrue(service.shouldRequestPermission())
    service.markPermissionRequested()
    assertFalse(service.shouldRequestPermission())
    assertFalse(AndroidLocationService(context).shouldRequestPermission())
  }

  @Test
  fun permissionAlreadyGranted_doesNotRequestDialog() {
    permission(coarse = true, fine = false)
    assertTrue(service.hasPermission())
    assertFalse(service.shouldRequestPermission())
  }

  @Test
  fun fineOnlyPermission_doesNotRequestDialog() {
    permission(coarse = false, fine = true)
    assertTrue(service.hasPermission())
    assertFalse(service.shouldRequestPermission())
  }

  @Test
  fun coarseOnlyPermission_readsOneNetworkFixAndCancelsRequest() = runTest {
    permission(coarse = true, fine = false)
    every { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns true
    val request = async { service.currentLocation() }
    yield()
    assertEquals(LocationManager.NETWORK_PROVIDER, provider.captured)
    callback.captured.accept(fix())
    assertEquals(LocationResult.Available(Coordinates(1.0, 2.0)), request.await())
    assertTrue(signal.captured.isCanceled)
    verify(exactly = 0) { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
  }

  @Test
  fun coarseOnlyPermission_doesNotUseGpsWhenNetworkUnavailable() = runTest {
    permission(coarse = true, fine = false)
    every { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns true
    assertEquals(LocationResult.Unavailable, service.currentLocation())
    assertFalse(callback.isCaptured)
    verify(exactly = 0) { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
  }

  @Test
  fun precisePermission_usesGpsWhenNetworkUnavailable() = runTest {
    permission(coarse = false, fine = true)
    every { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns true
    val request = async { service.currentLocation() }
    yield()
    assertEquals(LocationManager.GPS_PROVIDER, provider.captured)
    callback.captured.accept(fix())
    assertEquals(LocationResult.Available(Coordinates(1.0, 2.0)), request.await())
  }

  @Test
  fun noEnabledProvider_returnsUnavailableWithoutRequest() = runTest {
    permission(coarse = true, fine = true)
    assertEquals(LocationResult.Unavailable, service.currentLocation())
    assertFalse(callback.isCaptured)
  }

  @Test
  fun missingLocationManager_returnsUnavailable() = runTest {
    permission(coarse = true, fine = false)
    every { context.getSystemService(Context.LOCATION_SERVICE) } returns null
    assertEquals(LocationResult.Unavailable, service.currentLocation())
  }

  @Test
  fun nullFix_returnsUnavailableAndCancelsRequest() = runTest {
    permission(coarse = true, fine = false)
    every { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns true
    val request = async { service.currentLocation() }
    yield()
    callback.captured.accept(null)
    assertEquals(LocationResult.Unavailable, request.await())
    assertTrue(signal.captured.isCanceled)
  }

  @Test
  fun invalidFix_returnsUnavailable() = runTest {
    permission(coarse = true, fine = false)
    every { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns true
    val invalidFixes =
        listOf(
            fix(Double.NaN),
            fix(91.0),
            fix(-91.0),
            fix(longitude = 181.0),
            fix(longitude = -181.0),
            fix(longitude = Double.POSITIVE_INFINITY),
        )
    invalidFixes.forEach { invalid ->
      val request = async { service.currentLocation() }
      yield()
      callback.captured.accept(invalid)
      assertEquals(LocationResult.Unavailable, request.await())
    }
  }

  @Test
  fun revokedPermission_returnsDeniedAndCancelsRequest() = runTest {
    permission(coarse = true, fine = false)
    every { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns true
    every {
      LocationManagerCompat.getCurrentLocation(
          manager,
          any(),
          capture(signal),
          any(),
          any<Consumer<Location>>(),
      )
    } throws SecurityException("permission revoked")
    assertEquals(LocationResult.PermissionDenied, service.currentLocation())
    assertTrue(signal.captured.isCanceled)
  }

  @Test
  fun disappearingProvider_returnsUnavailable() = runTest {
    permission(coarse = true, fine = false)
    every { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } throws
        IllegalArgumentException("provider gone")
    assertEquals(LocationResult.Unavailable, service.currentLocation())
  }

  @Test
  fun timeout_returnsUnavailableAndStopsPlatformRequest() = runTest {
    permission(coarse = true, fine = false)
    every { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns true
    assertEquals(LocationResult.Unavailable, service.currentLocation())
    assertTrue(signal.captured.isCanceled)
    assertEquals(15_000L, testScheduler.currentTime)
  }

  @Test
  fun callerCancellation_stopsPlatformRequestAndIgnoresLateCallback() = runTest {
    permission(coarse = true, fine = false)
    every { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns true
    val request = async { service.currentLocation() }
    yield()
    request.cancel()
    request.join()
    assertTrue(request.isCancelled)
    assertTrue(signal.captured.isCanceled)
    callback.captured.accept(fix())
    assertTrue(request.isCancelled)
  }
}
