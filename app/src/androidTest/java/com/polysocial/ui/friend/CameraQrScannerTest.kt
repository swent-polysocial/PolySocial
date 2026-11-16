// Contributors: Claude Opus 5.5 (wrote these tests, #359); Konstantinos Perdikeas (directed,
// reviewed and owns them).
package com.polysocial.ui.friend

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real camera scanner on a device. CI's emulators have no camera, and the manifest lets a phone
 * without one install the app, so the scanner must show, switch the torch and be released there
 * without crashing; reading a real code needs a phone (see the PR).
 */
@RunWith(AndroidJUnit4::class)
class CameraQrScannerTest {
  @get:Rule(order = 0)
  val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun showsSwitchesTheTorchAndIsReleasedWithoutACrash() {
    var shown by mutableStateOf(true)
    var torchOn by mutableStateOf(false)
    var scanning by mutableStateOf(true)
    compose.setContent {
      if (shown) {
        CameraQrScanner.Camera(
            scanning = scanning,
            torchOn = torchOn,
            onCode = {},
            modifier = Modifier.size(240.dp).testTag(TAG),
        )
      }
    }
    compose.onNodeWithTag(TAG).assertIsDisplayed()

    torchOn = true
    scanning = false
    compose.waitForIdle()
    compose.onNodeWithTag(TAG).assertIsDisplayed()

    shown = false
    compose.waitForIdle()
    compose.onNodeWithTag(TAG).assertDoesNotExist()
  }

  private companion object {
    const val TAG = "camera_qr_scanner"
  }
}
