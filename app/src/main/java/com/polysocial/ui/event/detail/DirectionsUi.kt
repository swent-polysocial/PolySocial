// Contributors: Claude (Sonnet 5.5, Anthropic) (the Directions button and its Snackbar on the event
// detail, #391).
package com.polysocial.ui.event.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polysocial.R
import com.polysocial.model.directions.AndroidDirectionsLauncher
import com.polysocial.model.event.Coordinates
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink

/**
 * What the event detail does about directions (#391): [onOpen] opens public-transport directions to
 * a place, and [unavailable], while not null, is a Snackbar saying no app could open them, which
 * calls [onUnavailableShown] once it has been shown.
 */
data class EventDirections(
    val onOpen: (Coordinates) -> Unit = {},
    val unavailable: DirectionsUnavailable? = null,
    val onUnavailableShown: (DirectionsUnavailable) -> Unit = {},
)

/**
 * The [DirectionsViewModel] of the current screen, opening the links with the phone's own apps. It
 * is given the application context, which outlives the screen, so the ViewModel never keeps an
 * Activity alive.
 */
@Composable
fun rememberDirectionsViewModel(): DirectionsViewModel {
  val applicationContext = LocalContext.current.applicationContext
  return viewModel { DirectionsViewModel(AndroidDirectionsLauncher(applicationContext)) }
}

/**
 * Shows [directions]'s message once. Keyed on its id, so a second tap that fails the same way shows
 * the Snackbar again, and it is marked as shown even if the screen leaves first.
 */
@Composable
internal fun DirectionsSnackbar(directions: EventDirections, snackbarHostState: SnackbarHostState) {
  val unavailable = directions.unavailable
  val text = stringResource(R.string.event_detail_directions_unavailable)
  val currentDirections by rememberUpdatedState(directions)
  LaunchedEffect(unavailable) {
    val shown = unavailable ?: return@LaunchedEffect
    try {
      snackbarHostState.currentSnackbarData?.dismiss()
      snackbarHostState.showSnackbar(text, duration = SnackbarDuration.Long)
    } finally {
      currentDirections.onUnavailableShown(shown)
    }
  }
}

/** "Directions", under the event's place: an outlined pill with the public-transport icon. */
@Composable
internal fun DirectionsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(26.dp)
  Row(
      modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .clip(shape)
          .border(1.dp, Border, shape)
          .clickable(role = Role.Button, onClick = onClick)
          .padding(horizontal = 16.dp)
          .testTag(EventDetailTags.DIRECTIONS),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
        painterResource(R.drawable.ic_navigation),
        contentDescription = null,
        tint = Ink,
        modifier = Modifier.size(18.dp),
    )
    Text(
        stringResource(R.string.event_detail_directions),
        style = MaterialTheme.typography.titleSmall,
        color = Ink,
    )
  }
}
