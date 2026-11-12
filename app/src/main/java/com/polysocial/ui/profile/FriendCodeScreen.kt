// Contributors: Claude Opus 5.5 (drafted My friend code from Figma section 15, #339; the quiet
// zone, small windows and black on white after review);
// Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.qr.QrCode
import com.polysocial.resources.C
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.group.initials
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.SuccessSoft
import com.polysocial.ui.theme.Surface
import com.polysocial.ui.theme.WarningSoft
import kotlin.math.floor

/**
 * Navigation boundary for My friend code (#339): Close, Back and "Back to profile" call [onBack].
 */
@Composable
fun FriendCodeRoute(onBack: () -> Unit, viewModel: FriendCodeViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  BackHandler(onBack = onBack)
  FriendCodeScreen(state, onBack)
}

/**
 * My friend code, as in Figma section 15 (#336): the student's own QR code on a card under their
 * name, section and year, with a note on what sharing it does; or, when no code can be drawn, "My
 * friend code · couldn't show" (#339). Figma's "Scan a friend's code" button comes with scanning,
 * in its own task.
 */
@Composable
fun FriendCodeScreen(state: FriendCodeUiState, onBack: () -> Unit) {
  val background = if (state is FriendCodeUiState.Shown) Surface else Bg
  Column(Modifier.fillMaxSize().background(background).testTag(C.Tag.friend_code_screen)) {
    PrivacyHeader(
        R.string.friend_code_title,
        C.Tag.friend_code_heading,
        onBack,
        icon = R.drawable.ic_close,
        iconDescription = R.string.friend_code_close,
        backTag = C.Tag.friend_code_close,
    )
    when (state) {
      is FriendCodeUiState.Shown -> FriendCodeContent(state)
      FriendCodeUiState.CouldNotShow -> FriendCodeError(onBack)
    }
  }
}

@Composable
private fun FriendCodeContent(state: FriendCodeUiState.Shown) {
  BoxWithConstraints(Modifier.fillMaxSize().testTag(C.Tag.friend_code_content)) {
    // Figma's 205 dp, smaller when the window can't show the whole code at once (split screen, a
    // narrow phone at a large display size): the screen scrolls, but only a whole code scans.
    val qrSide =
        minOf(QR_SIDE, maxWidth - (SCREEN_PADDING + CARD_PADDING) * 2, maxHeight)
            .coerceAtLeast(0.dp)
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SCREEN_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.height(106.dp))
      Column(
          Modifier.width(qrSide + CARD_PADDING * 2)
              .background(Bg, RoundedCornerShape(28.dp))
              .padding(CARD_PADDING),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        FriendCodeOwner(state.name, state.details)
        Spacer(Modifier.height(14.dp))
        QrSymbol(state.code, Modifier.size(qrSide))
      }
      FriendCodeInfo()
    }
  }
}

/** Figma's note under the card: what sharing the code does. */
@Composable
private fun FriendCodeInfo() {
  Spacer(Modifier.height(18.dp))
  Row(
      Modifier.fillMaxWidth()
          .background(Bg, RoundedCornerShape(14.dp))
          .padding(horizontal = 14.dp, vertical = 13.dp)
          .testTag(C.Tag.friend_code_info),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Image(
        painterResource(R.drawable.ic_info),
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        colorFilter = ColorFilter.tint(Ink2),
    )
    Text(
        stringResource(R.string.friend_code_info),
        color = Ink2,
        style = privacyTextStyle(13, 19f),
    )
  }
  Spacer(Modifier.height(24.dp))
}

/** The card's header: the student's initials in Figma's green avatar, their name and details. */
@Composable
private fun FriendCodeOwner(name: String, details: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
        Modifier.size(40.dp).background(SuccessSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Text(initials(name), color = Success, style = privacyTextStyle(14, 16.4f, FontWeight.Bold))
    }
    Spacer(Modifier.width(10.dp))
    Column {
      Text(
          name,
          color = Ink,
          style = privacyTextStyle(16, 20.2f, FontWeight.ExtraBold),
          modifier = Modifier.testTag(C.Tag.friend_code_name),
      )
      if (details.isNotEmpty())
          Text(
              details,
              color = Ink2,
              style = privacyTextStyle(12, 15.1f),
              modifier = Modifier.testTag(C.Tag.friend_code_details),
          )
    }
  }
}

/**
 * [code]'s dark modules in black on white, inside [modifier]'s square with a margin of
 * [QUIET_ZONE_MODULES] light modules on each side: the quiet zone scanners need, kept inside the
 * square since the card's name sits right above it. Always black on white, not theme colors, so a
 * theme change can't make the code unscannable.
 */
@Composable
private fun QrSymbol(code: QrCode, modifier: Modifier) {
  val description = stringResource(R.string.friend_code_description)
  Canvas(
      modifier
          .background(Color.White)
          .semantics {
            contentDescription = description
            role = Role.Image
          }
          .testTag(C.Tag.friend_code_qr)
  ) {
    // Whole pixels per module, centred: fractional sizes leave light seams between dark modules.
    val module = floor(size.minDimension / (code.size + 2 * QUIET_ZONE_MODULES))
    val start = floor((size.minDimension - module * code.size) / 2)
    for (y in 0 until code.size) for (x in 0 until code.size) {
      if (code.isDark(x, y)) {
        drawRect(Color.Black, Offset(start + x * module, start + y * module), Size(module, module))
      }
    }
  }
}

/** Figma "My friend code · couldn't show": the alert, what happened, and Back to profile. */
@Composable
private fun FriendCodeError(onBack: () -> Unit) {
  // At least the screen's height, so the button sits at the bottom whenever everything fits, and
  // the screen scrolls instead when it doesn't (landscape with large text).
  BoxWithConstraints(Modifier.fillMaxSize().testTag(C.Tag.friend_code_error)) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) { FriendCodeErrorMessage() }
      Column {
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            stringResource(R.string.friend_code_back_to_profile),
            enabled = true,
            tag = C.Tag.friend_code_back_to_profile,
            onClick = onBack,
        )
        Spacer(Modifier.height(28.dp))
      }
    }
  }
}

@Composable
private fun FriendCodeErrorMessage() {
  Spacer(Modifier.height(160.dp))
  Box(
      Modifier.size(64.dp).background(WarningSoft, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Image(
        painterResource(R.drawable.ic_alert),
        contentDescription = null,
        modifier = Modifier.size(28.dp),
        colorFilter = ColorFilter.tint(AccentText),
    )
  }
  Spacer(Modifier.height(20.dp))
  Text(
      stringResource(R.string.friend_code_error_title),
      color = Ink,
      textAlign = TextAlign.Center,
      style = privacyTextStyle(22, 27.7f, FontWeight.ExtraBold),
      modifier = Modifier.semantics { heading() },
  )
  Spacer(Modifier.height(12.dp))
  Text(
      stringResource(R.string.friend_code_error_body),
      color = Ink2,
      textAlign = TextAlign.Center,
      style = privacyTextStyle(14, 18f),
      modifier = Modifier.widthIn(max = 290.dp),
  )
}

private val QR_SIDE: Dp = 205.dp
private val SCREEN_PADDING: Dp = 24.dp
private val CARD_PADDING: Dp = 24.dp

/** The light margin around a QR code that scanners need, in modules (ISO/IEC 18004). */
private const val QUIET_ZONE_MODULES = 4
