// Contributors: Claude (Sonnet 5.5, Anthropic) (the directions link, #391).
package com.polysocial.model.directions

import com.polysocial.model.event.Coordinates
import java.util.Locale

/**
 * The link that opens [destination] in the phone's maps app, or null when [destination] isn't a
 * point on Earth (a NaN or out-of-range coordinate read from a stored event), so the caller hides
 * the button instead of opening a nonsense place.
 *
 * It is a standard Android `geo:` link, so no particular app, key or service is involved: the phone
 * opens whichever maps app the student uses, which shows the place and offers the way there from
 * the phone's own position. The link names only the destination. The numbers are written with a dot
 * and six decimals (about 10 cm) whatever the phone's language, and never in scientific notation.
 */
fun directionsLink(destination: Coordinates): String? {
  val (latitude, longitude) = destination
  if (!latitude.isFinite() || !longitude.isFinite()) return null
  if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
  val point = "${degrees(latitude)},${degrees(longitude)}"
  return "geo:$point?q=$point"
}

private fun degrees(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
