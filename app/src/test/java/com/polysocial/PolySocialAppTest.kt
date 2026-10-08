// Contributors: OpenAI Codex (isolated SDK setup for session-aware activity startup).
// Contributors: Claude Opus 5.5 (wrote these tests and revised them after review).
package com.polysocial

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.utils.FirebaseStartupRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Starts the app with its real application class. Hilt makes an `@AndroidEntryPoint` activity crash
 * on start unless the application is a `@HiltAndroidApp`, so launching [MainActivity] here checks
 * that the dependency graph starts. The `@AndroidEntryPoint` annotation itself is checked by
 * `HiltViewModelInjectionTest`.
 */
@RunWith(AndroidJUnit4::class)
class PolySocialAppTest {
  @get:Rule(order = 0) val firebaseStartupRule = FirebaseStartupRule()
  @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun manifest_registersPolySocialApp() {
    val app = ApplicationProvider.getApplicationContext<Application>()

    assertEquals(PolySocialApp::class.java, app.javaClass)
  }

  @Test
  fun mainActivity_startsWithHilt() {
    assertEquals(Lifecycle.State.RESUMED, composeTestRule.activityRule.scenario.state)
  }
}
