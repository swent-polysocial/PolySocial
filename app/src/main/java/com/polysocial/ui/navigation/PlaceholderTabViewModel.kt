// Contributors: Claude (loading then placeholder state for the tab root screens, #43).
package com.polysocial.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a tab's root screen shows until its feature is built. */
sealed interface TabUiState {
  /** The tab's content is not ready yet: show the shared loading state. */
  data object Loading : TabUiState

  /** The tab has no feature yet: show its "coming soon" placeholder. */
  data object Placeholder : TabUiState
}

/**
 * State of a tab's root screen while the tab is still a placeholder. It starts in
 * [TabUiState.Loading] and switches to [TabUiState.Placeholder] once its content is ready. A
 * placeholder has nothing to load, so that happens as soon as the ViewModel's first coroutine runs,
 * without an artificial delay. When a tab's feature lands, its own ViewModel replaces this one and
 * stays in [TabUiState.Loading] until its real data arrives.
 *
 * Each tab gets its own instance, scoped to its navigation entry, so the loading state only shows
 * the first time a tab opens.
 */
class PlaceholderTabViewModel : ViewModel() {
  private val _uiState = MutableStateFlow<TabUiState>(TabUiState.Loading)
  val uiState: StateFlow<TabUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch { _uiState.value = TabUiState.Placeholder }
  }
}
