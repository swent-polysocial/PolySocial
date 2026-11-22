// Contributors: Claude (Sonnet 5.5, Anthropic) (the boundary that opens directions, #391).
package com.polysocial.model.directions

import com.polysocial.model.event.Coordinates

/**
 * Opens public-transport directions to a place in the phone's maps app. It only hands the
 * destination over: it never reads the phone's location and sends nothing of the student's.
 */
fun interface DirectionsLauncher {
  /** Returns false when nothing could open the directions, so the caller can say so. */
  fun openTransitDirections(destination: Coordinates): Boolean
}
