// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of the directions link, #391).
package com.polysocial.model.directions

import com.polysocial.model.event.Coordinates
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransitDirectionsTest {

  private val defaultLocale = Locale.getDefault()

  @After fun restoreLocale() = Locale.setDefault(defaultLocale)

  @Test
  fun theLinkCarriesTheDestinationAndThePublicTransportMode() {
    assertEquals(
        "https://www.google.com/maps/dir/?api=1&destination=46.519100,6.566800&travelmode=transit",
        transitDirectionsLink(Coordinates(46.5191, 6.5668)),
    )
  }

  @Test
  fun aSouthernAndWesternPlaceKeepsItsMinusSigns() {
    assertEquals(
        "https://www.google.com/maps/dir/?api=1&destination=-33.868800,-151.209300&travelmode=transit",
        transitDirectionsLink(Coordinates(-33.8688, -151.2093)),
    )
  }

  @Test
  fun theLimitsOfTheGlobeAreStillPlaces() {
    assertEquals(
        "https://www.google.com/maps/dir/?api=1&destination=90.000000,-180.000000&travelmode=transit",
        transitDirectionsLink(Coordinates(90.0, -180.0)),
    )
  }

  @Test
  fun aPhoneInFrenchStillWritesADotAndNoScientificNotation() {
    Locale.setDefault(Locale.FRANCE)
    assertEquals(
        "https://www.google.com/maps/dir/?api=1&destination=0.000010,6.566800&travelmode=transit",
        transitDirectionsLink(Coordinates(0.00001, 6.5668)),
    )
  }

  @Test
  fun aCoordinateThatIsNotANumberHasNoLink() {
    assertNull(transitDirectionsLink(Coordinates(Double.NaN, 6.5668)))
    assertNull(transitDirectionsLink(Coordinates(46.5191, Double.POSITIVE_INFINITY)))
  }

  @Test
  fun aCoordinateOutsideTheGlobeHasNoLink() {
    assertNull(transitDirectionsLink(Coordinates(90.1, 6.5668)))
    assertNull(transitDirectionsLink(Coordinates(46.5191, -180.1)))
  }
}
