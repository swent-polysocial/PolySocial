// Contributors: Claude (the shared chip surface and interest labels, written test-first, after
// review, #230).
package com.polysocial.ui.common

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.user.INTERESTS
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class ChipsTest {
  @get:Rule val rule = createComposeRule()

  private fun Color.isCloseTo(other: Color) =
      abs(red - other.red) < 0.04f &&
          abs(green - other.green) < 0.04f &&
          abs(blue - other.blue) < 0.04f

  @Test
  @Config(qualifiers = "xhdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun aSelectedChipIsInk_anUnselectedOneWhiteWithABorder() {
    rule.setContent {
      Row(Modifier.padding(4.dp)) {
        for (selected in listOf(true, false)) {
          Box(
              Modifier.padding(4.dp)
                  .size(80.dp, 40.dp)
                  .choiceChipSurface(selected, RoundedCornerShape(20.dp))
                  .testTag(if (selected) "on" else "off")
          )
        }
      }
    }

    for ((tag, fill) in listOf("on" to Ink, "off" to Bg)) {
      val image = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
      val middle = image.height / 2
      assertTrue(
          "$tag isn't filled with its colour",
          image[image.width / 2, middle].isCloseTo(fill),
      )
      // The left edge's middle: Ink for a selected chip, the 1 dp Border outline otherwise.
      val edge = image[1, middle]
      assertTrue("$tag's edge is wrong", edge.isCloseTo(if (tag == "on") Ink else Border))
    }
  }

  @Test
  fun everyInterestHasALabel_andAnUnknownCodeHasNone() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    assertEquals(INTERESTS, INTEREST_LABELS.keys.toList())
    assertEquals(
        INTERESTS.size,
        INTERESTS.map { context.getString(INTEREST_LABELS.getValue(it)) }.toSet().size,
    )
    assertNull(interestLabel("chess"))
    assertEquals(INTEREST_LABELS.getValue("arts"), interestLabel("arts"))
  }
}
