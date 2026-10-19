// Contributors: Claude (shared from ErrorState's Retry button and the event detail's Back button,
// #194).
package com.polysocial.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.exact

/**
 * The dark 160 × 48 dp button under a full-screen state's text, labelled [text]: Retry in the Figma
 * "Tab · couldn't load" frame ([ErrorState]) and the event detail's offline "not saved" state, Back
 * in its "not found" state.
 */
@Composable
fun StateButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
      modifier
          .size(width = 160.dp, height = 48.dp)
          .clip(RoundedCornerShape(24.dp))
          .background(Ink)
          .clickable(role = Role.Button, onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Text(text, style = MaterialTheme.typography.titleSmall.exact(), color = Bg)
  }
}
