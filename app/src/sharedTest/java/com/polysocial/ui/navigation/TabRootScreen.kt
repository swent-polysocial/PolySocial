// Contributors: Claude (tab root screen switching between loading and placeholder, #43).
// Contributors: Claude (Opus 5.5, Anthropic) (moved to the shared tests: no tab root uses it since
// the Chats tab, #364).
package com.polysocial.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polysocial.ui.common.LoadingState

/** Root screen of [tab]: the shared loading state first, then the tab's placeholder. */
@Composable
fun TabRootScreen(
    tab: Tab,
    modifier: Modifier = Modifier,
    viewModel: PlaceholderTabViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  when (uiState) {
    TabUiState.Loading -> LoadingState(stringResource(tab.loadingMessage), modifier)
    TabUiState.Placeholder -> PlaceholderScreen(tab, modifier)
  }
}
