// Contributors: OpenAI Codex (known-distance and invalid-coordinate tests for #51).
package com.polysocial.model.location

import com.polysocial.model.event.Coordinates
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DistanceTest {
  @Test
  fun samePoint_hasZeroDistance() {
    val point = Coordinates(46.5191, 6.5668)
    assertEquals(0.0, straightLineDistanceMeters(point, point), 0.0)
  }

  @Test
  fun oneDegreeOnEquator_isAbout111Kilometers() {
    assertEquals(
        111_194.9266,
        straightLineDistanceMeters(Coordinates(0.0, 0.0), Coordinates(0.0, 1.0)),
        0.001,
    )
  }

  @Test
  fun londonToNewYork_isAbout5570Kilometers() {
    assertEquals(
        5_570_222.18,
        straightLineDistanceMeters(Coordinates(51.5074, -0.1278), Coordinates(40.7128, -74.0060)),
        1.0,
    )
  }

  @Test
  fun reversingEndpoints_keepsDistance() {
    val from = Coordinates(46.5191, 6.5668)
    val to = Coordinates(46.5197, 6.6323)
    assertEquals(straightLineDistanceMeters(from, to), straightLineDistanceMeters(to, from), 0.001)
  }

  @Test
  fun crossingDateLine_usesShortArc() {
    assertEquals(
        222_389.8533,
        straightLineDistanceMeters(Coordinates(0.0, 179.0), Coordinates(0.0, -179.0)),
        0.001,
    )
  }

  @Test
  fun antipodalPoints_haveFiniteHalfCircumferenceDistance() {
    assertEquals(
        PI * 6_371_000.0,
        straightLineDistanceMeters(Coordinates(0.0, 0.0), Coordinates(0.0, 180.0)),
        0.001,
    )
  }

  @Test
  fun antipodalRoundoff_keepsDistanceFinite() {
    val distance =
        straightLineDistanceMeters(
            Coordinates(0.08, 6.0),
            Coordinates(-0.08, -174.0),
        )
    assertEquals(PI * 6_371_000.0, distance, 0.001)
  }

  @Test
  fun northToSouthPole_acceptsBoundaryLatitudes() {
    assertEquals(
        PI * 6_371_000.0,
        straightLineDistanceMeters(Coordinates(90.0, -180.0), Coordinates(-90.0, 180.0)),
        0.001,
    )
  }

  @Test
  fun invalidCoordinates_areRejectedAtEitherEndpoint() {
    val valid = Coordinates(0.0, 0.0)
    val invalid =
        listOf(
            Coordinates(90.01, 0.0),
            Coordinates(-90.01, 0.0),
            Coordinates(0.0, 180.01),
            Coordinates(0.0, -180.01),
            Coordinates(Double.NaN, 0.0),
            Coordinates(Double.POSITIVE_INFINITY, 0.0),
            Coordinates(Double.NEGATIVE_INFINITY, 0.0),
            Coordinates(0.0, Double.NaN),
            Coordinates(0.0, Double.POSITIVE_INFINITY),
            Coordinates(0.0, Double.NEGATIVE_INFINITY),
        )
    invalid.forEach { point ->
      assertThrows(IllegalArgumentException::class.java) {
        straightLineDistanceMeters(point, valid)
      }
      assertThrows(IllegalArgumentException::class.java) {
        straightLineDistanceMeters(valid, point)
      }
    }
  }
}
