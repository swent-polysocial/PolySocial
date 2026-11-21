// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #428).
package com.polysocial.model.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirestoreOneToOneChatMappingTest {

  private fun read(data: Map<String, Any?>?, id: String = PAIR, uid: String = ALICE) =
      oneToOneChatFromFirestore(id, data, uid)

  @Test
  fun newChatFirestoreMap_writesExactlyTheUids() {
    assertEquals(mapOf("uids" to listOf(ALICE, BOB)), newChatFirestoreMap(listOf(ALICE, BOB)))
  }

  @Test
  fun aChatOfThePair_hasTheOtherStudentForEitherOfThem() {
    val stored = mapOf("uids" to listOf(ALICE, BOB))

    assertEquals(OneToOneChat(PAIR, BOB), read(stored))
    assertEquals(OneToOneChat(PAIR, ALICE), read(stored, uid = BOB))
  }

  @Test
  fun aChatWhoseOtherAccountWasDeleted_hasNoOtherStudent() {
    assertEquals(OneToOneChat(PAIR, null), read(mapOf("uids" to listOf(ALICE))))
  }

  @Test
  fun aChatThatCannotBeRead_isNull() {
    val unreadable =
        listOf(
            null,
            emptyMap(),
            mapOf("uids" to "alice~bob"),
            mapOf("uids" to null),
            mapOf("uids" to emptyList<String>()),
            mapOf("uids" to listOf(ALICE, 7)),
            mapOf("uids" to listOf(ALICE, null)),
            // Without the student, or with someone outside the pair.
            mapOf("uids" to listOf(BOB)),
            mapOf("uids" to listOf(ALICE, "carol")),
            mapOf("uids" to listOf(ALICE, BOB, "carol")),
        )
    for (data in unreadable) {
      assertNull("$data must not be read", read(data))
    }
  }

  @Test
  fun aChatUnderAnIdThatIsNotTheStudentsPairId_isNull() {
    val stored = mapOf("uids" to listOf(ALICE, BOB))

    for (id in listOf("bob~alice", "alice~bob~", "alice", "alice~carol")) {
      assertNull("\"$id\" must not be read", read(stored, id = id))
    }
    assertNull(read(stored, uid = "carol"))
  }

  private companion object {
    const val ALICE = "alice"
    const val BOB = "bob"
    const val PAIR = "alice~bob"
  }
}
