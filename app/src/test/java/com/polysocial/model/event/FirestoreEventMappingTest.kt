// Contributors: Claude (wrote these tests).
package com.polysocial.model.event

import com.google.firebase.Timestamp
import com.google.firebase.firestore.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FirestoreEventMappingTest {

  @Test
  fun toFirestoreMap_writesEveryFieldUnderItsOwnName() {
    val event =
        validEvent()
            .copy(isPrivate = true, category = EventCategory.PARTY)
            .withCreator("creator", isVerifiedAssociation = true)

    assertEquals(
        mapOf(
            "title" to "Study session",
            "description" to "Going through the exercise sheet together.",
            "category" to "PARTY",
            "location" to GeoPoint(46.5191, 6.5668),
            "startTime" to Timestamp(event.startTime.epochSecond, 0),
            "endTime" to Timestamp(event.endTime!!.epochSecond, 0),
            "capacity" to 8,
            "isPrivate" to true,
            "createdBy" to "creator",
            "organizerIds" to listOf("creator"),
            "allowedUids" to listOf("creator"),
            "isAssociationEvent" to true,
        ),
        event.toFirestoreMap(),
    )
  }

  @Test
  fun toFirestoreMap_doesNotStoreTheId() {
    val map = validEvent().copy(id = "event-1").toFirestoreMap()

    assertFalse(map.containsKey("id"))
  }

  @Test
  fun toFirestoreMap_storesMissingEndTimeAndCapacityAsNull() {
    val map = validEvent(endTime = null, capacity = null).toFirestoreMap()

    assertNull(map.getValue("endTime"))
    assertNull(map.getValue("capacity"))
  }

  @Test
  fun toFirestoreMap_keepsSubSecondPrecision() {
    val start = TEST_NOW.plusSeconds(3600).plusNanos(123_000_000)
    val map = validEvent(startTime = start, endTime = null).toFirestoreMap()

    assertEquals(Timestamp(start.epochSecond, 123_000_000), map["startTime"])
  }

  @Test
  fun toFirestoreMap_storesEveryCategoryByName() {
    for (category in EventCategory.entries) {
      val map = validEvent().copy(category = category).toFirestoreMap()

      assertEquals(category.name, map["category"])
    }
  }
}
