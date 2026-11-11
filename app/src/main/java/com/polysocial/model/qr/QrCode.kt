// Contributors: Claude Opus 5.5 (drafted the friend QR code encoding, #339); Konstantinos Perdikeas
// (directed, reviewed and owns it).
package com.polysocial.model.qr

import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR symbol as a square grid of modules, without its quiet zone: the screen adds the white margin
 * and chooses the module size, so drawing stays in the UI and this stays plain Kotlin.
 */
class QrCode internal constructor(val size: Int, private val dark: BooleanArray) {
  /** Whether the module in column [x] and row [y] (both from 0 to [size] - 1) is dark. */
  fun isDark(x: Int, y: Int): Boolean {
    if (x !in 0 until size || y !in 0 until size) {
      throw IndexOutOfBoundsException("Module ($x, $y) is outside a $size-module code")
    }
    return dark[y * size + x]
  }
}

/**
 * Encodes [text] as a QR code with ZXing (`core` only, no Android code), at error correction level
 * M (about 15% of the symbol can be damaged or covered and it still scans), in UTF-8. Returns
 * `null` for empty text or text too long for any QR version, so the screen shows its error instead
 * of a code nobody can scan. Build friend codes with [friendQrPayload], never by hand.
 */
fun encodeQrCode(text: String): QrCode? {
  if (text.isEmpty()) return null
  val matrix =
      try {
        Encoder.encode(
                text,
                ErrorCorrectionLevel.M,
                mapOf(EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name()),
            )
            .matrix
      } catch (_: WriterException) {
        return null
      }
  val size = matrix.width
  return QrCode(size, BooleanArray(size * size) { matrix.get(it % size, it / size) == ONE })
}

private const val ONE: Byte = 1
