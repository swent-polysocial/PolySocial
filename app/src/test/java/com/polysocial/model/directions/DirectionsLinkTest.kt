// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of the directions link, #391).
package com.polysocial.model.directions

import com.polysocial.model.event.Coordinates
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectionsLinkTest {

  private val defaultLocale = Locale.getDefault()

  @After fun restoreLocale() = Locale.setDefault(defaultLocale)

  @Test
  fun theLinkIsAGeoUriOfTheDestination() {
    assertEquals(
        "geo:46.519100,6.566800?q=46.519100,6.566800",
        directionsLink(Coordinates(46.5191, 6.5668)),
    )
  }

  @Test
  fun aSouthernAndWesternPlaceKeepsItsMinusSigns() {
    assertEquals(
        "geo:-33.868800,-151.209300?q=-33.868800,-151.209300",
        directionsLink(Coordinates(-33.8688, -151.2093)),
    )
  }

  @Test
  fun theLimitsOfTheGlobeAreStillPlaces() {
    assertEquals(
        "geo:90.000000,-180.000000?q=90.000000,-180.000000",
        directionsLink(Coordinates(90.0, -180.0)),
    )
  }

  @Test
  fun aPhoneInFrenchStillWritesADotAndNoScientificNotation() {
    Locale.setDefault(Locale.FRANCE)
    assertEquals(
        "geo:0.000010,6.566800?q=0.000010,6.566800",
        directionsLink(Coordinates(0.00001, 6.5668)),
    )
  }

  @Test
  fun aCoordinateThatIsNotANumberHasNoLink() {
    assertNull(directionsLink(Coordinates(Double.NaN, 6.5668)))
    assertNull(directionsLink(Coordinates(46.5191, Double.POSITIVE_INFINITY)))
  }

  @Test
  fun aCoordinateOutsideTheGlobeHasNoLink() {
    assertNull(directionsLink(Coordinates(90.1, 6.5668)))
    assertNull(directionsLink(Coordinates(46.5191, -180.1)))
  }
}
