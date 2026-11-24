// Contributors: Claude (Sonnet 5.5, Anthropic) (the public-transport directions link, #391).
package com.polysocial.model.directions

import com.polysocial.model.event.Coordinates
import java.util.Locale

/**
 * The link that opens public-transport directions to [destination] in the phone's maps app, or null
 * when [destination] isn't a point on Earth (a NaN or out-of-range coordinate read from a stored
 * event), so the caller shows its message instead of opening a nonsense route.
 *
 * It is a plain "Google Maps URLs" link, so the app needs no key or service of its own: it names
 * only the destination and the transit travel mode, and the maps app finds the starting point (the
 * phone's own position), the nearest stop and the times. The numbers are written with a dot and six
 * decimals (about 10 cm) whatever the phone's language, and never in scientific notation.
 */
fun transitDirectionsLink(destination: Coordinates): String? {
  val (latitude, longitude) = destination
  if (!latitude.isFinite() || !longitude.isFinite()) return null
  if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
  return "https://www.google.com/maps/dir/?api=1&destination=" +
      "${degrees(latitude)},${degrees(longitude)}&travelmode=transit"
}

private fun degrees(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
