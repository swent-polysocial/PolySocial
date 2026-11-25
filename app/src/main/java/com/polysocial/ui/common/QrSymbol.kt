// Contributors: Claude Opus 5.5 (drafted the QR drawing for My friend code, #339; shared with the
// Entrance QR, #468); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.polysocial.model.qr.QrCode
import kotlin.math.floor

/**
 * [code]'s dark modules in black on white, inside [modifier]'s square with a margin of
 * [QUIET_ZONE_MODULES] light modules on each side: the quiet zone scanners need, kept inside the
 * square since text may sit right above it. Always black on white, not theme colors, so a theme
 * change can't make the code unscannable. Shared by My friend code (#339) and the Entrance QR
 * (#468); [description] is what a screen reader says, and [tag] the test tag.
 */
@Composable
fun QrSymbol(code: QrCode, description: String, tag: String, modifier: Modifier = Modifier) {
  Canvas(
      modifier
          .background(Color.White)
          .semantics {
            contentDescription = description
            role = Role.Image
          }
          .testTag(tag)
  ) {
    // Whole pixels per module, centred: fractional sizes leave light seams between dark modules.
    val module = floor(size.minDimension / (code.size + 2 * QUIET_ZONE_MODULES))
    val start = floor((size.minDimension - module * code.size) / 2)
    for (y in 0 until code.size) for (x in 0 until code.size) {
      if (code.isDark(x, y)) {
        drawRect(Color.Black, Offset(start + x * module, start + y * module), Size(module, module))
      }
    }
  }
}

/** The light margin around a QR code that scanners need, in modules (ISO/IEC 18004). */
private const val QUIET_ZONE_MODULES = 4
