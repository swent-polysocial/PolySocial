// Contributors: Claude (Opus 5.5, Anthropic) tested the shared confirmation sheet, #296.
package com.polysocial.ui.common

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfirmationSheetTest {

  @get:Rule val rule = createComposeRule()

  private val title =
      ApplicationProvider.getApplicationContext<Context>().getString(R.string.cancel_event_title)

  private var dismissals = 0

  /** Whether a call runs, as the screen would say; the test changes it while the sheet shows. */
  private var locked by mutableStateOf(false)

  /** A sheet like Cancel event's, with its icon, title and secondary button. */
  private fun show(locked: Boolean) {
    this.locked = locked
    rule.setContent {
      PolySocialTheme {
        ConfirmationSheet(
            locked = this.locked,
            onDismissRequest = { dismissals++ },
            modifier = Modifier.testTag(SHEET),
        ) { dismiss ->
          SheetIcon(R.drawable.ic_calendar_cancel, tint = AccentText)
          SheetTitle(R.string.cancel_event_title)
          SheetSecondaryButton(
              R.string.cancel_event_keep,
              enabled = !this@ConfirmationSheetTest.locked,
              tag = KEEP,
              onClick = dismiss,
          )
        }
      }
    }
    rule.waitForIdle()
  }

  private fun scrim() = rule.onNode(hasClickAction() and hasContentDescription(SCRIM))

  @Test
  fun unlocked_backAndTheScrimDismissIt() {
    show(locked = false)

    Espresso.pressBackUnconditionally()
    rule.waitForIdle()
    scrim().performClick()
    rule.waitForIdle()

    assertEquals(2, dismissals)
  }

  @Test
  fun locked_backAndTheScrimDontDismissIt_andItsButtonIsDisabled() {
    show(locked = true)

    Espresso.pressBackUnconditionally()
    scrim().performClick()
    rule.waitForIdle()

    rule.onNodeWithTag(SHEET).assertIsDisplayed()
    rule.onNodeWithTag(KEEP).assertIsNotEnabled()
    assertEquals(0, dismissals)
  }

  @Test
  fun unlockedAgain_backDismissesIt() {
    show(locked = true)
    Espresso.pressBackUnconditionally()
    rule.waitForIdle()
    assertEquals(0, dismissals)

    locked = false
    rule.waitForIdle()
    Espresso.pressBackUnconditionally()
    rule.waitForIdle()

    assertEquals(1, dismissals)
  }

  @Test
  fun theSecondaryButton_slidesTheSheetAwayThenDismissesItOnce() {
    show(locked = false)

    rule.onNodeWithTag(KEEP).assertHeightIsAtLeast(48.dp).performClick()
    rule.waitForIdle()

    assertEquals(1, dismissals)
  }

  @Test
  fun theTitle_isReadAsAHeading() {
    show(locked = false)

    rule.onNode(isHeading() and hasText(title)).assertIsDisplayed()
  }

  private companion object {
    const val SHEET = "confirmation_sheet_test"
    const val KEEP = "confirmation_sheet_test_keep"

    /** Material 3's description of the bottom sheet's scrim ("Close sheet"). */
    const val SCRIM = "Close sheet"
  }
}
