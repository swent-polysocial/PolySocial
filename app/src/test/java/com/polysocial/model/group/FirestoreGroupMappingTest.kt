// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #232).
package com.polysocial.model.group

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FirestoreGroupMappingTest {
  private val createdAt = Instant.parse("2030-03-02T18:00:00.250Z")

  private fun stored(vararg changes: Pair<String, Any?>): Map<String, Any?> =
      mapOf(
          "capacity" to 4L,
          "memberUids" to listOf("creator", "joiner"),
          "createdBy" to "creator",
          "createdAt" to Timestamp(createdAt.epochSecond, createdAt.nano),
      ) + changes

  private fun read(data: Map<String, Any?>?) = groupFromFirestore("group-1", "event-1", data)

  @Test
  fun newGroupFirestoreMap_writesFourPlacesTheCreatorAndTheServerTime() {
    val fields = newGroupFirestoreMap("creator")

    assertEquals(setOf("capacity", "memberUids", "createdBy", "createdAt"), fields.keys)
    assertEquals(4, fields["capacity"])
    assertEquals(listOf("creator"), fields["memberUids"])
    assertEquals("creator", fields["createdBy"])
    assertSame(FieldValue.serverTimestamp(), fields["createdAt"])
  }

  @Test
  fun groupFromFirestore_readsEveryField() {
    assertEquals(
        Group(
            id = "group-1",
            eventId = "event-1",
            capacity = 4,
            memberUids = listOf("creator", "joiner"),
            createdBy = "creator",
            createdAt = createdAt,
        ),
        read(stored()),
    )
  }

  @Test
  fun groupFromFirestore_withoutAServerTimeYet_hasNoCreationTime() {
    assertNull(read(stored("createdAt" to null))!!.createdAt)
    assertNull(read(stored() - "createdAt")!!.createdAt)
    assertNull(read(stored("createdAt" to "yesterday"))!!.createdAt)
  }

  @Test
  fun groupFromFirestore_withoutACreator_readsItAsBlank() {
    assertEquals("", read(stored() - "createdBy")!!.createdBy)
    assertEquals("", read(stored("createdBy" to 7L))!!.createdBy)
  }

  @Test
  fun groupFromFirestore_aMalformedGroup_isNullInsteadOfCrashing() {
    for ((name, data) in
        listOf(
            "no data" to null,
            "no capacity" to stored() - "capacity",
            "a text capacity" to stored("capacity" to "4"),
            "no place" to stored("capacity" to 0L),
            "no members" to stored() - "memberUids",
            "members that aren't a list" to stored("memberUids" to "creator"),
            "an empty group" to stored("memberUids" to emptyList<String>()),
            "a member that isn't a UID" to stored("memberUids" to listOf("creator", 7L)),
        )) {
      assertNull(name, read(data))
    }
  }

  @Test
  fun isFull_onceEveryPlaceIsTaken() {
    val group = read(stored())!!

    assertEquals(false, group.isFull)
    assertEquals(true, group.copy(memberUids = listOf("a", "b", "c", "d")).isFull)
  }
}
