// Contributors: Claude (moved Log in's "Create an account" link here to share it with the app-start
// error's Log out, #208).
package com.polysocial.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.polysocial.ui.theme.AccentText

/** A bold, underlined red text link, like Figma's "Create an account" on Log in. */
@Composable
fun TextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  Text(
      text,
      style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
      color = AccentText,
      textDecoration = TextDecoration.Underline,
      modifier = modifier.clickable(enabled = enabled, onClick = onClick),
  )
}
