// Contributors: OpenAI Codex (deterministic location fake for #51 tests).
// Contributors: OpenAI Codex (completed denial history for #278).
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

  override fun hasPermission(): Boolean {
    if (permissionGranted) denied = false
    return permissionGranted
  }

  override fun shouldRequestPermission(): Boolean = !permissionGranted && !permissionRequested

  override fun markPermissionRequested() {
    permissionRequested = true
  }

  private var denied = false

  override fun wasPermissionDenied(): Boolean = denied

  override fun recordPermissionResult(granted: Boolean) {
    denied = !granted
  }

  override suspend fun currentLocation(): LocationResult {
    if (!permissionGranted) return LocationResult.PermissionDenied
    requestCount++
    return result
  }
}
