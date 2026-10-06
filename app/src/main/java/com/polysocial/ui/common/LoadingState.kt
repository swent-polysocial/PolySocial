// Contributors: Claude (shared loading state from the Figma "App shell" section, #43).
package com.polysocial.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.polysocial.resources.C

/**
 * Full-screen loading state shared by every screen that waits for its content: a spinner and a
 * short [message] such as "Loading events…". It fills the screen, so a screen is never blank while
 * it loads.
 */
@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
  Column(
      modifier = modifier.fillMaxSize().testTag(C.Tag.loading_state),
      verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
    Text(text = message, style = MaterialTheme.typography.bodyMedium)
  }
}
