// Contributors: Claude (Sonnet 5.5, Anthropic) (opens the directions link with an Android intent,
// #391, following AndroidPrivacyContactLauncher).
package com.polysocial.model.directions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.polysocial.model.event.Coordinates

/** Opens [transitDirectionsLink] with the phone's own apps; no permission and no query needed. */
class AndroidDirectionsLauncher(private val context: Context) : DirectionsLauncher {
  override fun openTransitDirections(destination: Coordinates): Boolean {
    val link = transitDirectionsLink(destination) ?: return false
    return try {
      context.startActivity(
          Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
      true
    } catch (_: ActivityNotFoundException) {
      false
    } catch (_: SecurityException) {
      false
    }
  }
}
