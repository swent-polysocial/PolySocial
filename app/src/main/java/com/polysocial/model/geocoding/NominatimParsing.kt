// Contributors: Claude (drafted the Nominatim response parsing, #111).
package com.polysocial.model.geocoding

import com.polysocial.model.event.Coordinates
import org.json.JSONArray
import org.json.JSONObject

// Nominatim names the street and the town with whichever key fits the kind of place, so each is
// read from the first key that is present.
private val STREET_KEYS = listOf("road", "pedestrian", "square", "footway", "path")
private val TOWN_KEYS = listOf("city", "town", "village", "municipality", "hamlet")

/**
 * Reads a Nominatim `jsonv2` search response (an array of places) into [Place]s, keeping
 * Nominatim's order. Entries without valid coordinates or any name are skipped.
 *
 * This uses Android's `org.json`, so JVM tests of it must run under Robolectric: the plain JVM
 * stubs return empty values instead of parsing.
 *
 * @throws org.json.JSONException if [json] is not a JSON array.
 */
internal fun parseNominatimSearch(json: String): List<Place> {
  val array = JSONArray(json)
  return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toPlace() }
}

/**
 * Reads a Nominatim `jsonv2` reverse response into a [Place]. Returns null when Nominatim found
 * nothing there: it then answers with an object holding only `error`.
 *
 * @throws org.json.JSONException if [json] is not a JSON object.
 */
internal fun parseNominatimReverse(json: String): Place? {
  val result = JSONObject(json)
  return if (result.has("error")) null else result.toPlace()
}

/**
 * The place's own name becomes [Place.name]. A place without one, like a plain street address, is
 * named after its street. The address line holds the street (unless it is already the name) and the
 * postcode with the town, in the Swiss order shown in Figma: "Route Cantonale, 1015 Ecublens".
 */
private fun JSONObject.toPlace(): Place? {
  val latitude = text("lat").toDoubleOrNull()
  val longitude = text("lon").toDoubleOrNull()
  if (latitude == null || latitude !in -90.0..90.0) return null
  if (longitude == null || longitude !in -180.0..180.0) return null

  val address = optJSONObject("address")
  val street =
      address?.let { listOf(it.firstText(STREET_KEYS), it.text("house_number")) }?.joined(" ")
  val town = address?.let { listOf(it.text("postcode"), it.firstText(TOWN_KEYS)) }?.joined(" ")
  val name = text("name").ifEmpty { street ?: text("display_name").substringBefore(',').trim() }
  if (name.isEmpty()) return null

  val addressLine = listOfNotNull(street.takeIf { it != name }, town).joinToString(", ")
  return Place(name, addressLine, Coordinates(latitude, longitude))
}

/** The trimmed string at [key], or "" when it is missing or null (`optString` gives "null"). */
private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()

private fun JSONObject.firstText(keys: List<String>): String =
    keys.firstNotNullOfOrNull { key -> text(key).ifEmpty { null } } ?: ""

/** The non-empty parts joined by [separator], or null when every part is empty. */
private fun List<String>.joined(separator: String): String? {
  val text = filter { it.isNotEmpty() }.joinToString(separator)
  return text.ifEmpty { null }
}
