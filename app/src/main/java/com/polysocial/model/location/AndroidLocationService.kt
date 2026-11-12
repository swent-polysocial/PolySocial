// Contributors: OpenAI Codex (rationale-aware results and review regressions, #278).
// Contributors: OpenAI Codex (one-off coarse location access and review fixes for #51).
// Contributors: OpenAI Codex (dismissal-aware permission history and fused fallback, #278).
package com.polysocial.model.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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

  override suspend fun currentLocation(): LocationResult {
    val signal = CancellationSignal()
    return try {
      if (!hasPermission()) return LocationResult.PermissionDenied
      val manager =
          context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
              ?: return LocationResult.Unavailable
      // Android's fused provider accepts coarse access and can use an emulator's injected fix,
      // even when its network provider is present but has no cell/Wi-Fi fix.
      // Never fall back to GPS, which requires precise permission on older Android versions.
      val provider =
          when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) ->
                LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> return LocationResult.Unavailable
          }
      withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
          continuation.invokeOnCancellation { signal.cancel() }
          LocationManagerCompat.getCurrentLocation(
              manager,
              provider,
              signal,
              ContextCompat.getMainExecutor(context),
          ) { location ->
            if (continuation.isActive) {
              val latitude = location?.latitude
              val longitude = location?.longitude
              val result =
                  if (
                      latitude != null &&
                          longitude != null &&
                          latitude.isFinite() &&
                          longitude.isFinite() &&
                          latitude in -90.0..90.0 &&
                          longitude in -180.0..180.0
                  ) {
                    LocationResult.Available(Coordinates(latitude, longitude))
                  } else {
                    LocationResult.Unavailable
                  }
              continuation.resume(result)
            }
          }
        }
      } ?: LocationResult.Unavailable
    } catch (_: SecurityException) {
      LocationResult.PermissionDenied
    } catch (_: IllegalArgumentException) {
      // A provider may disappear after the enabled check.
      LocationResult.Unavailable
    } catch (_: Exception) {
      currentCoroutineContext().ensureActive()
      LocationResult.Unavailable
    } finally {
      signal.cancel()
    }
  }

  private companion object {
    const val PERMISSION_REQUESTED = "permission_requested"
    const val PERMISSION_DENIED = "permission_denied"
    const val LOCATION_TIMEOUT_MILLIS = 15_000L
  }
}
