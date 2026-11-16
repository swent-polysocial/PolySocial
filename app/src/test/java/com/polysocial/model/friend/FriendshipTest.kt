// Contributors: Claude Opus 5.5 (wrote these tests, #357); Konstantinos Perdikeas (directed,
// reviewed and owns them).
package com.polysocial.model.friend

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FriendshipTest {

  private fun assertNoId(uidA: String, uidB: String) =
      assertNull("\"$uidA\" and \"$uidB\" must not make a pair", friendshipId(uidA, uidB))

  @Test
  fun idIsTheTwoUidsSortedAndJoinedByATilde() {
    assertEquals("alice~bob", friendshipId("alice", "bob"))
  }

  @Test
  fun idIsTheSameWhoeverSends() {
    assertEquals("alice~bob", friendshipId("bob", "alice"))
  }

  @Test
  fun idSortsByCharacterCodeLikeTheSecurityRules() {
    // Uppercase letters and digits sort before lowercase, '-' before digits, '_' between.
    assertEquals("Zed~alice", friendshipId("alice", "Zed"))
    assertEquals("9lives~Zed", friendshipId("Zed", "9lives"))
    assertEquals("-dash~0zero", friendshipId("0zero", "-dash"))
    assertEquals("Zed~_under", friendshipId("_under", "Zed"))
    assertEquals("_under~alice", friendshipId("alice", "_under"))
  }

  @Test
  fun idComparesWholeUidsNotOnlyTheirFirstCharacter() {
    assertEquals("ab~abc", friendshipId("abc", "ab"))
    assertEquals("abc~abd", friendshipId("abd", "abc"))
  }

  @Test
  fun aRealFirebaseUidMakesAPair() {
    val uid = "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9v"

    assertEquals("$uid~b", friendshipId("b", uid))
  }

  @Test
  fun aStudentCannotBeTheirOwnFriend() {
    assertNoId("alice", "alice")
  }

  @Test
  fun anEmptyUidMakesNoPair() {
    assertNoId("", "bob")
    assertNoId("alice", "")
  }

  @Test
  fun aUidWithATildeMakesNoPair() {
    // A '~' inside a UID would make the ID ambiguous: "a~b~c" could be a~(b~c) or (a~b)~c.
    assertNoId("a~b", "c")
    assertNoId("a", "b~c")
  }

  @Test
  fun aUidThatIsNotADocumentIdSegmentMakesNoPair() {
    assertNoId("a/b", "c")
    assertNoId("alice", "b ob")
    assertNoId("__reserved__", "bob")
    assertNoId("alice", ".")
  }

  @Test
  fun theLongestUidMakesAPairAndALongerOneDoesNot() {
    val longest = "a".repeat(128)

    assertEquals("$longest~b", friendshipId(longest, "b"))
    assertNoId("a".repeat(129), "b")
  }

  private val friendship =
      Friendship(
          id = "alice~bob",
          uids = listOf("alice", "bob"),
          requestedBy = "alice",
          status = FriendshipStatus.PENDING,
          createdAt = Instant.parse("2026-11-16T10:00:00Z"),
          acceptedAt = null,
      )

  @Test
  fun otherUidIsTheOtherStudentForEitherOfThePair() {
    assertEquals("bob", friendship.otherUid("alice"))
    assertEquals("alice", friendship.otherUid("bob"))
  }

  @Test
  fun otherUidIsNullForSomeoneOutsideThePair() {
    assertNull(friendship.otherUid("carol"))
  }
}
