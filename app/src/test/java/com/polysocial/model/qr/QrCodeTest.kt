// Contributors: Claude Opus 5.5 (drafted the friend QR code encoding tests, #339); Konstantinos
// Perdikeas (directed, reviewed and owns them).
package com.polysocial.model.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {

  /**
   * Reads [code] back the way a phone camera would: the modules drawn [scale] pixels wide, black on
   * white, with the 4-module quiet zone the screen leaves around it, decoded by ZXing's own reader.
   */
  private fun scan(code: QrCode, scale: Int = 4, quietZone: Int = 4): String {
    val side = (code.size + 2 * quietZone) * scale
    val pixels = IntArray(side * side) { WHITE }
    for (y in 0 until code.size) for (x in 0 until code.size) {
      if (!code.isDark(x, y)) continue
      for (dy in 0 until scale) for (dx in 0 until scale) {
        pixels[((y + quietZone) * scale + dy) * side + (x + quietZone) * scale + dx] = BLACK
      }
    }
    val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
    return QRCodeReader().decode(bitmap).text
  }

  @Test
  fun aFriendCodeScansBackToTheSameStudent() {
    val uid = "fictionalStudentUid0123456789"
    val code = encodeQrCode(friendQrPayload(uid)!!)

    assertNotNull(code)
    assertEquals(QrPayload.Friend(uid), parseQrPayload(scan(code!!)))
  }

  @Test
  fun theLongestAcceptedUidStillFitsAndScans() {
    val uid = "a".repeat(MAX_QR_ID_LENGTH)
    val code = encodeQrCode(friendQrPayload(uid)!!)

    assertEquals(QrPayload.Friend(uid), parseQrPayload(scan(code!!)))
  }

  @Test
  fun theCodeIsASquareQrSymbolWithItsThreeFinderPatterns() {
    val code = encodeQrCode(friendQrPayload("fictional-uid")!!)!!

    // Versions 1 to 40 are 21 to 177 modules wide, in steps of 4.
    assertTrue(code.size in 21..177)
    assertEquals(1, code.size % 4)
    // Top-left, top-right and bottom-left 7x7 finders: a dark ring, a light ring, a dark centre.
    for ((left, top) in listOf(0 to 0, code.size - 7 to 0, 0 to code.size - 7)) {
      assertTrue(code.isDark(left, top))
      assertTrue(code.isDark(left + 6, top + 6))
      assertFalse(code.isDark(left + 1, top + 1))
      assertTrue(code.isDark(left + 3, top + 3))
    }
  }

  @Test
  fun theSameTextAlwaysGivesTheSameCode() {
    val text = friendQrPayload("fictional-uid")!!
    val first = encodeQrCode(text)!!
    val second = encodeQrCode(text)!!

    assertEquals(first.size, second.size)
    for (y in 0 until first.size) for (x in 0 until first.size) {
      assertEquals(first.isDark(x, y), second.isDark(x, y))
    }
  }

  @Test
  fun textTooLongForAnyQrCodeGivesNoCode() {
    assertNull(encodeQrCode("x".repeat(5000)))
  }

  @Test
  fun emptyTextGivesNoCode() {
    assertNull(encodeQrCode(""))
  }

  @Test
  fun aModuleOutsideTheCodeIsRefused() {
    val code = encodeQrCode(friendQrPayload("fictional-uid")!!)!!

    for ((x, y) in listOf(-1 to 0, 0 to -1, code.size to 0, 0 to code.size)) {
      assertTrue(runCatchingIndex { code.isDark(x, y) })
    }
  }

  private fun runCatchingIndex(block: () -> Unit): Boolean =
      try {
        block()
        false
      } catch (_: IndexOutOfBoundsException) {
        true
      }

  private companion object {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
  }
}
