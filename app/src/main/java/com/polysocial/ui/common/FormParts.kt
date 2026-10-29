// Contributors: Claude (shared from the copies in Create Event, Log in, Profile setup, Events and
// Event detail, #251; modifiers after review).
package com.polysocial.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Warning
import com.polysocial.ui.theme.WarningSoft

/**
 * The label above a form field (Figma: 13 sp semi-bold, Ink2). [style] lets a screen keep its own
 * measured variant of the same style, for example one rendered exactly as in Figma.
 */
@Composable
fun FieldLabel(
    @StringRes text: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
) {
  Text(stringResource(text), style = style, color = Ink2, modifier = modifier)
}

/**
 * Figma's amber "you're offline" banner: the offline icon, then [message], on WarningSoft with 14
 * dp corners. [tag] is its test tag. A screen whose message can wrap passes [Alignment.Top], so the
 * icon stays on the first line.
 */
@Composable
fun OfflineBanner(
    message: String,
    tag: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .background(WarningSoft, RoundedCornerShape(14.dp))
              .padding(horizontal = 14.dp, vertical = 13.dp)
              .testTag(tag),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = verticalAlignment,
  ) {
    Icon(painterResource(R.drawable.ic_offline), contentDescription = null, tint = Warning)
    Text(text = message, style = textStyle, color = Warning)
  }
}

/** Figma's secondary text in forms and banners: 13 sp on an 18 sp line. */
@Composable
fun secondaryTextStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp)

/**
 * Figma's red banner over a form's button when saving fails: the offline icon when [offline],
 * otherwise the error icon, then [title] in bold and [message]. [tag] is its test tag.
 */
@Composable
fun ErrorBanner(
    title: String,
    message: String,
    offline: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier
          .fillMaxWidth()
          .background(AccentSoft, RoundedCornerShape(14.dp))
          .padding(14.dp)
          .testTag(tag),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Image(
        painterResource(if (offline) R.drawable.ic_wifi_off else R.drawable.ic_error),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          title,
          style = secondaryTextStyle().copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
          color = Ink,
      )
      Text(message, style = secondaryTextStyle(), color = Ink2)
    }
  }
}

/**
 * Figma's dark 52 dp pill button: greyed out when it can't be used, with a spinner while [loading].
 * [tag] is its test tag.
 */
@Composable
fun PrimaryButton(
    text: String,
    enabled: Boolean,
    tag: String,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
  val greyed = !enabled && !loading
  Row(
      modifier
          .fillMaxWidth()
          .height(PRIMARY_BUTTON_HEIGHT)
          .clip(MaterialTheme.shapes.large)
          .background(if (greyed) Disabled else Ink)
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .testTag(tag),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (loading) {
      CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          color = Bg,
          trackColor = Bg.copy(alpha = 0.3f),
          strokeWidth = 2.dp,
      )
    }
    Text(text, style = MaterialTheme.typography.titleSmall, color = if (greyed) Ink3 else Bg)
  }
}

/** Figma's primary button height. */
val PRIMARY_BUTTON_HEIGHT = 52.dp
