// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests of the Entrance QR's frames, #468);
// Konstantinos Perdikeas (directed, reviewed and owns them).
package com.polysocial.ui.event.entrance

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.qr.encodeQrCode
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Entrance QR (#468) in each of its Figma section 20 frames: 06, 16, 18, 19 and 20. */
@RunWith(AndroidJUnit4::class)
class EntranceQrScreenTest {
  @get:Rule val rule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private var backs = 0
  private var retries = 0

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  private fun show(state: EntranceQrUiState) {
    rule.setContent { PolySocialTheme { EntranceQrScreen(state, { backs++ }, { retries++ }) } }
  }

  private fun absent(tag: String) = rule.onAllNodesWithTag(tag).assertCountEquals(0)

  @Test
  fun frame06_showsTheCodeWithTheEventAndVenue_andOnlyBackToEvent() {
    show(EntranceQrUiState.Shown("Balélec warm-up", "Satellite", encodeQrCode("polysocial://x")!!))

    rule.onNodeWithTag(EntranceQrTags.HEADING).assertTextEquals(string(R.string.entrance_qr_title))
    rule.onNodeWithText("Balélec warm-up").assertIsDisplayed()
    rule.onNodeWithText(string(R.string.entrance_qr_subtitle)).assertIsDisplayed()
    rule
        .onNodeWithContentDescription(string(R.string.entrance_qr_description, "Balélec warm-up"))
        .assertIsDisplayed()
    rule
        .onNodeWithTag(EntranceQrTags.CAPTION)
        .assertTextEquals("Satellite · The code refreshes every minute")
    absent(EntranceQrTags.TRY_AGAIN)
    rule.onNodeWithTag(EntranceQrTags.BACK_TO_EVENT).performClick()
    rule.onNodeWithTag(EntranceQrTags.BACK).performClick()
    assertEquals(2, backs)
  }

  @Test
  fun frame06_withoutAVenue_keepsOnlyTheNote() {
    show(EntranceQrUiState.Shown("Balélec warm-up", "", encodeQrCode("polysocial://x")!!))

    rule
        .onNodeWithTag(EntranceQrTags.CAPTION)
        .assertTextEquals(string(R.string.entrance_qr_caption_no_venue))
  }

  @Test
  fun frame16_offline_showsNoCode_andTryAgain() {
    show(EntranceQrUiState.Offline("Balélec warm-up"))

    rule
        .onNodeWithTag(EntranceQrTags.MESSAGE)
        .assertTextEquals(string(R.string.entrance_qr_offline))
    absent(EntranceQrTags.CODE)
    rule.onNodeWithTag(EntranceQrTags.EMPTY_CARD).assertIsDisplayed()
    rule
        .onNodeWithTag(EntranceQrTags.CAPTION)
        .assertTextEquals(string(R.string.entrance_qr_waiting))
    rule.onNodeWithTag(EntranceQrTags.TRY_AGAIN).performClick()
    assertEquals(1, retries)
  }

  @Test
  fun frame20_couldntLoad_showsNoCode_andTryAgain() {
    show(EntranceQrUiState.Failed("Balélec warm-up"))

    rule.onNodeWithText("Balélec warm-up").assertIsDisplayed()
    rule.onNodeWithTag(EntranceQrTags.MESSAGE).assertTextEquals(string(R.string.entrance_qr_failed))
    absent(EntranceQrTags.CODE)
    absent(EntranceQrTags.EMPTY_CARD)
    absent(EntranceQrTags.CAPTION)
    rule.onNodeWithTag(EntranceQrTags.TRY_AGAIN).performClick()
    assertEquals(1, retries)
  }

  @Test
  fun frame20_withoutTheEvent_hasNoTitle() {
    show(EntranceQrUiState.Failed(null))

    rule.onNodeWithTag(EntranceQrTags.MESSAGE).assertIsDisplayed()
    rule.onNodeWithTag(EntranceQrTags.TRY_AGAIN).assertIsDisplayed()
  }

  @Test
  fun frame18_beforeTheWindow_saysWhenItOpens() {
    show(EntranceQrUiState.NotOpenYet("Balélec warm-up", "Satellite", "19:30"))

    rule.onNodeWithText("Check-in opens at 19:30").assertIsDisplayed()
    rule
        .onNodeWithTag(EntranceQrTags.MESSAGE)
        .assertTextEquals(string(R.string.entrance_qr_not_open_body))
    rule.onNodeWithTag(EntranceQrTags.CAPTION).assertTextEquals("Balélec warm-up · Satellite")
    absent(EntranceQrTags.CODE)
    absent(EntranceQrTags.TRY_AGAIN)
    rule.onNodeWithTag(EntranceQrTags.BACK_TO_EVENT).performClick()
    assertEquals(1, backs)
  }

  @Test
  fun frame19_afterTheWindow_saysItClosed() {
    show(EntranceQrUiState.Closed("Balélec warm-up", "", "01:00"))

    rule.onNodeWithText(string(R.string.entrance_qr_closed_title)).assertIsDisplayed()
    rule
        .onNodeWithTag(EntranceQrTags.MESSAGE)
        .assertTextEquals("Check-in closed when Balélec warm-up ended at 01:00.")
    rule.onNodeWithTag(EntranceQrTags.CAPTION).assertTextEquals("Balélec warm-up")
    absent(EntranceQrTags.CODE)
  }

  @Test
  fun loading_showsTheLoadingState() {
    show(EntranceQrUiState.Loading)

    rule.onNodeWithText(string(R.string.entrance_qr_loading)).assertIsDisplayed()
    absent(EntranceQrTags.BACK_TO_EVENT)
  }
}
