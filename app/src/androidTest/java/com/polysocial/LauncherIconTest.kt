// Contributors: Claude Opus 5.5 (launcher icon checks, #279).
package com.polysocial

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.polysocial.ui.theme.Accent
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home-screen icon is the team's concept A from Figma (#279): the white people glyph on an
 * Accent tile, as an adaptive icon whose foreground is also the themed (monochrome) icon.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun theManifestUsesTheLauncherIcon() {
    val app = context.packageManager.getApplicationInfo(context.packageName, 0)

    assertEquals(R.mipmap.ic_launcher, app.icon)
  }

  @Test
  fun launcherAndRoundIconsAreTheGroupTile() {
    for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
      val name = context.resources.getResourceEntryName(id)
      val icon = context.getDrawable(id)
      assertTrue("$name is not an adaptive icon", icon is AdaptiveIconDrawable)
      icon as AdaptiveIconDrawable

      val tile = icon.background
      assertTrue("$name's background is not a plain colour", tile is ColorDrawable)
      assertEquals("$name's tile is not Accent", Accent.toArgb(), (tile as ColorDrawable).color)

      assertGlyph(name, icon.foreground)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        assertNotNull("$name has no themed (monochrome) icon", icon.monochrome)
        assertGlyph("$name's monochrome layer", icon.monochrome!!)
      }
    }
  }

  /**
   * Renders a 108 dp layer at 1 px per dp and checks Figma's geometry: the front person's head
   * stroke is opaque white at its top (48, 38), and the gap between the heads and the shoulders
   * (54, 56) stays transparent so the tile shows through. The whole glyph must also stay inside the
   * 66 dp safe zone. A glyph point (x, y) lands at (2x + 32, 2y + 33): the head circle's top (8.25,
   * 2.75) gives (48.5, 38.5), and (11, 11.5), between the head and the shoulders, gives (54, 56).
   */
  private fun assertGlyph(name: String, layer: Drawable) {
    val bitmap = Bitmap.createBitmap(LAYER_SIZE, LAYER_SIZE, Bitmap.Config.ARGB_8888)
    layer.setBounds(0, 0, LAYER_SIZE, LAYER_SIZE)
    layer.draw(Canvas(bitmap))

    assertEquals("$name has no white head stroke", Color.WHITE, bitmap.getPixel(48, 38))
    assertEquals("$name is not transparent between the strokes", 0, bitmap.getPixel(54, 56))
    assertInsideSafeZone(name, bitmap)
  }

  /** Launchers may mask the layer to any shape, but never cut into the central 66 dp circle. */
  private fun assertInsideSafeZone(name: String, bitmap: Bitmap) {
    val centre = LAYER_SIZE / 2f
    for (x in 0 until LAYER_SIZE) {
      for (y in 0 until LAYER_SIZE) {
        if (Color.alpha(bitmap.getPixel(x, y)) == 0) continue
        val distance = hypot(x + 0.5f - centre, y + 0.5f - centre)
        assertTrue("$name draws at ($x, $y), outside the safe zone", distance <= SAFE_ZONE_RADIUS)
      }
    }
  }

  private companion object {
    const val LAYER_SIZE = 108
    const val SAFE_ZONE_RADIUS = 33f
  }
}
