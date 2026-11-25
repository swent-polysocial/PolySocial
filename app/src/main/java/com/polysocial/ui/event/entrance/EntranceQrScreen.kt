// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Entrance QR screen from Figma section 20,
// frames 06, 16, 18, 19 and 20, #468); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.event.entrance

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.common.QrSymbol
import com.polysocial.ui.profile.PrivacyHeader
import com.polysocial.ui.profile.privacyTextStyle
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Surface

/** Test tags of the Entrance QR screen. */
object EntranceQrTags {
  const val SCREEN = "entrance_qr_screen"
  const val BACK = "entrance_qr_back"
  const val HEADING = "entrance_qr_heading"
  const val CODE = "entrance_qr_code"
  const val EMPTY_CARD = "entrance_qr_empty_card"
  const val MESSAGE = "entrance_qr_message"
  const val CAPTION = "entrance_qr_caption"
  const val TRY_AGAIN = "entrance_qr_try_again"
  const val BACK_TO_EVENT = "entrance_qr_back_to_event"
}

/**
 * Navigation boundary of the Entrance QR (#468): Back, system Back and "Back to event" call
 * [onBack]. The screen stays on while it is shown, so an organizer holding it at the door doesn't
 * see it go dark.
 */
@Composable
fun EntranceQrRoute(onBack: () -> Unit, viewModel: EntranceQrViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  BackHandler(onBack = onBack)
  val view = LocalView.current
  DisposableEffect(view) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }
  EntranceQrScreen(state, onBack, viewModel::retry)
}

/** The Entrance QR in each of its Figma section 20 frames (06, 16, 18, 19, 20). */
@Composable
fun EntranceQrScreen(state: EntranceQrUiState, onBack: () -> Unit, onTryAgain: () -> Unit) {
  Column(Modifier.fillMaxSize().background(Bg).testTag(EntranceQrTags.SCREEN)) {
    PrivacyHeader(
        R.string.entrance_qr_title,
        EntranceQrTags.HEADING,
        onBack,
        iconDescription = R.string.entrance_qr_back,
        backTag = EntranceQrTags.BACK,
    )
    when (state) {
      EntranceQrUiState.Loading -> LoadingState(stringResource(R.string.entrance_qr_loading))
      is EntranceQrUiState.Shown -> CodeLayout(onBack, onTryAgain = null) { ShownCode(state) }
      is EntranceQrUiState.Offline ->
          CodeLayout(onBack, onTryAgain) {
            Title(state.title)
            Message(R.string.entrance_qr_offline)
            CodeCard { Box(Modifier.size(QR_SIDE).testTag(EntranceQrTags.EMPTY_CARD)) }
            Caption(stringResource(R.string.entrance_qr_waiting))
          }
      is EntranceQrUiState.Failed ->
          CodeLayout(onBack, onTryAgain) {
            state.title?.let { Title(it) }
            Message(R.string.entrance_qr_failed)
          }
      is EntranceQrUiState.NotOpenYet ->
          WindowMessage(
              stringResource(R.string.entrance_qr_not_open_title, state.opensAt),
              stringResource(R.string.entrance_qr_not_open_body),
              place(state.title, state.venue),
              onBack,
          )
      is EntranceQrUiState.Closed ->
          WindowMessage(
              stringResource(R.string.entrance_qr_closed_title),
              stringResource(R.string.entrance_qr_closed_body, state.title, state.closedAt),
              place(state.title, state.venue),
              onBack,
          )
    }
  }
}

/** Frame 06's content: the event, what to do, the code on its card and the venue. */
@Composable
private fun ColumnScope.ShownCode(state: EntranceQrUiState.Shown) {
  Title(state.title)
  Message(R.string.entrance_qr_subtitle)
  CodeCard {
    QrSymbol(
        state.code,
        stringResource(R.string.entrance_qr_description, state.title),
        EntranceQrTags.CODE,
        Modifier.size(QR_SIDE),
    )
  }
  Caption(
      if (state.venue.isBlank()) stringResource(R.string.entrance_qr_caption_no_venue)
      else stringResource(R.string.entrance_qr_caption, state.venue)
  )
}

/**
 * The code frames' layout: [content] from Figma's 68 dp below the header, then Try again (when
 * [onTryAgain] isn't null) and Back to event at the bottom. At least the screen's height, so the
 * buttons sit at the bottom whenever everything fits, and it scrolls when it doesn't.
 */
@Composable
private fun CodeLayout(
    onBack: () -> Unit,
    onTryAgain: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(68.dp))
        content()
      }
      Buttons(onBack, onTryAgain)
    }
  }
}

/** Frames 18 and 19: the pin badge, when check-in opens or that it closed, and the event. */
@Composable
private fun WindowMessage(title: String, body: String, place: String, onBack: () -> Unit) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(175.dp))
        Box(
            Modifier.size(56.dp).background(Surface, RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center,
        ) {
          Image(
              painterResource(R.drawable.ic_pin),
              contentDescription = null,
              modifier = Modifier.size(25.dp),
              colorFilter = ColorFilter.tint(Ink),
          )
        }
        Spacer(Modifier.height(14.dp))
        Title(title)
        Text(
            body,
            color = Ink2,
            textAlign = TextAlign.Center,
            style = privacyTextStyle(14, 18f),
            modifier =
                Modifier.widthIn(max = 280.dp).padding(top = 14.dp).testTag(EntranceQrTags.MESSAGE),
        )
        Caption(place)
      }
      Buttons(onBack, onTryAgain = null)
    }
  }
}

@Composable
private fun Title(title: String) {
  Text(
      title,
      color = Ink,
      textAlign = TextAlign.Center,
      style = privacyTextStyle(22, 27.7f, FontWeight.ExtraBold),
      modifier = Modifier.fillMaxWidth().semantics { heading() },
  )
}

@Composable
private fun Message(@StringRes text: Int) {
  Text(
      stringResource(text),
      color = Ink2,
      textAlign = TextAlign.Center,
      style = privacyTextStyle(14, 18f),
      modifier =
          Modifier.widthIn(max = 275.dp).padding(top = 18.dp).testTag(EntranceQrTags.MESSAGE),
  )
}

/** Figma's white card with a light border and 22 dp corners, around the 224 dp code. */
@Composable
private fun CodeCard(content: @Composable () -> Unit) {
  Spacer(Modifier.height(18.dp))
  Box(
      Modifier.clip(RoundedCornerShape(22.dp))
          .background(Bg)
          .border(1.dp, Border, RoundedCornerShape(22.dp))
          .padding(21.dp),
      contentAlignment = Alignment.Center,
  ) {
    content()
  }
}

@Composable
private fun Caption(text: String) {
  Text(
      text,
      color = Ink2,
      textAlign = TextAlign.Center,
      style = privacyTextStyle(13, 16.4f, FontWeight.Bold),
      modifier = Modifier.fillMaxWidth().padding(top = 18.dp).testTag(EntranceQrTags.CAPTION),
  )
}

/** Try again (dark, when [onTryAgain] isn't null) above Back to event (outlined), at the bottom. */
@Composable
private fun Buttons(onBack: () -> Unit, onTryAgain: (() -> Unit)?) {
  Column {
    Spacer(Modifier.height(24.dp))
    if (onTryAgain != null) {
      PrimaryButton(
          stringResource(R.string.entrance_qr_try_again),
          enabled = true,
          tag = EntranceQrTags.TRY_AGAIN,
          textStyle = BUTTON_STYLE,
          onClick = onTryAgain,
      )
      Spacer(Modifier.height(10.dp))
    }
    Box(
        Modifier.fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Bg)
            .border(1.dp, Border, RoundedCornerShape(26.dp))
            .clickable(role = Role.Button, onClick = onBack)
            .testTag(EntranceQrTags.BACK_TO_EVENT),
        contentAlignment = Alignment.Center,
    ) {
      Text(stringResource(R.string.entrance_qr_back_to_event), color = Ink, style = BUTTON_STYLE)
    }
    Spacer(Modifier.height(28.dp))
  }
}

@Composable
private fun place(title: String, venue: String): String =
    if (venue.isBlank()) title else stringResource(R.string.entrance_qr_event_place, title, venue)

/** Figma's button label: 15 sp bold, 18.9 sp lines. */
private val BUTTON_STYLE
  @Composable get() = privacyTextStyle(15, 18.9f, FontWeight.Bold)

private val QR_SIDE: Dp = 224.dp
