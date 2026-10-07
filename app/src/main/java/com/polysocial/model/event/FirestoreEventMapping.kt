// Contributors: Claude (drafted the Firestore mapping for events).
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

private fun Instant.toTimestamp() = Timestamp(epochSecond, nano)
