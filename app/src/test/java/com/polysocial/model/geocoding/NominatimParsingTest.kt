// Contributors: Claude (drafted the Nominatim parsing tests, #111).
package com.polysocial.model.geocoding

import com.polysocial.model.event.Coordinates
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The parser uses Android's `org.json`, whose plain JVM stubs return empty values
 * (`isReturnDefaultValues`), so these tests must run under Robolectric to parse anything at all.
 * The fixtures follow the shape of real Nominatim `jsonv2` answers, with public places only.
 */
@RunWith(RobolectricTestRunner::class)
class NominatimParsingTest {

  @Test
  fun search_readsTheNameAndTheAddressLineInNominatimsOrder() {
    val places = parseNominatimSearch("[$ROLEX]")

    assertEquals(
        listOf(
            Place(
                name = "Rolex Learning Center",
                address = "Place Maurice-Cosandey, 1015 Lausanne",
                coordinates = Coordinates(46.5183517, 6.5676152),
            )
        ),
        places,
    )
  }

  @Test
  fun search_putsTheHouseNumberAfterTheStreetAndThePostcodeBeforeTheTown() {
    val json =
        """[{"lat":"46.52","lon":"6.63","name":"Some Shop",
          "address":{"road":"Rue de Bourg","house_number":"12","postcode":"1003","city":"Lausanne"}}]"""

    assertEquals("Rue de Bourg 12, 1003 Lausanne", parseNominatimSearch(json).single().address)
  }

  @Test
  fun search_keepsNominatimsOrderOfPlaces() {
    val json =
        """[{"lat":"46.5","lon":"6.5","name":"First"},{"lat":"46.6","lon":"6.6","name":"Second"}]"""

    assertEquals(listOf("First", "Second"), parseNominatimSearch(json).map { it.name })
  }

  @Test
  fun search_readsTheStreetAndTownFromTheirFallbackKeys() {
    val json =
        """[
          {"lat":"46.5","lon":"6.6","name":"A","address":{"pedestrian":"Place de la Palud","town":"Pully"}},
          {"lat":"46.5","lon":"6.6","name":"B","address":{"square":"Place Centrale","village":"Bussigny"}},
          {"lat":"46.5","lon":"6.6","name":"C","address":{"footway":"Chemin X","municipality":"Ecublens"}},
          {"lat":"46.5","lon":"6.6","name":"D","address":{"path":"Sentier Y","hamlet":"Vers-chez-Z"}}
        ]"""

    assertEquals(
        listOf(
            "Place de la Palud, Pully",
            "Place Centrale, Bussigny",
            "Chemin X, Ecublens",
            "Sentier Y, Vers-chez-Z",
        ),
        parseNominatimSearch(json).map { it.address },
    )
  }

  @Test
  fun search_prefersTheFirstStreetAndTownKeysWhenSeveralArePresent() {
    val json =
        """[{"lat":"46.5","lon":"6.6","name":"A",
          "address":{"path":"Sentier","road":"Avenue","hamlet":"Hamlet","city":"Lausanne"}}]"""

    assertEquals("Avenue, Lausanne", parseNominatimSearch(json).single().address)
  }

  @Test
  fun search_namesAPlaceWithoutANameAfterItsStreetAndDoesNotRepeatIt() {
    val json =
        """[{"lat":"46.5191","lon":"6.5668","name":"",
          "address":{"road":"Route Cantonale","house_number":"1","postcode":"1015","town":"Ecublens"}}]"""

    val place = parseNominatimSearch(json).single()

    assertEquals("Route Cantonale 1", place.name)
    assertEquals("1015 Ecublens", place.address)
  }

  @Test
  fun search_namesAPlaceWithoutANameKeyAfterItsStreet() {
    val json =
        """[{"lat":"46.5","lon":"6.6","address":{"road":"Avenue de Rhodanie","city":"Lausanne"}}]"""

    val place = parseNominatimSearch(json).single()

    assertEquals("Avenue de Rhodanie", place.name)
    assertEquals("Lausanne", place.address)
  }

  @Test
  fun search_fallsBackToTheFirstDisplayNameSegment() {
    val json =
        """[{"lat":"46.5","lon":"6.6","name":null,
          "display_name":" Lac Léman , Vaud, Schweiz/Suisse/Svizzera/Svizra"}]"""

    val place = parseNominatimSearch(json).single()

    assertEquals("Lac Léman", place.name)
    assertEquals("", place.address)
  }

  @Test
  fun search_neverShowsJsonNullsAsText() {
    val json =
        """[{"lat":"46.5","lon":"6.6","name":"Esplanade",
          "address":{"road":null,"house_number":null,"postcode":null,"city":"Lausanne"}}]"""

    val place = parseNominatimSearch(json).single()

    assertEquals("Esplanade", place.name)
    assertEquals("Lausanne", place.address)
    assertTrue(!place.address.contains("null"))
  }

  @Test
  fun search_aNullNameFallsBackToTheStreetInsteadOfTheTextNull() {
    val json = """[{"lat":"46.5","lon":"6.6","name":null,"address":{"road":"Quai d'Ouchy"}}]"""

    assertEquals("Quai d'Ouchy", parseNominatimSearch(json).single().name)
  }

  @Test
  fun search_trimsTheNameAndTheAddressParts() {
    val json =
        """[{"lat":" 46.5 ","lon":" 6.6 ","name":"  Esplanade  ",
          "address":{"road":"  Quai  ","city":" Lausanne "}}]"""

    val place = parseNominatimSearch(json).single()

    assertEquals("Esplanade", place.name)
    assertEquals("Quai, Lausanne", place.address)
    assertEquals(Coordinates(46.5, 6.6), place.coordinates)
  }

  @Test
  fun search_acceptsNumericCoordinates() {
    val json = """[{"lat":46.5,"lon":-6.6,"name":"Numbers"}]"""

    assertEquals(Coordinates(46.5, -6.6), parseNominatimSearch(json).single().coordinates)
  }

  @Test
  fun search_acceptsCoordinatesOnTheEdgesOfTheirRange() {
    val json =
        """[{"lat":"90","lon":"180","name":"North"},{"lat":"-90","lon":"-180","name":"South"}]"""

    assertEquals(listOf("North", "South"), parseNominatimSearch(json).map { it.name })
  }

  @Test
  fun search_skipsEntriesWithMissingInvalidOrOutOfRangeCoordinatesOrNoName() {
    val json =
        """[
          {"lon":"6.6","name":"no lat"},
          {"lat":"46.5","name":"no lon"},
          {"lat":null,"lon":"6.6","name":"null lat"},
          {"lat":"abc","lon":"6.6","name":"bad lat"},
          {"lat":"46.5","lon":"east","name":"bad lon"},
          {"lat":"90.1","lon":"6.6","name":"lat too big"},
          {"lat":"-90.1","lon":"6.6","name":"lat too small"},
          {"lat":"46.5","lon":"180.1","name":"lon too big"},
          {"lat":"46.5","lon":"-180.1","name":"lon too small"},
          {"lat":"NaN","lon":"6.6","name":"NaN lat"},
          {"lat":"46.5","lon":"6.6"},
          {"lat":"46.5","lon":"6.6","name":"","display_name":"","address":{}},
          {"lat":"46.5","lon":"6.6","name":"  ","address":{"city":"Lausanne"}},
          "not an object",
          {"lat":"46.5","lon":"6.6","name":"Kept"}
        ]"""

    assertEquals(listOf("Kept"), parseNominatimSearch(json).map { it.name })
  }

  @Test
  fun search_anEmptyArrayGivesNoPlaces() {
    assertEquals(emptyList<Place>(), parseNominatimSearch("[]"))
  }

  @Test
  fun search_malformedJsonThrows() {
    assertThrows(JSONException::class.java) { parseNominatimSearch("[{\"lat\":") }
    assertThrows(JSONException::class.java) { parseNominatimSearch("""{"error":"x"}""") }
    assertThrows(JSONException::class.java) { parseNominatimSearch("") }
  }

  @Test
  fun reverse_readsThePlace() {
    val place = parseNominatimReverse(ROLEX)

    assertEquals(
        Place(
            "Rolex Learning Center",
            "Place Maurice-Cosandey, 1015 Lausanne",
            Coordinates(46.5183517, 6.5676152),
        ),
        place,
    )
  }

  @Test
  fun reverse_aStreetAddressIsNamedAfterItsStreet() {
    val json =
        """{"lat":"46.5191","lon":"6.5668","name":"",
          "address":{"road":"Route Cantonale","postcode":"1015","town":"Ecublens"}}"""

    assertEquals(
        Place("Route Cantonale", "1015 Ecublens", Coordinates(46.5191, 6.5668)),
        parseNominatimReverse(json),
    )
  }

  @Test
  fun reverse_anUnknownLocationGivesNull() {
    assertNull(parseNominatimReverse("""{"error":"Unable to geocode"}"""))
  }

  @Test
  fun reverse_anAnswerWithoutValidCoordinatesGivesNull() {
    assertNull(parseNominatimReverse("""{"name":"Nowhere"}"""))
  }

  @Test
  fun reverse_malformedJsonThrows() {
    assertThrows(JSONException::class.java) { parseNominatimReverse("{\"lat\":") }
    assertThrows(JSONException::class.java) { parseNominatimReverse("[]") }
  }

  private companion object {
    const val ROLEX =
        """{"place_id":1,"lat":"46.5183517","lon":"6.5676152","category":"amenity",
          "type":"university","name":"Rolex Learning Center",
          "display_name":"Rolex Learning Center, Place Maurice-Cosandey, Quartier Sorge, Lausanne, District de l'Ouest lausannois, Vaud, 1015, Schweiz/Suisse/Svizzera/Svizra",
          "address":{"building":"Rolex Learning Center","road":"Place Maurice-Cosandey",
          "city":"Lausanne","postcode":"1015","country_code":"ch"}}"""
  }
}
