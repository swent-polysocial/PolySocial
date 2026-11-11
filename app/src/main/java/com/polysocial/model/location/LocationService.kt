// Contributors: OpenAI Codex (one-off location contract, #51; distinguish dismissed prompts, #278).
package com.polysocial.model.location

import com.polysocial.model.event.Coordinates

/**
 * Outcome of a single foreground location request; map browsing remains available in every case.
 */
sealed interface LocationResult {
  /** A single coordinate snapshot, kept only in screen memory for straight-line distance badges. */
  data class Available(val coordinates: Coordinates) : LocationResult

  /** Approximate location is not allowed, including permission revoked mid-request. */
  data object PermissionDenied : LocationResult

  /**
   * Location is disabled, no suitable provider exists, or no valid fix arrived before the timeout.
   */
  data object Unavailable : LocationResult
}

/**
 * Foreground, one-off device location access for Map and Events. No location history is stored or
 * sent to a server. The Map asks only for approximate permission, which is sufficient for distance
 * badges. The service uses coarse-capable providers and never requests precise or background
 * access.
 */
interface LocationService {
  /**
   * Whether approximate permission is granted; observing a grant clears previous denial history.
   */
  fun hasPermission(): Boolean

  /** Whether the first Map entry should show the permission prompt. */
  fun shouldRequestPermission(): Boolean

  /** Records the prompt before launching it, so later Map entries never prompt automatically. */
  fun markPermissionRequested()

  /** Whether Android returned an actual denial, rather than a dismissed/interrupted dialog. */
  fun wasPermissionDenied(): Boolean

  /** Records a completed Android permission result; a cancelled dialog must not call this. */
  fun recordPermissionResult(granted: Boolean)

  /**
   * Reads one coordinate snapshot, bounded by a timeout. Cancellation stops the platform request
   * and propagates to the caller. The service never requests a permission dialog itself.
   */
  suspend fun currentLocation(): LocationResult
}
