// Contributors: Claude (tab root screen switching between loading and placeholder, #43).
// Contributors: OpenAI Codex (Profile privacy menu callback, #103).
package com.polysocial.ui.navigation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.profile.ProfilePrivacyEntry

/** Root screen of [tab]: the shared loading state first, then the tab's placeholder. */
@Composable
fun TabRootScreen(
    tab: Tab,
    modifier: Modifier = Modifier,
    viewModel: PlaceholderTabViewModel = viewModel(),
    onOpenPrivacy: (() -> Unit)? = null,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  when (uiState) {
    TabUiState.Loading -> LoadingState(stringResource(tab.loadingMessage), modifier)
    TabUiState.Placeholder ->
        PlaceholderScreen(tab, modifier) {
          if (tab == Tab.PROFILE && onOpenPrivacy != null) {
            ProfilePrivacyEntry(onOpenPrivacy, Modifier.fillMaxWidth().padding(horizontal = 20.dp))
          }
        }
  }
}
