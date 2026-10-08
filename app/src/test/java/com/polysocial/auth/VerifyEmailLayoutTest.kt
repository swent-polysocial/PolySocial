// Contributors: OpenAI Codex (adaptive verification layout and tagged UI-state tests for #31).
package com.polysocial.auth

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.ui.auth.*
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
}
