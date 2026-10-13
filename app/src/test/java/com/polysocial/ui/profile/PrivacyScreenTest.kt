// Contributors: OpenAI Codex (privacy navigation, unavailable actions, font scaling and render QA,
// #103).
package com.polysocial.ui.profile

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.navigation.AppShell
import com.polysocial.ui.navigation.Tab
import com.polysocial.ui.theme.PolySocialTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class PrivacyScreenTest {
  @get:Rule val rule = createComposeRule()
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun text(id: Int) = context.getString(id)

  private fun openFromProfile() {
    rule.setContent {
      PolySocialTheme {
        AppShell(
            eventsContent = { Text("Events", Modifier.testTag(Tab.EVENTS.screenTag)) },
            mapContent = { Text("Map", Modifier.testTag(Tab.MAP.screenTag)) },
        )
      }
    }
    rule.onNodeWithTag(C.Tag.nav_item_profile).performClick()
    rule.onNodeWithTag(C.Tag.profile_privacy_entry).performClick()
  }

  @Test
  fun profileOpensPrivacyAndBackReturnsToProfile() {
    openFromProfile()
    rule.onNodeWithTag(C.Tag.privacy_heading).assertTextEquals(text(R.string.privacy_title))
    rule.onNodeWithTag(C.Tag.bottom_nav).assertDoesNotExist()
    rule.onNodeWithTag(C.Tag.app_bar_title).assertDoesNotExist()
    rule.onNodeWithTag(C.Tag.privacy_back).performClick()
    rule.onNodeWithTag(C.Tag.profile_privacy_entry).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.nav_item_profile).assertIsSelected()
  }

  @Test
  fun fullDatedNoticeOpensOfflineAndBackReturnsToSummary() {
    openFromProfile()
    rule.onNodeWithTag(C.Tag.privacy_full_notice).performScrollTo().performClick()
    rule
        .onNodeWithTag(C.Tag.privacy_notice_heading)
        .assertTextEquals(text(R.string.privacy_full_notice))
    rule.onNodeWithTag(C.Tag.privacy_notice_date).assertTextEquals(text(R.string.privacy_updated))
    rule.onNodeWithText(text(R.string.privacy_notice_deletion_body)).assertExists()
    rule.onNodeWithTag(C.Tag.privacy_back).performClick()
    rule.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.privacy_back).performClick()
    rule.onNodeWithTag(C.Tag.profile_privacy_entry).assertIsDisplayed()
  }

  private fun checkUnavailable(tag: String) {
    openFromProfile()
    rule.onNodeWithTag(tag).performScrollTo().performClick()
    rule.onNodeWithText(text(R.string.privacy_unavailable)).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()
    rule
        .onNodeWithTag(C.Tag.privacy_matching)
        .assertIsOff()
        .assertContentDescriptionEquals(text(R.string.privacy_matching))
  }

  @Test fun downloadExplainsThatExportIsUnavailable() = checkUnavailable(C.Tag.privacy_download)

  @Test
  fun deletionExplainsThatItIsUnavailableWithoutOpeningAnotherScreen() =
      checkUnavailable(C.Tag.privacy_delete)

  @Test
  fun matchingStaysOffAndExplainsThatItIsUnavailable() = checkUnavailable(C.Tag.privacy_matching)

  @Test
  fun largeFontsKeepPrivacyActionsReachable() {
    var backCalls = 0
    var noticeCalls = 0
    rule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f, 1.8f)) {
        PolySocialTheme {
          Box(Modifier.requiredSize(360.dp, 800.dp)) {
            PrivacyScreen(PrivacyUiState(), { backCalls++ }, { noticeCalls++ }, {}, {})
          }
        }
      }
    }
    rule.onNodeWithTag(C.Tag.privacy_full_notice).performScrollTo().performClick()
    org.junit.Assert.assertEquals(1, noticeCalls)
    rule.onNodeWithTag(C.Tag.privacy_delete).performScrollTo().assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.privacy_back).performScrollTo().performClick()
    org.junit.Assert.assertEquals(1, backCalls)
  }

  @Test
  @Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun summaryRenderContainsItsCurrentDataAndAvailableNotice() {
    rule.setContent {
      PolySocialTheme {
        Box(Modifier.requiredSize(360.dp, 800.dp)) {
          PrivacyScreen(PrivacyUiState(), {}, {}, {}, {})
        }
      }
    }
    rule.onNodeWithTag(C.Tag.privacy_heading).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.privacy_profile_title)).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.privacy_location_title)).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.privacy_reminders_title)).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.privacy_attendance_title)).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.privacy_matching).assertIsOff()
    rule.onNodeWithTag(C.Tag.privacy_full_notice).assertIsDisplayed()
    val image = rule.onNodeWithTag(C.Tag.privacy_screen).captureToImage().asAndroidBitmap()
    val output = File("build/reports/privacy/privacy-summary.png")
    output.parentFile?.mkdirs()
    output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
