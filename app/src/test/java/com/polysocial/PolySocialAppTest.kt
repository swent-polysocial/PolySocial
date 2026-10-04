// Contributors: Claude Opus 5.5 (wrote these tests).
package com.polysocial

import android.app.Application
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.resources.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Starts the app with its real application class. Hilt makes an `@AndroidEntryPoint` activity crash
 * on start unless the application is a `@HiltAndroidApp`, so launching [MainActivity] here checks
 * that the dependency graph starts.
 */
@RunWith(AndroidJUnit4::class)
class PolySocialAppTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun manifest_registersPolySocialApp() {
    val app = ApplicationProvider.getApplicationContext<Application>()

    assertTrue(app is PolySocialApp)
    assertEquals(PolySocialApp::class.java.name, app.applicationInfo.className)
  }

  @Test
  fun mainActivity_startsWithHilt() {
    composeTestRule.onNodeWithTag(C.Tag.greeting).assertTextEquals("Hello Android!")
  }
}
