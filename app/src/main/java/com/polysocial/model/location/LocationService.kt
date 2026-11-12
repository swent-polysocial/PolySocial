// Contributors: OpenAI Codex (rationale-aware results and review regressions, #278).
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
  /** Whether approximate permission is currently granted. This query never changes history. */
  fun hasPermission(): Boolean

  /** Whether the app has not prompted yet, so the first Map entry may prompt automatically. */
  fun shouldRequestPermission(): Boolean

  /** Records any Map/Events prompt before launching it; the automatic prompt is shared app-wide. */
  fun markPermissionRequested()

  /** Whether a genuine refusal was observed since the last explicitly observed grant. */
  fun wasPermissionDenied(): Boolean

  /**
   * Records a permission observation. Routes supply the post-callback rationale; ViewModels also
   * record a current grant or an already-visible rationale on resume or explicit retry. A first
   * false result without a rationale is ambiguous (for example Back), so it cannot establish
   * denial. A grant clears denial history; null/interrupted results leave it unchanged.
   */
  fun recordPermissionResult(granted: Boolean?, showRationale: Boolean)

  /**
   * Reads one coordinate snapshot, bounded by a timeout. Cancellation stops the platform request
   * and propagates to the caller. The service never requests a permission dialog itself.
   */
  suspend fun currentLocation(): LocationResult
}
