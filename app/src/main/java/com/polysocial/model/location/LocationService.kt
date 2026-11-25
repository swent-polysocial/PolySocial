// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #469 under Lucas Pinto Ricardo’s supervision and
// approval).
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
 * The outcome of one precise venue check. Only an available fix contains coordinates and reported
 * horizontal accuracy in meters.
 */
sealed interface PreciseLocationResult {
  data class Available(val coordinates: Coordinates, val accuracyMeters: Double) :
      PreciseLocationResult

  data object PermissionDenied : PreciseLocationResult

  data object Unavailable : PreciseLocationResult

  data object TimedOut : PreciseLocationResult
}

/**
 * One-off foreground location access. Map and Events use approximate location for distances;
 * check-in uses precise location after an explicit tap. No location history is stored or sent.
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

  /**
   * Reads one precise fix with its horizontal accuracy. The caller must have obtained precise
   * permission; this does not prompt, fall back to approximate access, or start a subscription.
   */
  suspend fun currentPreciseLocation(): PreciseLocationResult
}
