// Contributors: OpenAI Codex (privacy availability, repeated actions and private contact tests,
// #103).
package com.polysocial.ui.profile

import com.polysocial.model.privacy.PrivacyContactLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyViewModelTest {
  @Test
  fun missingContactShowsFeedbackWithoutOpeningAnEmailApp() {
    var calls = 0
    val model =
        PrivacyViewModel(
            " ",
            PrivacyContactLauncher { _, _ ->
              calls++
              true
            },
        )
    model.onContactRequested("Request")
    assertEquals(PrivacyContactMessage.NotConfigured, model.uiState.value.contactMessage)
    assertEquals(0, calls)
    model.onContactMessageDismissed()
    assertNull(model.uiState.value.contactMessage)
  }

  @Test
  fun configuredContactOpensTheUserControlledDraft() {
    var recipient = ""
    var subject = ""
    val model =
        PrivacyViewModel(
            "team@example.invalid",
            PrivacyContactLauncher { to, title ->
              recipient = to
              subject = title
              true
            },
        )
    model.onContactRequested("Data request")
    assertEquals("team@example.invalid", recipient)
    assertEquals("Data request", subject)
    assertNull(model.uiState.value.contactMessage)
  }

  @Test
  fun unavailableEmailAppShowsFeedbackAndAllowsRetry() {
    var canOpen = false
    val model = PrivacyViewModel("team@example.invalid", PrivacyContactLauncher { _, _ -> canOpen })
    model.onContactRequested("Request")
    assertEquals(PrivacyContactMessage.NoEmailApp, model.uiState.value.contactMessage)
    canOpen = true
    model.onContactRequested("Request")
    assertNull(model.uiState.value.contactMessage)
  }

  @Test
  fun noticeStartsWithoutAnUnavailableMessage() {
    assertFalse(PrivacyViewModel().uiState.value.showUnavailable)
  }

  @Test
  fun unbuiltActionCanBeExplainedAgainAfterDismissal() {
    val viewModel = PrivacyViewModel()
    viewModel.onUnavailableAction()
    assertTrue(viewModel.uiState.value.showUnavailable)
    viewModel.onMessageDismissed()
    assertFalse(viewModel.uiState.value.showUnavailable)
    viewModel.onUnavailableAction()
    assertTrue(viewModel.uiState.value.showUnavailable)
  }

  @Test
  fun repeatedUnavailableActionsKeepTheSameMessageAndDismissCleanly() {
    val viewModel = PrivacyViewModel()
    repeat(3) { viewModel.onUnavailableAction() }
    assertTrue(viewModel.uiState.value.showUnavailable)
    viewModel.onMessageDismissed()
    viewModel.onMessageDismissed()
    assertFalse(viewModel.uiState.value.showUnavailable)
  }
}
