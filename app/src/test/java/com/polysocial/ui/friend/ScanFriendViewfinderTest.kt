// Contributors: Claude Opus 5.5 (wrote these tests, #359); Konstantinos Perdikeas (directed,
// reviewed and owns them).
package com.polysocial.ui.friend

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Figma's viewfinder is drawn over the camera: white corners and the red scan line. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp")
class ScanFriendViewfinderTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val scanner =
      object : QrScanner {
        @Composable
        override fun Camera(
            scanning: Boolean,
            torchOn: Boolean,
            onCode: (String) -> Unit,
            onError: () -> Unit,
            modifier: Modifier,
        ) = Box(modifier)
      }

  @Test
  fun theCornersAreWhiteAndTheScanLineRed() {
    compose.setContent {
      PolySocialTheme {
        ScanFriendScreen(
            ScanFriendUiState.Scanning,
            CameraAccess.GRANTED,
            scanner,
            {},
            {},
            {},
            {},
            {},
            {},
        )
      }
    }
    val camera = compose.onNodeWithTag(C.Tag.scan_friend_camera).fetchSemanticsNode().boundsInRoot
    val pixels = compose.onRoot().captureToImage().toPixelMap()
    val centerX = ((camera.left + camera.right) / 2).roundToInt()
    val centerY = ((camera.top + camera.bottom) / 2).roundToInt()
    // The scan line crosses the middle; as in Figma, the corners' arms sit just outside the
    // preview's
    // top edge (the 3 dp stroke ends on it).
    assertEquals(Color(0xFFFF6B72), pixels[centerX, centerY])
    val armX = (camera.left + (camera.right - camera.left) * 0.12f).roundToInt()
    val aboveTop = camera.top.roundToInt() - 2
    assertEquals(Color.White, pixels[armX, aboveTop])
    // The middle of the top edge has no arm, as in Figma.
    assertEquals(false, pixels[centerX, aboveTop] == Color.White)
  }
}
