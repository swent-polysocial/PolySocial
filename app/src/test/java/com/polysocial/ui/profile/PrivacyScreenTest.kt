// Contributors: OpenAI Codex (privacy navigation, unavailable actions, font scaling and render QA,
// #103; privacy and confirmed logout integration after rebase).
// Contributors: Claude (Opus 5.5, Anthropic) replaced the unavailable deletion with its sheet,
// #207;
// Mohamed Khellaf (reviewed and revised).
package com.polysocial.ui.profile

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.R
import com.polysocial.model.privacy.PrivacyContactLauncher
import com.polysocial.resources.C
import com.polysocial.ui.navigation.AppShell
import com.polysocial.ui.navigation.Tab
import com.polysocial.ui.navigation.TabAction
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.FirebaseStartupRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class PrivacyScreenTest {
  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
  @get:Rule(order = 1) val firebaseStartupRule = FirebaseStartupRule()
  @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private lateinit var navController: NavHostController

  private fun text(id: Int) = context.getString(id)

  private fun openFromProfile() {
    rule.activity.setContent {
      PolySocialTheme {
        navController = rememberNavController()
        AppShell(
            navController = navController,
            eventsContent = { Text("Events", Modifier.testTag(Tab.EVENTS.screenTag)) },
            mapContent = { _, _ -> Text("Map", Modifier.testTag(Tab.MAP.screenTag)) },
            createEventContent = {
              Text("Create event", Modifier.testTag(C.Tag.create_event_screen))
            },
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
  fun returningFromPrivacyPreservesLogoutConfirmationAndCancel() {
    openFromProfile()
    rule.onNodeWithTag(C.Tag.privacy_back).performClick()
    rule.onNodeWithTag(ProfileTags.Logout).performClick()
    rule.onNodeWithTag(ProfileTags.Dialog).assertIsDisplayed()
    rule.onNodeWithTag(ProfileTags.Cancel).performClick()
    rule.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    rule.onNodeWithTag(C.Tag.profile_privacy_entry).performClick()
    rule.onNodeWithTag(C.Tag.privacy_heading).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.privacy_back).performClick()
    rule.onNodeWithTag(ProfileTags.Logout).assertIsDisplayed()
  }

  @Test
  fun profileWithoutAPrivacyDestinationDoesNotExposeADeadAction() {
    rule.activity.setContent { PolySocialTheme { ProfileScreen(LogoutState.Idle, {}, {}, {}) } }
    rule.onNodeWithTag(C.Tag.profile_privacy_entry).assertDoesNotExist()
    rule.onNodeWithTag(ProfileTags.Logout).assertIsDisplayed()
  }

  @Test
  fun leavingPrivacyKeepsProfileActionsAndCreateEventNavigationWorking() {
    openFromProfile()
    rule.onNodeWithTag(TabAction.EDIT_PROFILE.tag).assertDoesNotExist()
    rule.onNodeWithTag(C.Tag.privacy_back).performClick()
    rule.onNodeWithTag(TabAction.EDIT_PROFILE.tag).performClick()
    rule.onNodeWithTag(TabAction.EDIT_PROFILE.screenTag).assertIsDisplayed()
    rule.runOnIdle { navController.popBackStack() }
    rule.onNodeWithTag(C.Tag.profile_privacy_entry).assertIsDisplayed()
    rule.onNodeWithTag(Tab.EVENTS.navItemTag).performClick()
    rule.onNodeWithTag(C.Tag.create_event_button).performClick()
    rule.onNodeWithTag(C.Tag.create_event_screen).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.bottom_nav).assertDoesNotExist()
    rule.runOnIdle { navController.popBackStack() }
    rule.onNodeWithTag(TabAction.NOTIFICATIONS.tag).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.create_event_button).assertIsDisplayed()
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

  @Test
  fun draftContactExplainsTheMissingAddressWithoutLeavingTheNotice() {
    openFromProfile()
    rule.onNodeWithTag(C.Tag.privacy_full_notice).performScrollTo().performClick()
    rule.onNodeWithText(text(R.string.privacy_notice_draft_body)).assertExists()
    rule.onNodeWithTag(C.Tag.privacy_contact).performScrollTo().performClick()
    rule.onNodeWithText(text(R.string.privacy_contact_pending)).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.privacy_notice_screen).assertIsDisplayed()
  }

  @Test
  fun emailAppFailureIsExplainedInTheFullNotice() {
    rule.activity.setContent {
      PolySocialTheme {
        FullPrivacyNoticeScreen(
            {},
            PrivacyUiState(contactMessage = PrivacyContactMessage.NoEmailApp),
            {},
            {},
        )
      }
    }
    rule.onNodeWithText(text(R.string.privacy_contact_no_app)).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.privacy_contact).performScrollTo().assertIsDisplayed()
  }

  @Test
  fun unavailableFeedbackCanBeDismissedAndAnotherActionExplained() {
    openFromProfile()
    rule.onNodeWithTag(C.Tag.privacy_download).performScrollTo().performClick()
    rule.onNodeWithText(text(R.string.privacy_unavailable)).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.privacy_dismiss)).performClick()
    rule.onNodeWithText(text(R.string.privacy_unavailable)).assertDoesNotExist()
    rule.onNodeWithTag(C.Tag.privacy_matching).performScrollTo().performClick()
    rule.onNodeWithText(text(R.string.privacy_unavailable)).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.privacy_matching).assertIsOff()
  }

  @Test
  fun openingTheFullNoticeClearsFeedbackOnTheSummaryBackStack() {
    openFromProfile()
    rule.onNodeWithTag(C.Tag.privacy_download).performScrollTo().performClick()
    rule.onNodeWithTag(C.Tag.privacy_full_notice).performScrollTo().performClick()
    rule.onNodeWithTag(C.Tag.privacy_notice_heading).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.privacy_back).performClick()
    rule.onNodeWithTag(C.Tag.privacy_heading).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.privacy_unavailable)).assertDoesNotExist()
  }

  @Test
  fun configuredContactOpensADraftAndKeepsTheNoticeAvailable() {
    var draft: Pair<String, String>? = null
    val model =
        PrivacyViewModel(
            "team@example.invalid",
            PrivacyContactLauncher { to, subject ->
              draft = to to subject
              true
            },
        )
    rule.activity.setContent { PolySocialTheme { FullPrivacyNoticeRoute({}, model) } }
    rule.onNodeWithTag(C.Tag.privacy_contact).performScrollTo().performClick()
    org.junit.Assert.assertEquals(
        "team@example.invalid" to text(R.string.privacy_contact_subject),
        draft,
    )
    rule.onNodeWithTag(C.Tag.privacy_notice_screen).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.privacy_contact_pending)).assertDoesNotExist()
    rule.onNodeWithText(text(R.string.privacy_contact_no_app)).assertDoesNotExist()
  }

  @Test
  fun emailFailureCanBeDismissedAndRetriedAfterAClientIsAvailable() {
    var canOpen = false
    var attempts = 0
    val model =
        PrivacyViewModel(
            "team@example.invalid",
            PrivacyContactLauncher { _, _ ->
              attempts++
              canOpen
            },
        )
    rule.activity.setContent { PolySocialTheme { FullPrivacyNoticeRoute({}, model) } }
    rule.onNodeWithTag(C.Tag.privacy_contact).performScrollTo().performClick()
    rule.onNodeWithText(text(R.string.privacy_contact_no_app)).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.privacy_dismiss)).performClick()
    rule.onNodeWithText(text(R.string.privacy_contact_no_app)).assertDoesNotExist()
    canOpen = true
    rule.onNodeWithTag(C.Tag.privacy_contact).performClick()
    org.junit.Assert.assertEquals(2, attempts)
    rule.onNodeWithTag(C.Tag.privacy_notice_screen).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.privacy_contact_no_app)).assertDoesNotExist()
  }

  @Test
  fun backFromContactFailureClearsFeedbackWithoutSendingAnything() {
    val showNotice = mutableStateOf(true)
    var opened = 0
    val model =
        PrivacyViewModel(
            "team@example.invalid",
            PrivacyContactLauncher { _, _ ->
              opened++
              false
            },
        )
    rule.activity.setContent {
      PolySocialTheme {
        if (showNotice.value) FullPrivacyNoticeRoute({ showNotice.value = false }, model)
        else Text("Returned")
      }
    }
    rule.onNodeWithTag(C.Tag.privacy_contact).performScrollTo().performClick()
    rule.onNodeWithText(text(R.string.privacy_contact_no_app)).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.privacy_back).performScrollTo().performClick()
    rule.onNodeWithText("Returned").assertIsDisplayed()
    org.junit.Assert.assertNull(model.uiState.value.contactMessage)
    org.junit.Assert.assertEquals(1, opened)
  }

  @Test
  fun replacingTheViewModelClearsOldFeedbackAndUsesTheNewActions() {
    val first = PrivacyViewModel("", PrivacyContactLauncher { _, _ -> false })
    val second = PrivacyViewModel("", PrivacyContactLauncher { _, _ -> false })
    first.onUnavailableAction()
    second.onUnavailableAction()
    val current = mutableStateOf(first)
    rule.activity.setContent {
      PolySocialTheme { PrivacyRoute({}, {}, {}, viewModel = current.value) }
    }
    rule.onNodeWithText(text(R.string.privacy_unavailable)).assertIsDisplayed()
    rule.runOnIdle { current.value = second }
    rule.onNodeWithText(text(R.string.privacy_unavailable)).assertIsDisplayed()
    org.junit.Assert.assertFalse(first.uiState.value.showUnavailable)
    org.junit.Assert.assertTrue(second.uiState.value.showUnavailable)
    rule.onNodeWithText(text(R.string.privacy_dismiss)).performClick()
    org.junit.Assert.assertFalse(second.uiState.value.showUnavailable)
    rule.onNodeWithTag(C.Tag.privacy_download).performScrollTo().performClick()
    rule.onNodeWithText(text(R.string.privacy_unavailable)).assertIsDisplayed()
    org.junit.Assert.assertFalse(first.uiState.value.showUnavailable)
    org.junit.Assert.assertTrue(second.uiState.value.showUnavailable)
    rule.onNodeWithText(text(R.string.privacy_dismiss)).performClick()
    org.junit.Assert.assertFalse(second.uiState.value.showUnavailable)
  }

  @Test fun downloadExplainsThatExportIsUnavailable() = checkUnavailable(C.Tag.privacy_download)

  @Test
  fun deletionOpensItsConfirmationAndCancelReturnsToPrivacy() {
    openFromProfile()
    rule.onNodeWithTag(C.Tag.privacy_delete).performScrollTo().performClick()
    rule.onNodeWithTag(DeleteAccountTags.Sheet).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.delete_account_body)).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.privacy_unavailable)).assertDoesNotExist()
    rule.onNodeWithTag(DeleteAccountTags.Cancel).performClick()
    rule.onNodeWithTag(DeleteAccountTags.Sheet).assertDoesNotExist()
    rule.onNodeWithTag(C.Tag.privacy_screen).assertIsDisplayed()
  }

  @Test
  fun matchingStaysOffAndExplainsThatItIsUnavailable() = checkUnavailable(C.Tag.privacy_matching)

  @Test
  fun largeFontsKeepPrivacyActionsReachable() {
    var backCalls = 0
    var noticeCalls = 0
    var deleteCalls = 0
    rule.activity.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f, 1.8f)) {
        PolySocialTheme {
          PrivacyScreen(
              PrivacyUiState(),
              { backCalls++ },
              { noticeCalls++ },
              {},
              {},
              { deleteCalls++ },
              modifier = Modifier.requiredSize(360.dp, 800.dp),
          )
        }
      }
    }
    rule.onNodeWithTag(C.Tag.privacy_full_notice).performScrollTo().performClick()
    org.junit.Assert.assertEquals(1, noticeCalls)
    rule.onNodeWithTag(C.Tag.privacy_delete).performScrollTo().performClick()
    org.junit.Assert.assertEquals(1, deleteCalls)
    rule.onNodeWithTag(C.Tag.privacy_back).performScrollTo().performClick()
    org.junit.Assert.assertEquals(1, backCalls)
  }

  @Test
  @Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun summaryRenderContainsItsCurrentDataAndAvailableNotice() {
    rule.activity.setContent {
      PolySocialTheme {
        Box(Modifier.requiredSize(360.dp, 800.dp)) {
          PrivacyScreen(PrivacyUiState(), {}, {}, {}, {}, {})
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
