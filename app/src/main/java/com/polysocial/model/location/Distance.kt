// Contributors: OpenAI Codex (straight-line distance calculation for #51).
package com.polysocial.model.location

import com.polysocial.model.event.Coordinates
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * Great-circle distance in meters between WGS 84 coordinates, using the haversine formula and a
 * spherical Earth. This is a straight-line distance, not a walking route or travel time.
 *
 * The haversine term is clamped to avoid rounding errors near antipodal points. Longitude changes
 * across the date line work without special handling because the trigonometric terms are periodic.
 *
 * @throws IllegalArgumentException if either coordinate is non-finite or outside latitude [-90, 90]
 *   / longitude [-180, 180].
 */
fun straightLineDistanceMeters(from: Coordinates, to: Coordinates): Double {
  requireValidCoordinates(from)
  requireValidCoordinates(to)
  val fromLatitude = Math.toRadians(from.latitude)
  val toLatitude = Math.toRadians(to.latitude)
  val latitudeHalfDelta = Math.toRadians(to.latitude - from.latitude) / 2.0
  val longitudeHalfDelta = Math.toRadians(to.longitude - from.longitude) / 2.0
  val latitudeSine = sin(latitudeHalfDelta)
  val longitudeSine = sin(longitudeHalfDelta)
  val haversine =
      (latitudeSine * latitudeSine +
              cos(fromLatitude) * cos(toLatitude) * longitudeSine * longitudeSine)
          .coerceIn(0.0, 1.0)
  return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(haversine), sqrt(1.0 - haversine))
}

private fun requireValidCoordinates(coordinates: Coordinates) {
  require(coordinates.latitude.isFinite() && coordinates.latitude in -90.0..90.0) {
    "Latitude must be finite and between -90 and 90 degrees."
  }
  require(coordinates.longitude.isFinite() && coordinates.longitude in -180.0..180.0) {
    "Longitude must be finite and between -180 and 180 degrees."
  }
}
