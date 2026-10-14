// Contributors: Figma mockup (Google error-screen layout); OpenAI Codex (responsive Compose
// translation and recovery actions; removed collision UI for #107).
package com.polysocial.ui.auth

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.model.auth.GoogleAccount
import com.polysocial.model.auth.GoogleSignInResult
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Surface

object GoogleAuthTags {
  const val Error = "google_auth_error"
  const val Back = "google_auth_back"
  const val Account = "google_auth_account"
  const val Hero = "google_auth_hero"
  const val Guidance = "google_auth_guidance"
  const val TwoColumns = "google_auth_two_columns"
  const val Retry = "google_auth_retry"
  const val Title = "google_auth_title"
  const val Message = "google_auth_message"
  const val Footnote = "google_auth_footnote"
}

/** Uses the available window, including split-screen, following Verify Email's layout rule. */
@Composable
fun GoogleAuthErrorScreen(result: GoogleSignInResult, onBack: () -> Unit, onRetry: () -> Unit) {
  val account =
      when (result) {
        is GoogleSignInResult.NotEpfl -> result.account
        else -> null
      }
  val title =
      when (result) {
        is GoogleSignInResult.NotEpfl -> R.string.google_not_epfl_title
        else -> R.string.google_sign_in_error_title
      }
  val message =
      when (result) {
        is GoogleSignInResult.NotEpfl -> R.string.google_not_epfl_message
        GoogleSignInResult.NetworkError -> R.string.google_network_error
        else -> R.string.google_unexpected_error
      }
  val footnote =
      when (result) {
        is GoogleSignInResult.NotEpfl -> R.string.google_not_epfl_footnote
        else -> null
      }
  BoxWithConstraints(
      Modifier.fillMaxSize().background(Bg).safeDrawingPadding().testTag(GoogleAuthTags.Error)
  ) {
    val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight
    val screenHeight = maxHeight
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = screenHeight)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
      IconButton(
          onClick = onBack,
          modifier = Modifier.size(48.dp).offset(x = (-18).dp).testTag(GoogleAuthTags.Back),
      ) {
        Image(
            painterResource(R.drawable.signup_back),
            stringResource(R.string.signup_back),
            Modifier.size(22.dp),
        )
      }
      val hero: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.testTag(GoogleAuthTags.Hero)) {
          Box(
              Modifier.size(if (sideBySide) 56.dp else 72.dp).background(AccentSoft, CircleShape),
              contentAlignment = Alignment.Center,
          ) {
            Image(painterResource(R.drawable.google_auth_alert), null, Modifier.size(32.dp))
          }
          Spacer(Modifier.height(if (sideBySide) 8.dp else 20.dp))
          Text(
              stringResource(title),
              modifier =
                  (if (result is GoogleSignInResult.NotEpfl) Modifier.widthIn(max = 225.dp)
                      else Modifier)
                      .testTag(GoogleAuthTags.Title),
              style =
                  MaterialTheme.typography.headlineMedium.copy(
                      textMotion = TextMotion.Animated,
                      lineHeightStyle =
                          LineHeightStyle(
                              LineHeightStyle.Alignment.Center,
                              LineHeightStyle.Trim.None,
                          ),
                  ),
              color = Ink,
          )
          if (account != null) {
            Spacer(Modifier.height(if (sideBySide) 8.dp else 20.dp))
            SelectedAccount(account)
          }
        }
      }
      val guidance: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.testTag(GoogleAuthTags.Guidance)) {
          val guidanceText = stringResource(message)
          val annotatedGuidance = buildAnnotatedString {
            append(guidanceText)
            if (result is GoogleSignInResult.NotEpfl) {
              val start = guidanceText.indexOf("@epfl.ch")
              if (start >= 0)
                  addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Ink), start, start + 8)
            }
          }
          Text(
              annotatedGuidance,
              modifier = Modifier.testTag(GoogleAuthTags.Message),
              style =
                  MaterialTheme.typography.bodyLarge.copy(
                      lineHeight = 23.sp,
                      textMotion = TextMotion.Animated,
                      lineHeightStyle =
                          LineHeightStyle(
                              LineHeightStyle.Alignment.Center,
                              LineHeightStyle.Trim.None,
                          ),
                  ),
              color = Ink2,
          )
          if (footnote != null) {
            Spacer(Modifier.height(19.dp))
            Text(
                stringResource(footnote),
                modifier = Modifier.testTag(GoogleAuthTags.Footnote),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Normal,
                        lineHeight = 19.sp,
                        textMotion = TextMotion.Animated,
                    ),
                color = Ink3,
            )
          } else {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().testTag(GoogleAuthTags.Retry),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Bg),
            ) {
              Text(stringResource(R.string.google_try_again))
            }
          }
        }
      }
      if (sideBySide) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = (screenHeight - 80.dp).coerceAtLeast(0.dp))
                .testTag(GoogleAuthTags.TwoColumns),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          hero(Modifier.weight(1f))
          guidance(Modifier.weight(1f))
        }
      } else {
        Spacer(Modifier.height((screenHeight * 0.10625f).coerceAtMost(85.dp)))
        hero(Modifier.fillMaxWidth())
        Spacer(Modifier.height(22.dp))
        guidance(Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

@Composable
private fun SelectedAccount(account: GoogleAccount) {
  Row(
      Modifier.fillMaxWidth()
          .heightIn(min = 68.dp)
          .background(Surface, RoundedCornerShape(16.dp))
          .padding(16.dp, 14.dp)
          .testTag(GoogleAuthTags.Account),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        Modifier.size(40.dp).background(Color(0xFFEFEDE8), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      val initials =
          account.displayName
              .orEmpty()
              .trim()
              .split(Regex("\\s+"))
              .filter { it.isNotEmpty() }
              .take(2)
              .map { it.first() }
              .joinToString("")
              .uppercase()
      Text(
          initials,
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
          color = Ink2,
      )
    }
    Column(Modifier.weight(1f)) {
      if (!account.displayName.isNullOrBlank())
          Text(
              account.displayName,
              style =
                  MaterialTheme.typography.titleSmall.copy(
                      fontSize = 15.sp,
                      fontWeight = FontWeight.Bold,
                      textMotion = TextMotion.Animated,
                  ),
              color = Ink,
          )
      Text(
          account.email,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
          color = Ink2,
      )
    }
  }
}
