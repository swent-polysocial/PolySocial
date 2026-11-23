// Contributors: Claude (Sonnet 5.5, Anthropic) (a fake of the directions launcher, #391).
package com.polysocial.model.directions

import com.polysocial.model.event.Coordinates

/** Records the places it was asked to open, and fails like a phone with no maps app on demand. */
class FakeDirectionsLauncher(var canOpen: Boolean = true) : DirectionsLauncher {
  val opened = mutableListOf<Coordinates>()

  override fun openDirections(destination: Coordinates): Boolean {
    opened += destination
    return canOpen
  }
}
