// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #237).
// Contributors: OpenAI Codex (deleted-author message mapping regression, #241).
package com.polysocial.model.chat

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FirestoreMessageMappingTest {
  private val createdAt = Instant.parse("2030-03-02T18:00:00.250Z")

  private fun stored(vararg changes: Pair<String, Any?>): Map<String, Any?> =
      mapOf(
          "senderId" to "sender",
          "text" to "See you at 6?",
          "createdAt" to Timestamp(createdAt.epochSecond, createdAt.nano),
      ) + changes

  private fun read(data: Map<String, Any?>?) = messageFromFirestore("message-1", data)

  @Test
  fun deletedAuthorsRemainReadableWithTheirTextAndTime() {
    assertEquals(
        Message("message-1", DELETED_MESSAGE_SENDER, "See you at 6?", createdAt),
        read(stored("senderId" to DELETED_MESSAGE_SENDER)),
    )
  }

  @Test
  fun newMessageFirestoreMap_writesTheSenderTheTextAndTheServerTime() {
    val fields = newMessageFirestoreMap("sender", "Hi")

    assertEquals(setOf("senderId", "text", "createdAt"), fields.keys)
    assertEquals("sender", fields["senderId"])
    assertEquals("Hi", fields["text"])
    assertSame(FieldValue.serverTimestamp(), fields["createdAt"])
  }

  @Test
  fun messageFromFirestore_readsEveryField() {
    assertEquals(Message("message-1", "sender", "See you at 6?", createdAt), read(stored()))
  }

  @Test
  fun messageFromFirestore_aMalformedMessage_isNullInsteadOfCrashing() {
    for ((name, data) in
        listOf(
            "no data" to null,
            "no sender" to stored() - "senderId",
            "a sender that isn't a UID" to stored("senderId" to 7L),
            "no text" to stored() - "text",
            "a text that isn't a string" to stored("text" to 42L),
            "no time" to stored() - "createdAt",
            "a time not set yet" to stored("createdAt" to null),
            "a time that isn't a timestamp" to stored("createdAt" to "yesterday"),
        )) {
      assertNull(name, read(data))
    }
  }

  @Test
  fun validateMessageText_acceptsOneToAThousandCharactersOnceTrimmed() {
    assertNull(validateMessageText("a"))
    assertNull(validateMessageText("a".repeat(MAX_MESSAGE_LENGTH)))
    assertNull(validateMessageText("  ${"a".repeat(MAX_MESSAGE_LENGTH)}\n"))
    assertNull(validateMessageText("Line one\n\nLine two"))
  }

  @Test
  fun validateMessageText_nothingButWhitespace_isBlank() {
    for (text in listOf("", " ", "\n\n", "\t \n ")) {
      assertEquals("\"$text\"", MessageTextError.BLANK, validateMessageText(text))
    }
  }

  @Test
  fun validateMessageText_overTheLimit_isTooLong() {
    assertEquals(
        MessageTextError.TOO_LONG,
        validateMessageText("a".repeat(MAX_MESSAGE_LENGTH + 1)),
    )
  }

  @Test
  fun validateMessageText_countsUtf16UnitsLikeTheRules() {
    // An emoji is one character but two UTF-16 units, as the rules' size() counts it.
    val emoji = "😀"

    assertNull(validateMessageText(emoji.repeat(MAX_MESSAGE_LENGTH / 2)))
    assertEquals(
        MessageTextError.TOO_LONG,
        validateMessageText(emoji.repeat(MAX_MESSAGE_LENGTH / 2 + 1)),
    )
  }
}
