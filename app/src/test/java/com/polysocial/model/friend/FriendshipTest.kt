// Contributors: Claude Opus 5.5 (wrote these tests, #357; isFriendshipIdOf, second PR);
// Konstantinos Perdikeas (directed, reviewed and owns them).
// Contributors: Claude (Opus 5.5, Anthropic) (tests of uidsOfFriendshipId, #428).
package com.polysocial.model.friend

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

  @Test
  fun isFriendshipIdOf_isTrueForEitherStudentOfTheExactId() {
    assertTrue(isFriendshipIdOf("alice~bob", "alice"))
    assertTrue(isFriendshipIdOf("alice~bob", "bob"))
  }

  @Test
  fun isFriendshipIdOf_isFalseForAnIdFriendshipIdWouldNotBuild() {
    for (id in listOf("bob~alice", "alice~bob~", "alice~bob~carol", "~alice~bob", "alice~~bob")) {
      assertFalse("\"$id\" must not be alice's", isFriendshipIdOf(id, "alice"))
    }
    assertFalse(isFriendshipIdOf("alice", "alice"))
    assertFalse(isFriendshipIdOf("alice~", "alice"))
    assertFalse(isFriendshipIdOf("alice~alice", "alice"))
    assertFalse(isFriendshipIdOf("alice~b/ob", "alice"))
  }

  @Test
  fun isFriendshipIdOf_isFalseForSomeoneOutsideThePair() {
    assertFalse(isFriendshipIdOf("alice~bob", "carol"))
    assertFalse(isFriendshipIdOf("alice~bob", "alice~bob"))
  }

  @Test
  fun uidsOfFriendshipId_areTheTwoHalvesInTheIdsOrder() {
    assertEquals(listOf("alice", "bob"), uidsOfFriendshipId("alice~bob"))
    // By character code, as friendshipId sorts them: not alphabetical.
    assertEquals(listOf("Zed", "alice"), uidsOfFriendshipId("Zed~alice"))
  }

  @Test
  fun uidsOfFriendshipId_isNullForAnIdFriendshipIdWouldNotBuild() {
    val notPairIds =
        listOf(
            "bob~alice",
            "alice~bob~",
            "alice~bob~carol",
            "~alice~bob",
            "alice~~bob",
            "alice",
            "alice~",
            "~bob",
            "alice~alice",
            "alice~b/ob",
            "",
        )
    for (id in notPairIds) {
      assertNull("\"$id\" must not be a pair ID", uidsOfFriendshipId(id))
    }
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
