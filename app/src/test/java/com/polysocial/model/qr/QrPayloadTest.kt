// Contributors: Claude Opus 5.5 (wrote these tests, #337); Konstantinos Perdikeas (directed,
// reviewed and owns them).
package com.polysocial.model.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {

  private fun assertInvalid(raw: String) =
      assertEquals("\"$raw\" must be invalid", QrPayload.Invalid, parseQrPayload(raw))

  @Test
  fun friendCodeGivesTheUid() {
    assertEquals(
        QrPayload.Friend("aB3dE5fG7hJ9kL1mN3pQ5rS7tU9v"),
        parseQrPayload("polysocial://friend/aB3dE5fG7hJ9kL1mN3pQ5rS7tU9v"),
    )
  }

  @Test
  fun checkInCodeGivesTheEventAndToken() {
    assertEquals(
        QrPayload.CheckIn(eventId = "event42", token = "t0k3n"),
        parseQrPayload("polysocial://checkin/event42/t0k3n"),
    )
  }

  @Test
  fun idsMayHoldHyphensAndUnderscores() {
    // Demo and test IDs (`demo-…`) and some generated tokens use them.
    assertEquals(
        QrPayload.Friend("demo-student_1"),
        parseQrPayload("polysocial://friend/demo-student_1"),
    )
    assertEquals(
        QrPayload.CheckIn("demo-event-1", "a_b-c"),
        parseQrPayload("polysocial://checkin/demo-event-1/a_b-c"),
    )
  }

  @Test
  fun surroundingWhitespaceIsIgnored() {
    // Scanners can add a trailing newline.
    assertEquals(QrPayload.Friend("abc"), parseQrPayload("  polysocial://friend/abc\n"))
  }

  @Test
  fun anIdIsLimitedTo128Characters() {
    // A literal, not the constant: changing the limit changes which printed codes still scan.
    assertEquals(128, MAX_QR_ID_LENGTH)
    val longest = "a".repeat(128)
    assertEquals(QrPayload.Friend(longest), parseQrPayload("polysocial://friend/$longest"))
    assertInvalid("polysocial://friend/${longest}a")
    assertInvalid("polysocial://checkin/${longest}a/token")
    assertInvalid("polysocial://checkin/event/${longest}a")
  }

  @Test
  fun emptyTextIsInvalid() {
    assertInvalid("")
    assertInvalid("   ")
  }

  @Test
  fun anotherSchemeOrAWebLinkIsInvalid() {
    assertInvalid("https://polysocial.app/friend/abc")
    assertInvalid("polysocialx://friend/abc")
    assertInvalid("xpolysocial://friend/abc")
    assertInvalid("polysocial:friend/abc")
    assertInvalid("polysocial:/friend/abc")
    assertInvalid("friend/abc")
    assertInvalid("abc")
  }

  @Test
  fun theSchemeAndHostAreCaseSensitive() {
    assertInvalid("POLYSOCIAL://friend/abc")
    assertInvalid("Polysocial://friend/abc")
    assertInvalid("polysocial://Friend/abc")
    assertInvalid("polysocial://CHECKIN/event/token")
  }

  @Test
  fun anUnknownHostIsInvalid() {
    assertInvalid("polysocial://group/abc")
    assertInvalid("polysocial:///abc")
    assertInvalid("polysocial://")
  }

  @Test
  fun aMissingOrEmptyIdIsInvalid() {
    assertInvalid("polysocial://friend")
    assertInvalid("polysocial://friend/")
    assertInvalid("polysocial://checkin")
    assertInvalid("polysocial://checkin/event")
    assertInvalid("polysocial://checkin/event/")
    assertInvalid("polysocial://checkin//token")
  }

  @Test
  fun extraSegmentsAreInvalid() {
    assertInvalid("polysocial://friend/abc/def")
    assertInvalid("polysocial://friend/abc/")
    assertInvalid("polysocial://checkin/event/token/extra")
    assertInvalid("polysocial://checkin/event/token/")
    assertInvalid("polysocial://friend//abc")
  }

  @Test
  fun aQueryOrFragmentIsInvalid() {
    assertInvalid("polysocial://friend/abc?ref=1")
    assertInvalid("polysocial://friend/abc#top")
    assertInvalid("polysocial://checkin/event/token?x")
  }

  @Test
  fun idsWithOtherCharactersAreInvalid() {
    assertInvalid("polysocial://friend/a%20b")
    assertInvalid("polysocial://friend/a b")
    assertInvalid("polysocial://friend/ abc")
    assertInvalid("polysocial://friend/..")
    assertInvalid("polysocial://friend/.")
    assertInvalid("polysocial://friend/a.b")
    assertInvalid("polysocial://friend/élise")
    assertInvalid("polysocial://checkin/event/to ken")
    assertInvalid("polysocial://checkin/ev+ent/token")
  }

  @Test
  fun friendPayloadIsTheDecidedFormat() {
    assertEquals("polysocial://friend/abc", friendQrPayload("abc"))
  }

  @Test
  fun aBuiltFriendPayloadParsesBackToTheSameUid() {
    val uid = "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9v"
    assertEquals(QrPayload.Friend(uid), parseQrPayload(friendQrPayload(uid)!!))
  }

  @Test
  fun noPayloadIsBuiltForAUidTheParserWouldRefuse() {
    assertNull(friendQrPayload(""))
    assertNull(friendQrPayload("a/b"))
    assertNull(friendQrPayload("a b"))
    assertNull(friendQrPayload("a".repeat(MAX_QR_ID_LENGTH + 1)))
  }
}
