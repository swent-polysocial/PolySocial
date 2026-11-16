// Contributors: Claude (Sonnet 5.5, Anthropic) built the "Cancel your registration?" sheet from the
// Figma "Event detail · cancel registration (in a group)" frame, #318.
package com.polysocial.ui.event.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.polysocial.R
import com.polysocial.ui.common.ConfirmationSheet
import com.polysocial.ui.common.SheetIcon
import com.polysocial.ui.common.SheetSecondaryButton
import com.polysocial.ui.common.SheetTitle
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.exact

/**
 * The "Cancel your registration?" bottom sheet (Figma "Event detail · cancel registration (in a
 * group)") of a student in a group for the event titled [title]: cancelling also leaves the group,
 * and deletes it if the student is its last member. While the group is left, the sheet can't be
 * dismissed. A failure shows its reason above Try again, with nothing changed.
 */
@Composable
internal fun CancelRegistrationSheet(
    title: String,
    state: CancelRegistrationState,
    onConfirm: () -> Unit,
    onKeep: () -> Unit,
) {
  val leaving = state == CancelRegistrationState.Leaving
  ConfirmationSheet(
      locked = leaving,
      onDismissRequest = onKeep,
      modifier = Modifier.testTag(EventDetailTags.CANCEL_REGISTRATION_SHEET),
  ) { dismiss ->
    SheetIcon(R.drawable.ic_calendar_cancel, tint = AccentText)
    SheetTitle(R.string.cancel_registration_title)
    Text(
        text = stringResource(R.string.cancel_registration_body, title),
        style = MaterialTheme.typography.bodyMedium.exact(),
        color = Ink2,
    )
    if (state is CancelRegistrationState.Failed) {
      Text(
          text =
              stringResource(
                  when (state.reason) {
                    CancelRegistrationState.Failed.Reason.OFFLINE ->
                        R.string.cancel_registration_offline
                    CancelRegistrationState.Failed.Reason.UNEXPECTED ->
                        R.string.cancel_registration_unexpected
                  }
              ),
          style = MaterialTheme.typography.bodySmall.exact(),
          color = AccentText,
          modifier = Modifier.testTag(EventDetailTags.CANCEL_REGISTRATION_FAILURE),
      )
    }
    ConfirmButton(
        text =
            when {
              leaving -> R.string.cancel_registration_leaving
              state is CancelRegistrationState.Failed -> R.string.create_event_try_again
              else -> R.string.event_detail_cancel_registration
            },
        cancelling = leaving,
        onClick = onConfirm,
        tag = EventDetailTags.CONFIRM_CANCEL_REGISTRATION,
    )
    SheetSecondaryButton(
        R.string.cancel_registration_keep,
        enabled = !leaving,
        tag = EventDetailTags.KEEP_REGISTRATION,
        onClick = dismiss,
    )
  }
}
