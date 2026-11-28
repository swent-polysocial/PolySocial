// Contributors: OpenAI Codex (6.2 Sol, high; centered handle variant for Friends, #360).
// Contributors: Claude (Opus 5.5, Anthropic) shared the Cancel event and Delete account sheets'
// parts, #296.
// Contributors: Claude (Opus 5.5, Anthropic) (the Friends sheet, badge and pill moved here from
// FriendsScreen.kt to share them with Unsend, #436).
package com.polysocial.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.ui.profile.privacyTextStyle
import com.polysocial.ui.theme.AccentPale
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Surface
import com.polysocial.ui.theme.exact
import kotlinx.coroutines.launch

/**
 * Figma's confirmation bottom sheet (Cancel event, Delete account): white, expanded at once, with
 * 28 dp top corners over a 45% Ink scrim, and the handle at the start of [content] instead of
 * Material's. [content] is laid out in a scrolling column, 20 dp from the sides and 12 dp apart.
 * [centerHandle] uses the centered 32 dp handle from the Friends design.
 *
 * While [locked] (a call runs), Back, a swipe and the scrim neither close it nor call
 * [onDismissRequest]; otherwise they call it. [content] gets `dismiss`, which slides the sheet away
 * and then calls [onDismissRequest], for a button that closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationSheet(
    locked: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    centerHandle: Boolean = false,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
  val isLocked by rememberUpdatedState(locked)
  val onDismiss by rememberUpdatedState(onDismissRequest)
  val sheetState =
      rememberModalBottomSheetState(
          skipPartiallyExpanded = true,
          confirmValueChange = { it != SheetValue.Hidden || !isLocked },
      )
  val scope = rememberCoroutineScope()
  val dismiss = {
    scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    Unit
  }
  ModalBottomSheet(
      // Material calls it for Back even when confirmValueChange keeps the sheet open.
      onDismissRequest = { if (!isLocked) onDismiss() },
      sheetState = sheetState,
      shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
      containerColor = Bg,
      scrimColor = Ink.copy(alpha = 0.45f),
      // Figma puts the handle at the start, in the sheet's content.
      dragHandle = null,
      modifier = modifier,
  ) {
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
          Modifier.align(if (centerHandle) Alignment.CenterHorizontally else Alignment.Start)
              .size(width = if (centerHandle) 32.dp else 36.dp, height = 4.dp)
              .background(Border, RoundedCornerShape(2.dp))
      )
      content(dismiss)
    }
  }
}

/**
 * The sheet's 44 dp icon circle on [background]. With a [tint], [icon] is drawn in that one color;
 * without, in its own colors.
 */
@Composable
fun SheetIcon(@DrawableRes icon: Int, tint: Color? = null, background: Color = AccentPale) {
  Box(
      Modifier.size(44.dp).background(background, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    // Decorative: the title next to it says what the sheet is about.
    if (tint != null) {
      Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = ICON_SIZE)
    } else {
      Image(painterResource(icon), contentDescription = null, modifier = ICON_SIZE)
    }
  }
}

/** The sheet's 20 sp extra-bold title, read as a heading. */
@Composable
fun SheetTitle(@StringRes text: Int, modifier: Modifier = Modifier) {
  Text(
      text = stringResource(text),
      style =
          MaterialTheme.typography.titleLarge
              .copy(fontSize = 20.sp, lineHeight = TextUnit.Unspecified)
              .exact(),
      fontWeight = FontWeight.ExtraBold,
      color = Ink,
      modifier = modifier.semantics { heading() },
  )
}

/**
 * The sheet's plain secondary button ("Keep event", "Cancel"), faded while it can't be used. Figma
 * draws it 47 dp high; it is at least 48 dp, Android's minimum touch target.
 */
@Composable
fun SheetSecondaryButton(
    @StringRes text: Int,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
  Box(
      Modifier.fillMaxWidth()
          .heightIn(min = 48.dp)
          .alpha(if (enabled) 1f else 0.4f)
          .clip(RoundedCornerShape(26.dp))
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .testTag(tag),
      contentAlignment = Alignment.Center,
  ) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, color = Ink)
  }
}

/**
 * The Friends design's confirmation sheet (Remove friend, #360; Unsend, #436): a centered handle,
 * [icon]'s badge, [title], [body], a red [confirm] button and an outlined [keep] pill. Confirming
 * slides the sheet away, then calls [onConfirm]; Keep, Back, a swipe or the scrim slide it away and
 * call [onKeep].
 */
@Composable
fun CenteredConfirmationSheet(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    confirm: String,
    @StringRes keep: Int,
    confirmTag: String,
    keepTag: String,
    onConfirm: () -> Unit,
    onKeep: () -> Unit,
) {
  var confirming by remember { mutableStateOf(false) }
  ConfirmationSheet(
      locked = false,
      onDismissRequest = { if (confirming) onConfirm() else onKeep() },
      centerHandle = true,
  ) { dismiss ->
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      IconBadge(icon, AccentSoft)
      Text(
          title,
          style = privacyTextStyle(22, 28f, FontWeight.ExtraBold),
          color = Ink,
          textAlign = TextAlign.Center,
          modifier = Modifier.semantics { heading() },
      )
      Text(
          body,
          style = privacyTextStyle(14, 18f),
          color = Ink3,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(horizontal = 12.dp),
      )
      PrimaryButton(
          confirm,
          enabled = !confirming,
          tag = confirmTag,
          color = AccentText,
          textStyle = privacyTextStyle(13, 17f, FontWeight.Bold),
          onClick = {
            confirming = true
            dismiss()
          },
      )
      OutlinePill(keep, keepTag, dismiss)
    }
  }
}

/** The Friends design's 56 dp rounded badge, with [icon] in its own colors on [background]. */
@Composable
fun IconBadge(@DrawableRes icon: Int, background: Color = Surface) {
  Box(
      Modifier.size(56.dp).background(background, RoundedCornerShape(17.dp)),
      contentAlignment = Alignment.Center,
  ) {
    Icon(painterResource(icon), null, Modifier.size(25.dp), tint = Color.Unspecified)
  }
}

/** The Friends design's outlined full-width pill button, at least 52 dp high. */
@Composable
fun OutlinePill(@StringRes label: Int, tag: String, onClick: () -> Unit) {
  Box(
      Modifier.fillMaxWidth()
          .heightIn(min = 52.dp)
          .clip(CircleShape)
          .border(1.dp, Border, CircleShape)
          .clickable(role = Role.Button, onClick = onClick)
          .testTag(tag)
          .padding(horizontal = 16.dp, vertical = 8.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        stringResource(label),
        color = Ink,
        style = privacyTextStyle(15, 19f, FontWeight.Bold),
        textAlign = TextAlign.Center,
    )
  }
}

private val ICON_SIZE = Modifier.size(22.dp)
