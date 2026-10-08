// Contributors: Claude (wrote these tests, including reading events back, #49).
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

  private val stored = validEvent().withCreator("creator", isVerifiedAssociation = false)

  @Test
  fun eventFromFirestore_readsBackWhatWasWritten() {
    val event =
        stored.copy(
            startTime = TEST_NOW.plusSeconds(3600).plusNanos(123_000_000),
            endTime = null,
            capacity = null,
            isAssociationEvent = true,
        )

    assertEquals(event.copy(id = "event-1"), eventFromFirestore("event-1", event.toFirestoreMap()))
  }

  @Test
  fun eventFromFirestore_readsAnUnknownCategoryAsOther() {
    val data = stored.toFirestoreMap() + ("category" to "KARAOKE")

    assertEquals(EventCategory.OTHER, eventFromFirestore("event-1", data)?.category)
  }

  @Test
  fun eventFromFirestore_readsTheCapacityFirestoreReturnsAsALong() {
    val data = stored.toFirestoreMap() + ("capacity" to 8L)

    assertEquals(8, eventFromFirestore("event-1", data)?.capacity)
  }

  @Test
  fun eventFromFirestore_givesMissingOptionalFieldsTheirDefaults() {
    val required =
        stored.toFirestoreMap().filterKeys {
          it in setOf("title", "description", "category", "location", "startTime", "isPrivate")
        }

    val event = eventFromFirestore("event-1", required)

    assertEquals(
        stored.copy(
            id = "event-1",
            endTime = null,
            capacity = null,
            createdBy = "",
            organizerIds = emptyList(),
            allowedUids = emptyList(),
        ),
        event,
    )
  }

  @Test
  fun eventFromFirestore_rejectsADocumentMissingARequiredField() {
    for (field in
        listOf("title", "description", "category", "location", "startTime", "isPrivate")) {
      assertNull(field, eventFromFirestore("event-1", stored.toFirestoreMap() - field))
    }
  }

  @Test
  fun eventFromFirestore_rejectsARequiredFieldWithTheWrongType() {
    assertNull(eventFromFirestore("event-1", stored.toFirestoreMap() + ("isPrivate" to "false")))
    assertNull(eventFromFirestore("event-1", stored.toFirestoreMap() + ("startTime" to "tomorrow")))
  }
}
