// Contributors: Claude (tab placeholder screen from the Figma "App shell" section, #41).
package com.polysocial.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.polysocial.R

/** Stand-in for a tab's root screen until its feature is built, e.g. "Events — coming soon". */
@Composable
fun PlaceholderScreen(tab: Tab, modifier: Modifier = Modifier) {
  Column(
      modifier = modifier.fillMaxSize().testTag(tab.screenTag),
      verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
        modifier =
            Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painter = painterResource(tab.icon),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(30.dp),
      )
    }
    Text(
        text = stringResource(R.string.placeholder_coming_soon, stringResource(tab.label)),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
  }
}
