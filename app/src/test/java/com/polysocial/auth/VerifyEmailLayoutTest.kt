// Contributors: OpenAI Codex (adaptive verification layout and tagged UI-state tests for #31;
// startup timing-loading feedback, disabled-action assertions and tall-tablet layout).
// Contributors: Claude (account-neutral texts and only the confirmation after a resend, written
// test-first, #277).
package com.polysocial.auth

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.ui.auth.VerificationMessage
import com.polysocial.ui.auth.VerifyEmailScreen
import com.polysocial.ui.auth.VerifyEmailTags
import com.polysocial.ui.auth.VerifyEmailUiState
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class VerifyEmailLayoutTest {
  @get:Rule val compose = createComposeRule()
  private val state =
      mutableStateOf(
          VerifyEmailUiState(
              email = "student.test@epfl.ch",
              hasSentEmail = true,
              remainingSeconds = 45,
          )
      )
  private var checks = 0
  private var resends = 0
  private var backs = 0
  private var changes = 0

  private fun launch(fontScale: Float = 1f) = compose.setContent {
    CompositionLocalProvider(
        LocalDensity provides Density(LocalDensity.current.density, fontScale)
    ) {
      PolySocialTheme {
        VerifyEmailScreen(state.value, { backs++ }, { changes++ }, { checks++ }, { resends++ }, {})
      }
    }
  }

  @Test
  @Config(qualifiers = "w840dp-h360dp")
  fun wideShortWindowUsesTwoColumnsWithVisibleActions() {
    launch()
    compose.onNodeWithTag(VerifyEmailTags.TwoColumns).assertIsDisplayed()
    val hero = compose.onNodeWithTag(VerifyEmailTags.Hero).fetchSemanticsNode().boundsInRoot
    val actions = compose.onNodeWithTag(VerifyEmailTags.Actions).fetchSemanticsNode().boundsInRoot
    assertTrue("actions are beside the hero", hero.right < actions.left)
    compose.onNodeWithTag(VerifyEmailTags.Continue).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Resend).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Back).performClick()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).performClick()
    compose.onNodeWithTag(VerifyEmailTags.Continue).performClick()
    assertEquals(1, backs)
    assertEquals(1, changes)
    assertEquals(1, checks)
    assertEquals(0, resends)
  }

  @Test
  @Config(qualifiers = "w840dp-h1024dp")
  fun tallTabletKeepsTheHeroAboveReachableActions() {
    launch()
    compose.onNodeWithTag(VerifyEmailTags.TwoColumns).assertDoesNotExist()
    val hero = compose.onNodeWithTag(VerifyEmailTags.Hero).fetchSemanticsNode().boundsInRoot
    val actions = compose.onNodeWithTag(VerifyEmailTags.Actions).fetchSemanticsNode().boundsInRoot
    assertTrue("portrait actions stay below the hero", hero.bottom < actions.top)
    compose.onNodeWithTag(VerifyEmailTags.Continue).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Resend).performScrollTo().assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w360dp-h800dp")
  fun portraitUsesOneColumnAndCountdownControlsResend() {
    launch()
    compose.onNodeWithTag(VerifyEmailTags.TwoColumns).assertDoesNotExist()
    compose
        .onNodeWithTag(VerifyEmailTags.Resend)
        .performScrollTo()
        .assertIsNotEnabled()
        .assertTextContains("0:45", substring = true)
    compose.runOnIdle { state.value = state.value.copy(remainingSeconds = 0) }
    compose.onNodeWithTag(VerifyEmailTags.Resend).assertIsEnabled().performClick()
    assertEquals(1, resends)
  }

  @Test
  @Config(qualifiers = "w360dp-h360dp")
  fun shortWindowAndLargeTextKeepControlsReachableByScrolling() {
    launch(fontScale = 1.4f)
    compose
        .onNodeWithTag(VerifyEmailTags.Continue)
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    compose.onNodeWithTag(VerifyEmailTags.Resend).performScrollTo().assertIsDisplayed()
    assertEquals(1, checks)
  }

  @Test
  fun checkingExposesLoadingAndDisablesRepeatedActions() {
    state.value = state.value.copy(checking = true)
    launch()
    compose.onNodeWithTag(VerifyEmailTags.Continue).performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag(VerifyEmailTags.Loading).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Resend).assertIsNotEnabled()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).assertIsNotEnabled()
  }

  @Test
  fun preparingTimingShowsProgressAndDisablesActionsUntilReady() {
    state.value = state.value.copy(preparing = true, remainingSeconds = 0)
    launch()
    compose.onNodeWithTag(VerifyEmailTags.Continue).performScrollTo().assertIsNotEnabled()
    compose
        .onNodeWithTag(VerifyEmailTags.Resend)
        .assertIsNotEnabled()
        .assertTextEquals(
            RuntimeEnvironment.getApplication().getString(R.string.verification_sending)
        )
    compose.onNodeWithTag(VerifyEmailTags.Loading).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).assertIsNotEnabled()
    compose.runOnIdle { state.value = state.value.copy(preparing = false) }
    compose.onNodeWithTag(VerifyEmailTags.Loading).assertDoesNotExist()
    compose.onNodeWithTag(VerifyEmailTags.Continue).assertIsEnabled().performClick()
    compose.onNodeWithTag(VerifyEmailTags.Resend).performScrollTo().assertIsEnabled().performClick()
    assertEquals(1, checks)
    assertEquals(1, resends)
  }

  @Test
  fun allFailureAndSuccessStatesHaveBannerText() {
    launch()
    for (message in VerificationMessage.entries) {
      compose.runOnIdle { state.value = state.value.copy(banner = message) }
      compose.onNodeWithTag(VerifyEmailTags.Banner).performScrollTo().assertIsDisplayed()
      val text =
          compose
              .onNodeWithTag(VerifyEmailTags.Banner)
              .fetchSemanticsNode()
              .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
      assertTrue(text.any { it.text.isNotBlank() })
    }
  }

  // ---- Texts for students and associations alike (#277) ----

  private fun bannerText() =
      compose
          .onNodeWithTag(VerifyEmailTags.Banner)
          .fetchSemanticsNode()
          .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
          .joinToString(" ") { it.text }

  @Test
  fun noVerificationText_mentionsEpfl_forAStudentOrAnAssociation() {
    // Associations verify their own address here (#125), so no text may assume an EPFL inbox.
    for (email in listOf("student.test@epfl.ch", "club@example.org")) {
      state.value = state.value.copy(email = email, banner = null)
      if (email == "student.test@epfl.ch") launch()
      val shown =
          (listOf(null) + VerificationMessage.entries).map { message ->
            compose.runOnIdle { state.value = state.value.copy(banner = message) }
            compose.onNodeWithTag(VerifyEmailTags.Banner).performScrollTo()
            bannerText()
          }
      for (text in shown) assertFalse("\"$text\" mentions EPFL", text.contains("EPFL"))
    }
  }

  @Test
  fun theSpamHintAndTheResendConfirmation_sayInboxAndMailbox() {
    launch()

    assertEquals("Can't find it? Check your mailbox and spam folder.", bannerText())
    compose.runOnIdle { state.value = state.value.copy(banner = VerificationMessage.Sent) }
    assertEquals(
        "We sent a new link to your inbox. It can take a minute to arrive.",
        bannerText(),
    )
  }

  @Test
  fun aSuccessfulResend_onlyConfirms_withNoRetry() {
    // The coaches reported a Retry after a resend: the screen must only confirm it.
    state.value = state.value.copy(banner = VerificationMessage.Sent)
    launch()

    compose
        .onNodeWithText("We sent a new link to your inbox. It can take a minute to arrive.")
        .assertIsDisplayed()
    compose.onAllNodesWithText("Retry", substring = true, ignoreCase = true).assertCountEquals(0)
    // Its two actions stay: Continue, and Resend once the cooldown ends.
    compose.onNodeWithTag(VerifyEmailTags.Continue).performScrollTo().assertIsEnabled()
    compose.onNodeWithTag(VerifyEmailTags.Resend).performScrollTo().assertIsNotEnabled()
  }

  @Test
  fun afterAFailedResend_resendStaysTheWayOut() {
    state.value = state.value.copy(banner = VerificationMessage.NetworkError, remainingSeconds = 0)
    launch()

    assertEquals("Can't connect. Check your connection and try again.", bannerText())
    compose.onNodeWithTag(VerifyEmailTags.Resend).performScrollTo().assertIsEnabled().performClick()
    assertEquals(1, resends)
  }
}
