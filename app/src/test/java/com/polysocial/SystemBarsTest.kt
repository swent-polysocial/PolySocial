// Contributors: OpenAI Codex (isolated SDK setup for session-aware activity startup).
// Contributors: Claude (regression test for the system-bar icons, review of #69).
// Contributors: OpenAI Codex (mocked Firebase session before activity startup for #31).
package com.polysocial

import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.utils.FirebaseStartupRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The app is light only, so the status- and navigation-bar icons must stay dark even when the phone
 * is in dark mode. Otherwise they are white on the white background and invisible.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "night")
class SystemBarsTest {
  @get:Rule(order = 0) val firebaseStartupRule = FirebaseStartupRule()

  private fun assertDarkSystemBarIcons(activity: Class<out ComponentActivity>) {
    ActivityScenario.launch(activity).use { scenario ->
      scenario.onActivity {
        val controller = WindowCompat.getInsetsController(it.window, it.window.decorView)
        assertTrue("status-bar icons are light", controller.isAppearanceLightStatusBars)
        assertTrue("navigation-bar icons are light", controller.isAppearanceLightNavigationBars)
      }
    }
  }

  @Test
  fun mainActivity_keepsDarkSystemBarIconsInDarkMode() =
      assertDarkSystemBarIcons(MainActivity::class.java)

  @Test
  fun secondActivity_keepsDarkSystemBarIconsInDarkMode() =
      assertDarkSystemBarIcons(SecondActivity::class.java)
}
