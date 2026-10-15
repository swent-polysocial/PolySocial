// Contributors: OpenAI Codex (deterministic location fake for #51 tests).
package com.polysocial.model.location

/** Controlled location and permission outcomes; never reads device location or shows a dialog. */
class FakeLocationService(
    var permissionGranted: Boolean = false,
    var result: LocationResult = LocationResult.Unavailable,
) : LocationService {
  var permissionRequested = false
    private set

  var requestCount = 0
    private set

  override fun hasPermission(): Boolean = permissionGranted

  override fun shouldRequestPermission(): Boolean = !permissionGranted && !permissionRequested

  override fun markPermissionRequested() {
    permissionRequested = true
  }

  override suspend fun currentLocation(): LocationResult {
    if (!permissionGranted) return LocationResult.PermissionDenied
    requestCount++
    return result
  }
}
