// Contributors: OpenAI Codex (isolated SDK setup for session-aware activity startup).
// Contributors: Claude Opus 5.5 (wrote these tests).
// Contributors: OpenAI Codex (mocked Firebase session before activity startup for #31).
package com.polysocial

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.utils.FirebaseStartupRule
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A dependency Hilt builds through its `@Inject` constructor. */
class ProbeGreeting @Inject constructor() {
  val text = "injected"
}

/** Test-only `@HiltViewModel`; the app has no feature ViewModel yet (#70 adds none). */
@HiltViewModel class ProbeViewModel @Inject constructor(val greeting: ProbeGreeting) : ViewModel()

/**
 * Checks #70's criterion that a `@HiltViewModel` can be obtained in Compose with `hiltViewModel()`
 * inside [MainActivity]. `hiltViewModel()` throws unless the activity is an `@AndroidEntryPoint`.
 */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class HiltViewModelInjectionTest {
  @get:Rule(order = 1) val firebaseStartupRule = FirebaseStartupRule()

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @get:Rule(order = 2) val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun hiltViewModel_providesAViewModelWithItsDependencies() {
    composeTestRule.activity.setContent {
      val viewModel: ProbeViewModel = hiltViewModel()
      Text(viewModel.greeting.text, Modifier.testTag("probe"))
    }

    composeTestRule.onNodeWithTag("probe").assertTextEquals("injected")
  }

  @Test
  fun hiltViewModel_returnsTheSameInstanceWithinTheActivity() {
    lateinit var first: ProbeViewModel
    lateinit var second: ProbeViewModel
    composeTestRule.activity.setContent {
      first = hiltViewModel()
      second = hiltViewModel()
    }
    composeTestRule.waitForIdle()

    assertSame(first, second)
  }
}
