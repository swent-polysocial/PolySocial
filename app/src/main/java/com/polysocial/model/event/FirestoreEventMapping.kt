// Contributors: Claude (drafted the Firestore mapping for events, and reading it back, #49).
package com.polysocial.model.event

import com.google.firebase.Timestamp
import com.google.firebase.firestore.GeoPoint
import java.time.Instant

/**
 * The fields of `events/{id}` as Firestore stores them, written by hand instead of with Firestore's
 * automatic mapping. That mapping would store the Kotlin property `isPrivate` as `private` and
 * `isAssociationEvent` as `associationEvent`, so the queries and Security Rules on these fields
 * would never match. [Event.id] isn't stored: it is the document ID.
 *
 * The category is stored by its enum name, the location as a [GeoPoint] and the times as
 * [Timestamp]s. A missing end time or capacity is stored as null.
 */
fun Event.toFirestoreMap(): Map<String, Any?> =
    mapOf(
        "title" to title,
        "description" to description,
        "category" to category.name,
        "location" to GeoPoint(location.latitude, location.longitude),
        "startTime" to startTime.toTimestamp(),
        "endTime" to endTime?.toTimestamp(),
        "capacity" to capacity,
        "isPrivate" to isPrivate,
        "createdBy" to createdBy,
        "organizerIds" to organizerIds,
        "allowedUids" to allowedUids,
        "isAssociationEvent" to isAssociationEvent,
    )

/**
 * Reads the fields of `events/[id]` back into an [Event], the reverse of [toFirestoreMap].
 *
 * A stored category this app version doesn't know is read as [EventCategory.OTHER], so adding a
 * category later doesn't break older versions. Returns null if a required field (title,
 * description, category, location, start time or visibility) is missing or has the wrong type, so
 * one malformed document can't crash the map. Missing optional fields get their defaults.
 */
fun eventFromFirestore(id: String, data: Map<String, Any?>): Event? {
  val title = data["title"] as? String ?: return null
  val description = data["description"] as? String ?: return null
  val category = data["category"] as? String ?: return null
  val location = data["location"] as? GeoPoint ?: return null
  val startTime = data["startTime"] as? Timestamp ?: return null
  val isPrivate = data["isPrivate"] as? Boolean ?: return null
  return Event(
      id = id,
      title = title,
      description = description,
      category = EventCategory.entries.firstOrNull { it.name == category } ?: EventCategory.OTHER,
      location = Coordinates(location.latitude, location.longitude),
      startTime = startTime.toInstant(),
      endTime = (data["endTime"] as? Timestamp)?.toInstant(),
      // Firestore returns every whole number as a Long.
      capacity = (data["capacity"] as? Number)?.toInt(),
      isPrivate = isPrivate,
      createdBy = data["createdBy"] as? String ?: "",
      organizerIds = data["organizerIds"].stringList(),
      allowedUids = data["allowedUids"].stringList(),
      isAssociationEvent = data["isAssociationEvent"] as? Boolean ?: false,
  )
}

/** This instant as a Firestore [Timestamp], keeping sub-second precision. */
internal fun Instant.toTimestamp() = Timestamp(epochSecond, nano)

private fun Any?.stringList(): List<String> =
    (this as? List<*>)?.filterIsInstance<String>().orEmpty()
