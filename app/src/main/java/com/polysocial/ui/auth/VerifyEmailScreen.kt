// Contributors: Figma mockup (verification layout); OpenAI Codex (Compose verification UI and
// adaptive two-column layout for #31).
package com.polysocial.ui.auth

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.ui.theme.*

object VerifyEmailTags {
  const val Screen = "verify_email_destination"
  const val Back = "verification_back"
  const val ChangeAddress = "verification_change_address"
  const val Continue = "verification_continue"
  const val Resend = "verification_resend"
  const val Banner = "verification_banner"
  const val Email = "verification_email"
  const val Loading = "verification_loading"
  const val Snackbar = "verification_snackbar"
  const val Hero = "verification_hero"
  const val Actions = "verification_actions"
  const val TwoColumns = "verification_two_columns"
}

@Composable
fun VerifyEmailScreen(
    state: VerifyEmailUiState,
    onBack: () -> Unit,
    onChangeAddress: () -> Unit,
    onContinue: () -> Unit,
    onResend: () -> Unit,
    onSnackbarShown: () -> Unit,
) {
  val snackbar = remember { SnackbarHostState() }
  val snackbarMessage = state.snackbar?.let { stringResource(it.resource()) }
  val latestOnSnackbarShown by rememberUpdatedState(onSnackbarShown)
  LaunchedEffect(snackbarMessage) {
    snackbarMessage?.let {
      snackbar.showSnackbar(it)
      latestOnSnackbarShown()
    }
  }
  Box(Modifier.fillMaxSize().background(Bg).testTag(VerifyEmailTags.Screen)) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
      val screenHeight = maxHeight
      val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight
      Column(
          Modifier.fillMaxWidth()
              .verticalScroll(rememberScrollState())
              .heightIn(min = screenHeight)
              .padding(horizontal = 24.dp, vertical = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        IconButton(
            onClick = onBack,
            modifier =
                Modifier.align(Alignment.Start)
                    .size(48.dp)
                    .offset(x = (-18).dp)
                    .testTag(VerifyEmailTags.Back),
        ) {
          Image(
              painterResource(R.drawable.signup_back),
              stringResource(R.string.signup_back),
              Modifier.size(22.dp),
          )
        }
        if (sideBySide) {
          Row(
              Modifier.fillMaxWidth()
                  .heightIn(min = (screenHeight - 80.dp).coerceAtLeast(0.dp))
                  .testTag(VerifyEmailTags.TwoColumns),
              horizontalArrangement = Arrangement.spacedBy(32.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            VerificationHero(state, onChangeAddress, Modifier.weight(1f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
              VerificationBanner(state.banner)
              VerificationActions(state, onContinue, onResend)
            }
          }
        } else {
          Spacer(Modifier.height((screenHeight * 0.149f).coerceAtMost(120.dp)))
          VerificationHero(state, onChangeAddress)
          Spacer(Modifier.height(20.dp))
          VerificationBanner(state.banner)
          Spacer(Modifier.weight(1f))
          Spacer(Modifier.height(24.dp))
          VerificationActions(state, onContinue, onResend)
          Spacer(Modifier.height(12.dp))
        }
      }
    }
    SnackbarHost(
        snackbar,
        Modifier.align(Alignment.BottomCenter)
            .safeDrawingPadding()
            .padding(24.dp)
            .testTag(VerifyEmailTags.Snackbar),
    )
  }
}

@Composable
private fun VerificationHero(
    state: VerifyEmailUiState,
    onChangeAddress: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier.testTag(VerifyEmailTags.Hero),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
        Modifier.size(80.dp).background(InfoSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Image(painterResource(R.drawable.verify_mail), null, Modifier.size(34.dp))
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.verification_inbox),
        style = MaterialTheme.typography.headlineMedium,
        color = Ink,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(
            if (state.hasSentEmail) R.string.verification_description
            else R.string.verification_send_description
        ),
        style = MaterialTheme.typography.bodyLarge,
        color = Ink2,
        textAlign = TextAlign.Center,
    )
    Text(
        state.email,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
        color = Ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(VerifyEmailTags.Email),
    )
    Spacer(Modifier.height(20.dp))
    TextButton(
        onClick = onChangeAddress,
        enabled = !state.busy,
        modifier = Modifier.testTag(VerifyEmailTags.ChangeAddress),
    ) {
      Text(
          stringResource(R.string.verification_change_address),
          style =
              MaterialTheme.typography.bodyMedium.copy(
                  fontWeight = FontWeight.Bold,
                  textDecoration = TextDecoration.Underline,
              ),
          color = AccentText,
      )
    }
  }
}

@Composable
private fun VerificationActions(
    state: VerifyEmailUiState,
    onContinue: () -> Unit,
    onResend: () -> Unit,
) {
  Column(Modifier.fillMaxWidth().testTag(VerifyEmailTags.Actions)) {
    Button(
        onClick = onContinue,
        enabled = !state.busy && !state.verified,
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Ink,
                disabledContainerColor = Ink,
                disabledContentColor = Color.White,
            ),
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag(VerifyEmailTags.Continue),
    ) {
      if (state.checking) VerificationSpinner(Color.White)
      Text(
          stringResource(
              if (state.checking) R.string.verification_checking else R.string.verification_continue
          ),
          style = MaterialTheme.typography.titleSmall,
      )
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onResend,
        enabled = state.canResend,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag(VerifyEmailTags.Resend),
    ) {
      if (state.sending || state.preparing) VerificationSpinner(Ink)
      val text =
          when {
            state.sending || state.preparing -> stringResource(R.string.verification_sending)
            state.remainingSeconds > 0 ->
                stringResource(
                    R.string.verification_countdown,
                    state.remainingSeconds / 60,
                    state.remainingSeconds % 60,
                )
            else -> stringResource(R.string.verification_resend)
          }
      Text(
          text,
          color = if (state.canResend) Ink else Ink3,
          style = MaterialTheme.typography.titleSmall,
      )
    }
  }
}

@Composable
private fun VerificationSpinner(color: Color) {
  val transition = rememberInfiniteTransition(label = "Verification loading")
  val rotation by
      transition.animateFloat(
          initialValue = 0f,
          targetValue = 360f,
          animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
          label = "Spinner rotation",
      )
  Image(
      painterResource(R.drawable.signup_spinner),
      null,
      modifier =
          Modifier.size(18.dp)
              .rotate(rotation)
              .progressSemantics()
              .testTag(VerifyEmailTags.Loading),
      colorFilter = ColorFilter.tint(color),
  )
  Spacer(Modifier.width(8.dp))
}

@Composable
private fun VerificationBanner(message: VerificationMessage?) {
  val background =
      when (message) {
        VerificationMessage.Throttled -> WarningSoft
        VerificationMessage.Sent -> SuccessSoft
        null -> Surface
        else -> AccentSoft
      }
  val foreground =
      when (message) {
        VerificationMessage.Throttled -> Warning
        VerificationMessage.Sent -> Success
        null -> Ink2
        else -> AccentText
      }
  Row(
      Modifier.fillMaxWidth()
          .background(background, RoundedCornerShape(14.dp))
          .heightIn(min = if (message == null) 81.dp else 62.dp)
          .padding(14.dp)
          .testTag(VerifyEmailTags.Banner)
          .semantics(mergeDescendants = true) {},
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Image(
        painterResource(R.drawable.verify_info),
        null,
        Modifier.size(18.dp),
        colorFilter = ColorFilter.tint(foreground),
    )
    Text(
        stringResource(message?.resource() ?: R.string.verification_spam),
        color = foreground,
        style =
            MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = if (message == null) FontWeight.Normal else FontWeight.SemiBold,
            ),
    )
  }
}

private fun VerificationMessage.resource(): Int =
    when (this) {
      VerificationMessage.Sent -> R.string.verification_sent
      VerificationMessage.AlreadySent -> R.string.verification_already_sent
      VerificationMessage.Throttled -> R.string.verification_throttled
      VerificationMessage.StillUnverified -> R.string.verification_unverified
      VerificationMessage.NetworkError -> R.string.verification_network
      VerificationMessage.UnexpectedError -> R.string.verification_error
      VerificationMessage.NotSignedIn -> R.string.verification_not_signed_in
    }
