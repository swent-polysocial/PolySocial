// Contributors: OpenAI Codex (cancellable one-off Android location access for #51).
package com.polysocial.model.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.polysocial.model.event.Coordinates
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Uses Android's existing location providers for one foreground fix. The request is cancelled on
 * completion, timeout or caller cancellation. Only a permission-prompt boolean is persisted;
 * coordinates are never logged or stored. No background permission or subscription is used.
 */
class AndroidLocationService @Inject constructor(@ApplicationContext private val context: Context) :
    LocationService {
  private val preferences =
      context.getSharedPreferences("map_location_permission", Context.MODE_PRIVATE)

  override fun hasPermission(): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED ||
          ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
              PackageManager.PERMISSION_GRANTED

  override fun shouldRequestPermission(): Boolean =
      !hasPermission() && !preferences.getBoolean(PERMISSION_REQUESTED, false)

  override fun markPermissionRequested() {
    preferences.edit().putBoolean(PERMISSION_REQUESTED, true).apply()
  }

  override suspend fun currentLocation(): LocationResult {
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
    ) {
      return LocationResult.PermissionDenied
    }
    val manager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return LocationResult.Unavailable
    val signal = CancellationSignal()
    return try {
      // Prefer the network provider: approximate permission works and it normally resolves indoors.
      val provider =
          when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED &&
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
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
    } finally {
      signal.cancel()
    }
  }

  private companion object {
    const val PERMISSION_REQUESTED = "permission_requested"
    const val LOCATION_TIMEOUT_MILLIS = 15_000L
  }
}
