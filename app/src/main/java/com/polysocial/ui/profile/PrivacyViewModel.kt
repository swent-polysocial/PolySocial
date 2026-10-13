// Contributors: OpenAI Codex (read-only privacy screen availability state, #103).
package com.polysocial.ui.profile

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Presentation state; the notice never reads, exports or modifies personal data. */
data class PrivacyUiState(val showUnavailable: Boolean = false)

/** Explains unavailable privacy actions without changing consent or promising data operations. */
class PrivacyViewModel : ViewModel() {
  private val _uiState = MutableStateFlow(PrivacyUiState())
  val uiState = _uiState.asStateFlow()

  /** Request the shared not-available message for export, matching or in-app deletion. */
  fun onUnavailableAction() {
    _uiState.update { it.copy(showUnavailable = true) }
  }

  /** Clear a dismissed message, including when its screen leaves composition. */
  fun onMessageDismissed() {
    _uiState.update { it.copy(showUnavailable = false) }
  }
}
