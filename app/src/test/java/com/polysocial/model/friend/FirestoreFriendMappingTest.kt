// Contributors: Claude Opus 5.5 (wrote these tests, #357); Konstantinos Perdikeas (directed,
// reviewed and owns them).
package com.polysocial.model.friend

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirestoreFriendMappingTest {
  private val created = Instant.parse("2026-11-16T10:00:00Z")
  private val accepted = Instant.parse("2026-11-16T11:00:00Z")

  private fun stored(changes: Map<String, Any?> = emptyMap()): Map<String, Any?> =
      mapOf(
          "uids" to listOf("alice", "bob"),
          "requestedBy" to "bob",
          "status" to "pending",
          "createdAt" to Timestamp(created),
      ) + changes

  private fun assertUnreadable(changes: Map<String, Any?>, id: String = "alice~bob") =
      assertNull("$changes must not be read", friendshipFromFirestore(id, stored(changes)))

  @Test
  fun newRequest_holdsTheSortedUidsTheSenderPendingAndTheServerTime() {
    val expected =
        mapOf(
            "uids" to listOf("alice", "bob"),
            "requestedBy" to "bob",
            "status" to "pending",
            "createdAt" to FieldValue.serverTimestamp(),
        )

    assertEquals(expected, newFriendRequestFirestoreMap(fromUid = "bob", toUid = "alice"))
    assertEquals(
        expected + ("requestedBy" to "alice"),
        newFriendRequestFirestoreMap(fromUid = "alice", toUid = "bob"),
    )
  }

  @Test
  fun acceptance_setsOnlyTheStatusAndTheServerTime() {
    assertEquals(
        mapOf("status" to "accepted", "acceptedAt" to FieldValue.serverTimestamp()),
        acceptanceFirestoreMap(),
    )
  }

  @Test
  fun aPendingRequestIsRead() {
    assertEquals(
        Friendship(
            id = "alice~bob",
            uids = listOf("alice", "bob"),
            requestedBy = "bob",
            status = FriendshipStatus.PENDING,
            createdAt = created,
            acceptedAt = null,
        ),
        friendshipFromFirestore("alice~bob", stored()),
    )
  }

  @Test
  fun anAcceptedFriendshipIsReadWithItsAcceptedTime() {
    val friendship =
        friendshipFromFirestore(
            "alice~bob",
            stored(mapOf("status" to "accepted", "acceptedAt" to Timestamp(accepted))),
        )

    assertEquals(FriendshipStatus.ACCEPTED, friendship?.status)
    assertEquals(accepted, friendship?.acceptedAt)
  }

  @Test
  fun theServerTimeKeepsItsNanoseconds() {
    // accept and remove compare createdAt with what the screen showed, so it must read back
    // exactly as stored.
    val precise = Instant.parse("2026-11-16T10:00:00.123456789Z")

    assertEquals(
        precise,
        friendshipFromFirestore("alice~bob", stored(mapOf("createdAt" to Timestamp(precise))))
            ?.createdAt,
    )
  }

  @Test
  fun noDataIsUnreadable() {
    assertNull(friendshipFromFirestore("alice~bob", null))
  }

  @Test
  fun wrongOrMissingUidsAreUnreadable() {
    assertUnreadable(mapOf("uids" to null))
    assertUnreadable(mapOf("uids" to "alice~bob"))
    assertUnreadable(mapOf("uids" to listOf("alice")))
    assertUnreadable(mapOf("uids" to listOf("alice", "bob", "carol")))
    assertUnreadable(mapOf("uids" to listOf("alice", 7L)))
    assertUnreadable(mapOf("uids" to listOf("bob", "alice")))
  }

  @Test
  fun anIdThatIsNotThePairsIsUnreadable() {
    assertUnreadable(emptyMap(), id = "alice~carol")
    assertUnreadable(emptyMap(), id = "bob~alice")
  }

  @Test
  fun aSenderOutsideThePairIsUnreadable() {
    assertUnreadable(mapOf("requestedBy" to "carol"))
    assertUnreadable(mapOf("requestedBy" to null))
    assertUnreadable(mapOf("requestedBy" to 1L))
  }

  @Test
  fun anUnknownOrMissingStatusIsUnreadable() {
    assertUnreadable(mapOf("status" to "blocked"))
    assertUnreadable(mapOf("status" to "PENDING"))
    assertUnreadable(mapOf("status" to null))
  }

  @Test
  fun aMissingOrWrongTypedCreatedAtIsUnreadable() {
    assertUnreadable(mapOf("createdAt" to null))
    assertUnreadable(mapOf("createdAt" to "2026-11-16"))
  }

  @Test
  fun aWrongTypedAcceptedAtReadsAsNone() {
    assertNull(
        friendshipFromFirestore("alice~bob", stored(mapOf("acceptedAt" to "soon")))?.acceptedAt
    )
  }
}
