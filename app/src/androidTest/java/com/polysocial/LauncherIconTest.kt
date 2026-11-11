// Contributors: Claude Opus 5.5 (launcher icon checks and glyph contrast check, #279).
// Contributors: OpenAI Codex (coral monogram geometry, contrast and safe-zone checks, #335).
package com.polysocial

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home-screen icon is Mehdi's selected coral monogram (#335), without the wordmark, on an
 * opaque cream tile. Its foreground is also Android's themed (monochrome) silhouette.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  /**
   * On a device whose framework sets `config_useRoundIcon` (CI's API 34 `google_apis` image does),
   * the package parser stores `android:roundIcon` in [android.content.pm.ApplicationInfo.icon], so
   * either of the two icons is the manifest's; [launcherAndRoundIconsUseTheMonogram] checks both.
   */
  @Test
  fun theManifestUsesTheLauncherIcon() {
    val app = context.packageManager.getApplicationInfo(context.packageName, 0)

    val launcherIcons = listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)
    assertTrue(
        "the app's icon ${app.icon} is not ic_launcher or ic_launcher_round",
        app.icon in launcherIcons,
    )
  }

  @Test
  fun launcherAndRoundIconsUseTheMonogram() {
    for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
      val name = context.resources.getResourceEntryName(id)
      val icon = context.getDrawable(id)
      assertTrue("$name is not an adaptive icon", icon is AdaptiveIconDrawable)
      icon as AdaptiveIconDrawable

      val tile = icon.background
      assertTrue("$name's background is not a plain colour", tile is ColorDrawable)
      assertEquals(
          "$name's tile is not cream",
          Color.rgb(255, 249, 241),
          (tile as ColorDrawable).color,
      )
      // WCAG 1.4.11 asks 3:1 for graphics; an opaque tile keeps it on any wallpaper.
      val contrast = ColorUtils.calculateContrast(CORAL, tile.color)
      assertTrue("$name's glyph contrast $contrast is below 3:1", contrast >= MIN_CONTRAST)

      assertGlyph(name, icon.foreground)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        assertNotNull("$name has no themed (monochrome) icon", icon.monochrome)
        assertGlyph("$name's monochrome layer", icon.monochrome!!)
      }
    }
  }

  /**
   * Renders at 1 px per dp and checks the distinctive P, speech-bubble opening and two heads of the
   * supplied monogram. The former people glyph fails these samples. Every painted pixel must also
   * remain inside the 66 dp safe circle, leaving no wordmark below the symbol.
   */
  private fun assertGlyph(name: String, layer: Drawable) {
    val bitmap = Bitmap.createBitmap(LAYER_SIZE, LAYER_SIZE, Bitmap.Config.ARGB_8888)
    layer.setBounds(0, 0, LAYER_SIZE, LAYER_SIZE)
    layer.draw(Canvas(bitmap))

    assertEquals("$name has no coral P rim", CORAL, bitmap.getPixel(54, 30))
    assertEquals("$name has no P stem", CORAL, bitmap.getPixel(38, 72))
    assertEquals("$name has no first head", CORAL, bitmap.getPixel(52, 54))
    assertEquals("$name has no second head", CORAL, bitmap.getPixel(60, 52))
    assertEquals("$name fills the speech-bubble opening", 0, bitmap.getPixel(54, 45))
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
    val CORAL = Color.rgb(255, 77, 77)
    const val LAYER_SIZE = 108
    const val SAFE_ZONE_RADIUS = 33f
    const val MIN_CONTRAST = 3.0
  }
}
