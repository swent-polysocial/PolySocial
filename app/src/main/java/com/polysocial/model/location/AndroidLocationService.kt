// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #469 under Lucas Pinto Ricardo’s supervision and
// approval).
// Contributors: OpenAI Codex (rationale-aware results and review regressions, #278).
// Contributors: OpenAI Codex (one-off coarse location access and review fixes for #51).
// Contributors: OpenAI Codex (dismissal-aware permission history and fused fallback, #278).
package com.polysocial.model.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import com.polysocial.model.event.Coordinates
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Uses Android's existing location providers for one foreground fix. The request is cancelled on
 * completion, timeout or caller cancellation. Only permission-history booleans are persisted;
 * coordinates are never logged or stored. No background permission or subscription is used.
 */
class AndroidLocationService @Inject constructor(@ApplicationContext private val context: Context) :
    LocationService {
  private val preferences =
      context.getSharedPreferences("map_location_permission", Context.MODE_PRIVATE)

  override fun hasPermission(): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED

  override fun shouldRequestPermission(): Boolean =
      !hasPermission() && !preferences.getBoolean(PERMISSION_REQUESTED, false)

  override fun markPermissionRequested() {
    preferences.edit { putBoolean(PERMISSION_REQUESTED, true) }
  }

  override fun wasPermissionDenied(): Boolean =
      preferences.getBoolean(PERMISSION_DENIED, false) && !hasPermission()

  override fun recordPermissionResult(granted: Boolean?, showRationale: Boolean) {
    // Back can return false too. Only a rationale establishes a first genuine refusal; a later
    // no-rationale refusal preserves it, allowing the explicit action to open Settings.
    val denied =
        when {
          granted == true -> false
          granted == false && showRationale -> true
          else -> return
        }
    if (preferences.getBoolean(PERMISSION_DENIED, false) != denied) {
      preferences.edit { putBoolean(PERMISSION_DENIED, denied) }
    }
  }

  override suspend fun currentLocation(): LocationResult =
      when (val result = readLocation(precise = false)) {
        is PreciseLocationResult.Available -> LocationResult.Available(result.coordinates)
        PreciseLocationResult.PermissionDenied -> LocationResult.PermissionDenied
        PreciseLocationResult.Unavailable,
        PreciseLocationResult.TimedOut -> LocationResult.Unavailable
      }

  override suspend fun currentPreciseLocation(): PreciseLocationResult =
      readLocation(precise = true)

  private suspend fun readLocation(precise: Boolean): PreciseLocationResult {
    val signal = CancellationSignal()
    return try {
      val granted =
          if (precise) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
          } else hasPermission()
      if (!granted) return PreciseLocationResult.PermissionDenied
      val manager =
          context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
              ?: return PreciseLocationResult.Unavailable
      val provider = provider(manager, precise) ?: return PreciseLocationResult.Unavailable
      withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
          continuation.invokeOnCancellation { signal.cancel() }
          LocationManagerCompat.getCurrentLocation(
              manager,
              provider,
              signal,
              ContextCompat.getMainExecutor(context),
          ) { location ->
            if (continuation.isActive) continuation.resume(fixResult(location, precise))
          }
        }
      } ?: PreciseLocationResult.TimedOut
    } catch (_: SecurityException) {
      PreciseLocationResult.PermissionDenied
    } catch (_: IllegalArgumentException) {
      // A provider may disappear after the enabled check.
      PreciseLocationResult.Unavailable
    } catch (_: Exception) {
      currentCoroutineContext().ensureActive()
      PreciseLocationResult.Unavailable
    } finally {
      signal.cancel()
    }
  }

  private fun provider(manager: LocationManager, precise: Boolean): String? {
    if (precise) return LocationManager.GPS_PROVIDER.takeIf(manager::isProviderEnabled)
    // Fused accepts coarse permission and an emulator's injected fix. Map and Events never probe
    // GPS, even when the app has precise permission for check-in.
    return when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
          manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) ->
          LocationManager.FUSED_PROVIDER
      manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
          LocationManager.NETWORK_PROVIDER
      else -> null
    }
  }

  private fun fixResult(location: Location?, precise: Boolean): PreciseLocationResult {
    if (location == null) return PreciseLocationResult.Unavailable
    val latitude = location.latitude
    val longitude = location.longitude
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
      return PreciseLocationResult.Unavailable
    }
    val accuracy = location.accuracy.toDouble()
    if (precise && (!location.hasAccuracy() || !accuracy.isFinite() || accuracy < 0.0)) {
      return PreciseLocationResult.Unavailable
    }
    return PreciseLocationResult.Available(Coordinates(latitude, longitude), accuracy)
  }

  private companion object {
    const val PERMISSION_REQUESTED = "permission_requested"
    const val PERMISSION_DENIED = "permission_denied"
    const val LOCATION_TIMEOUT_MILLIS = 15_000L
  }
}
