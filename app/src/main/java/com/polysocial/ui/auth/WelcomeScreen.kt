// Contributors: Figma mockup (event/group illustration assets); OpenAI Codex (GPT-6.1 Sol,
// medium; implemented the welcome layout, navigation callbacks and unavailable actions;
// added a compact two-column layout for landscape and reused theme typography after review).
package com.polysocial.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.SuccessSoft
import com.polysocial.ui.theme.Surface
import com.polysocial.ui.theme.Warning
import com.polysocial.ui.theme.WarningSoft
import kotlinx.coroutines.launch

object WelcomeTags {
  const val Screen = "welcome_screen"
  const val Google = "welcome_google"
  const val SignUp = "welcome_sign_up"
  const val LogIn = "welcome_log_in"
  const val Association = "welcome_association"
}

@Composable
fun WelcomeScreen(onSignUp: () -> Unit, onLogIn: () -> Unit) {
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val unavailable = stringResource(R.string.not_available_yet)
  val showUnavailable: () -> Unit = { scope.launch { snackbarHostState.showSnackbar(unavailable) } }
  Box(Modifier.fillMaxSize().background(Bg).testTag(WelcomeTags.Screen)) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
      // Use the available window, including split-screen, rather than device orientation.
      // Wide, short windows put actions beside the hero so they are visible immediately.
      val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight
      val scrollState = rememberScrollState()
      if (sideBySide) {
        Row(
            Modifier.fillMaxWidth()
                .verticalScroll(scrollState)
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
          Column(
              Modifier.weight(1f),
              horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            WelcomeIllustration()
            Spacer(Modifier.height(20.dp))
            WelcomeHeading()
          }
          Column(
              Modifier.weight(1f).widthIn(max = 400.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            WelcomeActions(onSignUp, onLogIn, showUnavailable)
          }
        }
      } else {
        // Keep the portrait reference's spacing and distribute extra height above the hero.
        val extraSpace = (maxHeight - 800.dp).coerceAtLeast(0.dp) / 2
        Column(
            Modifier.fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Spacer(Modifier.height(147.dp + extraSpace))
          WelcomeIllustration()
          Spacer(Modifier.height(86.dp + extraSpace))
          WelcomeHeading()
          Spacer(Modifier.height(70.dp))
          WelcomeActions(onSignUp, onLogIn, showUnavailable)
          Spacer(Modifier.height(10.dp))
        }
      }
    }
    SnackbarHost(
        snackbarHostState,
        Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(20.dp),
    ) {
      Snackbar(it, shape = RoundedCornerShape(14.dp), containerColor = Ink, contentColor = Bg)
    }
  }
}

@Composable
private fun WelcomeHeading() {
  BasicText(
      stringResource(R.string.welcome_title),
      style =
          MaterialTheme.typography.displaySmall.copy(
              color = Ink,
              textAlign = TextAlign.Center,
          ),
  )
  Spacer(Modifier.height(11.dp))
  BasicText(
      stringResource(R.string.welcome_subtitle),
      Modifier.widthIn(max = 269.dp),
      style = MaterialTheme.typography.bodyLarge.copy(color = Ink2, textAlign = TextAlign.Center),
  )
}

@Composable
private fun WelcomeActions(onSignUp: () -> Unit, onLogIn: () -> Unit, showUnavailable: () -> Unit) {
  GoogleSignInButton(WelcomeTags.Google, showUnavailable)
  Spacer(Modifier.height(12.dp))
  Box(
      Modifier.fillMaxWidth()
          .heightIn(min = 52.dp)
          .border(1.dp, Border, MaterialTheme.shapes.large)
          .clip(MaterialTheme.shapes.large)
          .clickable(role = Role.Button, onClick = onSignUp)
          .testTag(WelcomeTags.SignUp)
          .padding(horizontal = 12.dp, vertical = 15.dp),
      contentAlignment = Alignment.Center,
  ) {
    BasicText(
        stringResource(R.string.welcome_sign_up),
        style =
            MaterialTheme.typography.titleSmall.copy(
                color = Ink,
                textAlign = TextAlign.Center,
            ),
    )
  }
  Spacer(Modifier.height(17.dp))
  Row(verticalAlignment = Alignment.CenterVertically) {
    BasicText(
        stringResource(R.string.signup_already_have_account),
        style = MaterialTheme.typography.bodyMedium.copy(color = Ink2),
    )
    WelcomeLink(stringResource(R.string.login_log_in), WelcomeTags.LogIn, onLogIn)
  }
  WelcomeLink(
      stringResource(R.string.welcome_association),
      WelcomeTags.Association,
      showUnavailable,
  )
  Spacer(Modifier.height(4.dp))
  BasicText(
      stringResource(R.string.welcome_privacy),
      Modifier.widthIn(max = 278.dp),
      style =
          MaterialTheme.typography.bodySmall.copy(
              fontWeight = FontWeight.Normal,
              lineHeight = 17.sp,
              color = Ink3,
              textAlign = TextAlign.Center,
          ),
  )
}

@Composable
private fun WelcomeLink(text: String, tag: String, onClick: () -> Unit) {
  Box(
      Modifier.heightIn(min = 34.dp)
          .clickable(role = Role.Button, onClick = onClick)
          .testTag(tag)
          .padding(horizontal = 4.dp, vertical = 8.dp),
      contentAlignment = Alignment.Center,
  ) {
    BasicText(
        text,
        style =
            MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                color = AccentText,
                textDecoration = TextDecoration.Underline,
            ),
    )
  }
}

@Composable
private fun WelcomeIllustration() {
  Box(Modifier.offset(x = 2.dp).size(width = 244.dp, height = 138.dp)) {
    Column(
        Modifier.padding(top = 24.dp)
            .size(width = 228.dp, height = 114.dp)
            .background(Surface, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(AccentSoft, MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center,
        ) {
          Image(painterResource(R.drawable.welcome_music), null, Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
          BasicText(
              stringResource(R.string.welcome_event_time),
              style =
                  MaterialTheme.typography.bodyMedium.copy(
                      fontWeight = FontWeight.Bold,
                      color = Ink,
                  ),
          )
          BasicText(
              stringResource(R.string.welcome_event_venue),
              style =
                  MaterialTheme.typography.bodySmall.copy(
                      fontWeight = FontWeight.Normal,
                      color = Ink2,
                  ),
          )
        }
      }
      Spacer(Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
          WelcomeAvatar(stringResource(R.string.welcome_you), Info, Bg)
          WelcomeAvatar(stringResource(R.string.welcome_avatar_lm), AccentSoft, AccentText)
          WelcomeAvatar(stringResource(R.string.welcome_avatar_tr), WarningSoft, Warning)
          WelcomeAvatar(stringResource(R.string.welcome_avatar_ak), SuccessSoft, Success)
        }
        Spacer(Modifier.width(8.dp))
        BasicText(
            stringResource(R.string.welcome_group_size),
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Success,
                ),
        )
      }
    }
    Box(
        Modifier.align(Alignment.TopEnd)
            .size(56.dp)
            .shadow(
                8.dp,
                RoundedCornerShape(18.dp),
                ambientColor = Accent.copy(alpha = 0.25f),
                spotColor = Accent.copy(alpha = 0.25f),
            )
            .background(Accent, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
      Image(painterResource(R.drawable.welcome_group), null, Modifier.size(28.dp))
    }
  }
}

@Composable
private fun WelcomeAvatar(text: String, background: Color, foreground: Color) {
  Box(
      Modifier.size(32.dp)
          .background(background, RoundedCornerShape(16.dp))
          .border(2.dp, Surface, RoundedCornerShape(16.dp)),
      contentAlignment = Alignment.Center,
  ) {
    BasicText(
        text,
        style =
            MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Bold,
                color = foreground,
            ),
    )
  }
}
