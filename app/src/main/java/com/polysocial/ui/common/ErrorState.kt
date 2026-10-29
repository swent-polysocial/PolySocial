// Contributors: Claude (shared error state from the Figma "Tab · couldn't load" and "App start ·
// couldn't load profile" frames, #74; scrolling and heading after review; its Retry button moved to
// the shared StateButton, #194); Claude (optional secondary link, for Log out at app start, #208).
package com.polysocial.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Surface
import com.polysocial.ui.theme.exact

/** A second way out of an [ErrorState], shown as a text link under Retry. */
data class ErrorAction(val label: String, val onClick: () -> Unit)

/**
 * Full-screen error state shared by every screen whose content couldn't load: the offline icon, a
 * [title], a [message] and a Retry button that calls [onRetry]. Centred and sized as in the Figma
 * "Tab · couldn't load" and "App start · couldn't load profile" frames. It scrolls when the screen
 * is too short (landscape with large text), so Retry stays reachable. A [secondaryAction] adds a
 * text link under Retry (app start's Log out, #208).
 */
@Composable
fun ErrorState(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryAction: ErrorAction? = null,
) {
  // Figma's texts use the font's own line height ("auto"), not the type scale's fixed one.
  val typography = MaterialTheme.typography
  BoxWithConstraints(modifier.fillMaxSize().testTag(C.Tag.error_state)) {
    // At least the screen's height, so the content stays centred whenever it fits.
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
          Modifier.size(72.dp).background(Surface, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(R.drawable.ic_couldnt_load),
            contentDescription = null,
            tint = AccentText,
            modifier = Modifier.size(30.dp),
        )
      }
      Text(
          text = title,
          style = typography.titleMedium.copy(lineHeight = TextUnit.Unspecified).exact(),
          color = Ink,
          textAlign = TextAlign.Center,
          modifier = Modifier.widthIn(max = MAX_TEXT_WIDTH).semantics { heading() },
      )
      Text(
          text = message,
          style = typography.bodyLarge.copy(lineHeight = TextUnit.Unspecified).exact(),
          color = Ink2,
          textAlign = TextAlign.Center,
          modifier = Modifier.widthIn(max = MAX_TEXT_WIDTH),
      )
      StateButton(
          stringResource(R.string.retry),
          onRetry,
          Modifier.testTag(C.Tag.error_retry),
      )
      secondaryAction?.let { action ->
        // A 48 dp touch target whose text still sits the stack's 10 dp under Retry (Figma 490:26):
        // the padding above the text is taken from that gap.
        TextLink(
            action.label,
            action.onClick,
            Modifier.overlapVertically(LINK_PADDING)
                .heightIn(min = 48.dp)
                .testTag(C.Tag.error_secondary),
            contentPadding = PaddingValues(vertical = LINK_PADDING),
        )
      }
    }
  }
}

/** The width of Figma's text block, 40 dp inside each edge of the 360 dp frame. */
private val MAX_TEXT_WIDTH = 280.dp

/** Space above and below the link's text, inside its 48 dp touch target. */
private val LINK_PADDING = 15.dp

/**
 * Lays the content out [padding] shorter at the top and the bottom, so that padding grows the touch
 * area over the neighbouring space without moving anything.
 */
private fun Modifier.overlapVertically(padding: Dp) = layout { measurable, constraints ->
  val extra = padding.roundToPx()
  val placeable = measurable.measure(constraints.offset(vertical = 2 * extra))
  layout(placeable.width, (placeable.height - 2 * extra).coerceAtLeast(0)) {
    placeable.place(0, -extra)
  }
}
