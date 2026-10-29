// Contributors: Claude (wrote these tests, #251; modifier and label style tests after review).
package com.polysocial.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The shared form and banner parts (#251) on their own; each screen's tests cover its use. */
@RunWith(AndroidJUnit4::class)
class FormPartsTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private fun show(content: @Composable () -> Unit) {
    compose.setContent { PolySocialTheme { content() } }
  }

  @Test
  fun fieldLabel_showsItsText() {
    show { FieldLabel(R.string.login_email_label) }

    compose
        .onNodeWithText(compose.activity.getString(R.string.login_email_label))
        .assertIsDisplayed()
  }

  @Test
  fun offlineBanner_showsItsMessage_underItsTag() {
    show { OfflineBanner("You're offline.", tag = "banner") }

    compose.onNodeWithTag("banner").assertIsDisplayed()
    compose.onNodeWithText("You're offline.").assertIsDisplayed()
  }

  @Test
  fun errorBanner_showsItsTitleAndMessage() {
    show { ErrorBanner("Couldn't save", "Try again later.", offline = true, tag = "error") }

    compose.onNodeWithTag("error").assertIsDisplayed()
    compose.onNodeWithText("Couldn't save").assertIsDisplayed()
    compose.onNodeWithText("Try again later.").assertIsDisplayed()
  }

  @Test
  fun primaryButton_clicksOnlyWhenEnabled_andKeepsFigmasHeight() {
    var clicks = 0
    show { PrimaryButton("Save", enabled = true, tag = "button") { clicks++ } }
    compose
        .onNodeWithTag("button")
        .assertHeightIsEqualTo(52.dp)
        .assertHasClickAction()
        .assertIsEnabled()
        .performClick()
    assertEquals(1, clicks)
  }

  @Test
  fun primaryButton_disabledOrLoading_ignoresTaps() {
    var clicks = 0
    show {
      Column {
        PrimaryButton("Save", enabled = false, tag = "disabled") { clicks++ }
        PrimaryButton("Saving…", enabled = false, tag = "loading", loading = true) { clicks++ }
      }
    }

    compose.onNodeWithTag("disabled").assertIsNotEnabled().performClick()
    compose.onNodeWithTag("loading").assertIsNotEnabled().assertTextEquals("Saving…").performClick()

    assertEquals(0, clicks)
  }

  @Test
  fun errorBanner_andPrimaryButton_applyTheModifierTheyAreGiven() {
    show {
      Column {
        ErrorBanner(
            "Couldn't save",
            "Try again.",
            false,
            "error",
            Modifier.width(200.dp).testTag("errorOuter"),
        )
        PrimaryButton("Save", true, "button", modifier = Modifier.width(150.dp)) {}
      }
    }

    compose.onNodeWithTag("errorOuter").assertWidthIsEqualTo(200.dp)
    compose.onNodeWithTag("button").assertWidthIsEqualTo(150.dp).assertHeightIsEqualTo(52.dp)
  }

  @Test
  fun primaryButton_drawsItsLabelInTheStyleItIsGiven() {
    val style = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold)
    show {
      Column {
        PrimaryButton("Default", true, "default") {}
        PrimaryButton("Custom", true, "custom", textStyle = style) {}
      }
    }

    assertEquals(21.sp, labelLayout("Custom").layoutInput.style.fontSize)
    // Without one, the label keeps the theme's button style (titleSmall, 15 sp).
    assertEquals(15.sp, labelLayout("Default").layoutInput.style.fontSize)
  }

  private fun labelLayout(text: String): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    compose
        .onNodeWithText(text)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsActions.GetTextLayoutResult)
        ?.action
        ?.invoke(results)
    return results.single()
  }
}
