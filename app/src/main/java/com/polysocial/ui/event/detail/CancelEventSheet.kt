// Contributors: Claude (Cancel event confirmation from the Figma "Cancel event" frames, #131).
package com.polysocial.ui.event.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.exact
import kotlinx.coroutines.launch

/**
 * The "Cancel this event?" bottom sheet (Figma section 10, "Cancel event · confirm", "· cancelling"
 * and "· offline"), for the event titled [title]. While the event is deleted, the sheet can't be
 * dismissed and Keep event is disabled. A failure shows its reason above Try again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CancelEventSheet(
    title: String,
    state: CancelEventState,
    onConfirm: () -> Unit,
    onKeep: () -> Unit,
) {
  val cancelling = state == CancelEventState.Cancelling || state == CancelEventState.Cancelled
  val locked by rememberUpdatedState(cancelling)
  val sheetState =
      rememberModalBottomSheetState(
          skipPartiallyExpanded = true,
          confirmValueChange = { it != SheetValue.Hidden || !locked },
      )
  val scope = rememberCoroutineScope()
  ModalBottomSheet(
      onDismissRequest = onKeep,
      sheetState = sheetState,
      shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
      containerColor = Bg,
      scrimColor = Ink.copy(alpha = 0.45f),
      // Figma puts the handle at the start, in the sheet's content.
      dragHandle = null,
      modifier = Modifier.testTag(EventDetailTags.CANCEL_SHEET),
  ) {
    SheetContent(title, state, cancelling, onConfirm) {
      scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onKeep() }
    }
  }
}

/** The sheet's handle, texts and buttons. */
@Composable
private fun SheetContent(
    title: String,
    state: CancelEventState,
    cancelling: Boolean,
    onConfirm: () -> Unit,
    onKeep: () -> Unit,
) {
  Column(
      Modifier.verticalScroll(rememberScrollState())
          .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(Modifier.size(width = 36.dp, height = 4.dp).background(Border, RoundedCornerShape(2.dp)))
    Box(
        Modifier.size(44.dp).background(ICON_BACKGROUND, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(R.drawable.ic_calendar_cancel),
          contentDescription = null,
          tint = AccentText,
          modifier = Modifier.size(22.dp),
      )
    }
    Text(
        text = stringResource(R.string.cancel_event_title),
        style =
            MaterialTheme.typography.titleLarge
                .copy(fontSize = 20.sp, lineHeight = TextUnit.Unspecified)
                .exact(),
        fontWeight = FontWeight.ExtraBold,
        color = Ink,
        modifier = Modifier.semantics { heading() },
    )
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
    Box(
        Modifier.fillMaxWidth()
            .height(47.dp)
            .alpha(if (cancelling) 0.4f else 1f)
            .clip(RoundedCornerShape(26.dp))
            .clickable(enabled = !cancelling, role = Role.Button, onClick = onKeep)
            .testTag(EventDetailTags.KEEP_EVENT),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          stringResource(R.string.cancel_event_keep),
          style = MaterialTheme.typography.titleSmall,
          color = Ink,
      )
    }
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

/** Figma's pale red behind the sheet's icon. */
private val ICON_BACKGROUND = Color(0xFFFCE7E8)
