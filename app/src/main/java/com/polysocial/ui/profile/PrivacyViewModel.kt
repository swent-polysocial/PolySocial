// Contributors: OpenAI Codex (privacy availability state and private contact draft coordination,
// #103).
// Contributors: Claude (Opus 5.5, Anthropic) moved in-app deletion out of the unavailable actions,
// #207; Mohamed Khellaf (reviewed and revised).
package com.polysocial.ui.profile

import androidx.lifecycle.ViewModel
import com.polysocial.model.privacy.PrivacyContactAddress
import com.polysocial.model.privacy.PrivacyContactLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Presentation state; the notice never reads, exports or modifies personal data. */
data class PrivacyUiState(
    val showUnavailable: Boolean = false,
    val contactMessage: PrivacyContactMessage? = null,
)

/** Actionable reasons why the private contact draft could not open. */
enum class PrivacyContactMessage {
  NotConfigured,
  NoEmailApp,
}

/** Explains unavailable actions and opens private contact drafts without changing account data. */
@HiltViewModel
class PrivacyViewModel
@Inject
constructor(
    @PrivacyContactAddress private val contactEmail: String,
    private val contactLauncher: PrivacyContactLauncher,
) : ViewModel() {
  private val _uiState = MutableStateFlow(PrivacyUiState())
  val uiState = _uiState.asStateFlow()

  /** Request the shared not-available message for export or matching. */
  fun onUnavailableAction() {
    _uiState.update { it.copy(showUnavailable = true) }
  }

  /** Clear a dismissed message, including when its screen leaves composition. */
  fun onMessageDismissed() {
    _uiState.update { it.copy(showUnavailable = false) }
  }

  /** Opens a user-controlled email draft once the team has supplied its private address. */
  fun onContactRequested(subject: String) {
    val message =
        if (contactEmail.isBlank()) PrivacyContactMessage.NotConfigured
        else if (!contactLauncher.openDraft(contactEmail, subject)) PrivacyContactMessage.NoEmailApp
        else null
    _uiState.update { it.copy(contactMessage = message) }
  }

  /** Allows another contact attempt after feedback is dismissed. */
  fun onContactMessageDismissed() {
    _uiState.update { it.copy(contactMessage = null) }
  }
}
