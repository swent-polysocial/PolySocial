// Contributors: Claude (wrote these tests; the location picker stays open, #111).
package com.polysocial.ui.event.create

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.resources.C
import java.time.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Checks what the Create Event screen keeps when the activity is recreated, as on a rotation. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CreateEventStateRestorationTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun anOpenLocationPicker_staysOpenAfterRecreation() {
    val viewModel =
        CreateEventViewModel(FakeEventRepository(now = FORM_NOW), Clock.fixed(FORM_NOW, ZURICH))
    val restorationTester = StateRestorationTester(composeTestRule)
    restorationTester.setContent {
      CreateEventScreen(
          viewModel = viewModel,
          onClose = {},
          onViewEvent = {},
          onBackToMap = {},
          locationPicker = { _, _, _, _ -> Box(Modifier.testTag(FAKE_PICKER)) },
      )
    }
    composeTestRule.onNodeWithTag(C.Tag.create_event_location).performScrollTo().performClick()

    restorationTester.emulateSavedInstanceStateRestore()

    composeTestRule.onNodeWithTag(FAKE_PICKER).assertExists()
    composeTestRule.onNodeWithTag(C.Tag.create_event_location).assertDoesNotExist()
  }

  @Test
  fun anOpenPicker_staysOpenAfterRecreation() {
    val viewModel =
        CreateEventViewModel(FakeEventRepository(now = FORM_NOW), Clock.fixed(FORM_NOW, ZURICH))
    val restorationTester = StateRestorationTester(composeTestRule)
    restorationTester.setContent {
      CreateEventScreen(
          viewModel = viewModel,
          onClose = {},
          onViewEvent = {},
          onBackToMap = {},
      )
    }
    composeTestRule.onNodeWithTag(C.Tag.create_event_start).performScrollTo().performClick()

    restorationTester.emulateSavedInstanceStateRestore()

    composeTestRule.onNodeWithTag(C.Tag.create_event_dialog_confirm).assertExists()
  }
}

private const val FAKE_PICKER = "fake_location_picker"
