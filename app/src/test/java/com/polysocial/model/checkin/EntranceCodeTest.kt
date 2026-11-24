// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, test-first, #468); Konstantinos
// Perdikeas (directed, reviewed and owns them).
package com.polysocial.model.checkin

import com.polysocial.model.qr.QrPayload
import com.polysocial.model.qr.checkInQrPayload
import com.polysocial.model.qr.parseQrPayload
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The entrance code's token and timing (#374, Architecture Diagram 3.10). */
class EntranceCodeTest {
  private val rotatedAt = Instant.parse("2026-11-27T19:00:00.123456Z")
  private val code = EntranceCode("a".repeat(22), previousToken = null, rotatedAt = rotatedAt)

  @Test
  fun aToken_is22UrlSafeCharacters_thatAQrCodeCanCarry() {
    repeat(200) {
      val token = newEntranceToken()
      assertTrue(token, Regex("[A-Za-z0-9_-]{22}").matches(token))
      assertEquals(QrPayload.CheckIn("e1", token), parseQrPayload(checkInQrPayload("e1", token)!!))
    }
  }

  @Test
  fun aToken_carries128RandomBits() {
    val allOnes =
        object : SecureRandom() {
          override fun nextBytes(bytes: ByteArray) = bytes.fill(-1)
        }

    // 16 bytes of 1s: 21 full base64url characters of "_" and the last 2 bits as "w".
    assertEquals("_".repeat(21) + "w", newEntranceToken(allOnes))
    assertEquals(200, List(200) { newEntranceToken() }.toSet().size)
  }

  @Test
  fun aCode_isDueForRotation_60SecondsAfterItWasRotated() {
    assertFalse(code.isDue(rotatedAt + Duration.ofSeconds(60) - Duration.ofNanos(1)))
    assertTrue(code.isDue(rotatedAt + Duration.ofSeconds(60)))
  }

  @Test
  fun aCode_canBeScanned_untilJustBeforeItIs2MinutesOld() {
    assertTrue(code.isScannable(rotatedAt))
    assertTrue(code.isScannable(rotatedAt + Duration.ofSeconds(110) - Duration.ofNanos(1)))
    assertFalse(code.isScannable(rotatedAt + Duration.ofSeconds(110)))
  }
}
