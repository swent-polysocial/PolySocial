// Contributors: OpenAI Codex (privacy availability state and repeated-action tests, #103).
package com.polysocial.ui.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyViewModelTest {
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
