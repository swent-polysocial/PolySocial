// Contributors: Claude (tab placeholder screen from the Figma "App shell" section, #41; placeholder
// detail screen, #42; app bar action placeholder, #84).
// Contributors: Claude (Opus 5.5, Anthropic) (the tab root placeholder moved to the shared tests,
// #364).
package com.polysocial.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.polysocial.R

/**
 * Stand-in for a screen one level below a tab's root (e.g. an event's detail) until it is built.
 */
@Composable
fun PlaceholderDetailScreen(tab: Tab, modifier: Modifier = Modifier) {
  Box(
      modifier = modifier.fillMaxSize().testTag(tab.detailTag),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = stringResource(R.string.placeholder_detail_coming_soon),
        style = MaterialTheme.typography.titleMedium,
    )
  }
}

/** Stand-in for the screen an app bar [action] opens until its feature is built. */
@Composable
fun PlaceholderActionScreen(action: TabAction, modifier: Modifier = Modifier) {
  Box(
      modifier = modifier.fillMaxSize().testTag(action.screenTag),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = stringResource(R.string.placeholder_coming_soon, stringResource(action.label)),
        style = MaterialTheme.typography.titleMedium,
    )
  }
}
