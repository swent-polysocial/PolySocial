// Contributors: Claude (Sonnet 5.5, Anthropic) (the Directions button's state, #391).
package com.polysocial.ui.event.detail

import androidx.lifecycle.ViewModel
import com.polysocial.model.directions.DirectionsLauncher
import com.polysocial.model.event.Coordinates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A "no app could open the directions" message, with an [id] that tells two of them apart, so a
 * second tap that fails the same way shows its Snackbar again.
 */
data class DirectionsUnavailable(val id: Long)

/**
 * The Directions button (#391): opens public-transport directions to a place through [launcher],
 * and reports [unavailable] when nothing could open them. It is separate from the event detail's
 * ViewModel because it needs no event data of its own (the screen passes the place), so the full
 * screen map (#389) can use it as it is. It is not a Hilt ViewModel: [EventDetailRoute] creates it
 * with the Android launcher, so the existing Route tests, which have no Hilt, keep working.
 */
class DirectionsViewModel(private val launcher: DirectionsLauncher) : ViewModel() {

  private var nextId = 0L

  private val _unavailable = MutableStateFlow<DirectionsUnavailable?>(null)
  val unavailable: StateFlow<DirectionsUnavailable?> = _unavailable.asStateFlow()

  /** Opens directions to [destination], or reports [unavailable] if nothing could open them. */
  fun onDirections(destination: Coordinates) {
    _unavailable.value =
        if (launcher.openTransitDirections(destination)) null else DirectionsUnavailable(nextId++)
  }

  /** Marks [shown] as shown, unless a newer message has replaced it meanwhile. */
  fun onUnavailableShown(shown: DirectionsUnavailable) {
    _unavailable.compareAndSet(shown, null)
  }
}
