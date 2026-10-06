// Contributors: Claude (tests for the tab loading state, #43).
package com.polysocial.ui.navigation

import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceholderTabViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun startsLoadingThenShowsThePlaceholder() =
      runTest(mainDispatcherRule.dispatcher) {
        val viewModel = PlaceholderTabViewModel()
        assertEquals(TabUiState.Loading, viewModel.uiState.value)

        advanceUntilIdle()

        assertEquals(TabUiState.Placeholder, viewModel.uiState.value)
      }
}
