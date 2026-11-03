// Contributors: Claude (Cancel event confirmation from the Figma "Cancel event" frames, #131; its
// sheet parts moved to ui/common, #296).
package com.polysocial.ui.event.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.ui.common.ConfirmationSheet
import com.polysocial.ui.common.SheetIcon
import com.polysocial.ui.common.SheetSecondaryButton
import com.polysocial.ui.common.SheetTitle
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.exact

/**
 * The "Cancel this event?" bottom sheet (Figma section 10, "Cancel event · confirm", "· cancelling"
 * and "· offline"), for the event titled [title]. While the event is deleted, the sheet can't be
 * dismissed and Keep event is disabled. A failure shows its reason above Try again.
 */
@Composable
internal fun CancelEventSheet(
    title: String,
    state: CancelEventState,
    onConfirm: () -> Unit,
    onKeep: () -> Unit,
) {
  val cancelling = state == CancelEventState.Cancelling || state == CancelEventState.Cancelled
  ConfirmationSheet(
      locked = cancelling,
      onDismissRequest = onKeep,
      modifier = Modifier.testTag(EventDetailTags.CANCEL_SHEET),
  ) { dismiss ->
    SheetIcon(R.drawable.ic_calendar_cancel, tint = AccentText)
    SheetTitle(R.string.cancel_event_title)
    Text(
        text = stringResource(R.string.cancel_event_body, title),
        style = MaterialTheme.typography.bodyMedium.exact(),
        color = Ink2,
    )
    if (state is CancelEventState.Failed) {
      Text(
          text =
              stringResource(
                  when (state.reason) {
                    CancelEventState.Failed.Reason.OFFLINE -> R.string.cancel_event_offline
                    CancelEventState.Failed.Reason.UNEXPECTED -> R.string.cancel_event_unexpected
                  }
              ),
          style = MaterialTheme.typography.bodySmall.exact(),
          color = AccentText,
          modifier = Modifier.testTag(EventDetailTags.CANCEL_FAILURE),
      )
    }
    ConfirmButton(
        text =
            when {
              cancelling -> R.string.cancel_event_cancelling
              state is CancelEventState.Failed -> R.string.create_event_try_again
              else -> R.string.event_detail_cancel_event
            },
        cancelling = cancelling,
        onClick = onConfirm,
    )
    SheetSecondaryButton(
        R.string.cancel_event_keep,
        enabled = !cancelling,
        tag = EventDetailTags.KEEP_EVENT,
        onClick = dismiss,
    )
  }
}

/** Figma's red button: "Cancel event", "Try again", or a spinner and "Cancelling event…". */
@Composable
private fun ConfirmButton(text: Int, cancelling: Boolean, onClick: () -> Unit) {
  Row(
      Modifier.fillMaxWidth()
          .height(52.dp)
          // Figma fades the whole button while cancelling.
          .alpha(if (cancelling) 0.85f else 1f)
          .clip(RoundedCornerShape(26.dp))
          .background(Accent)
          .clickable(enabled = !cancelling, role = Role.Button, onClick = onClick)
          .testTag(EventDetailTags.CONFIRM_CANCEL),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (cancelling) {
      CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          color = Bg,
          trackColor = Bg.copy(alpha = 0.3f),
          strokeWidth = 2.dp,
      )
    }
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, color = Bg)
  }
}
