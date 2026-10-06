// Contributors: Claude (Log in screen for #32, built from the Figma "First proposal revamped"
// frames); Claude Opus 5.5 (eye icon label and scrolling on short screens, after review).
package com.polysocial.ui.login

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.polysocial.R
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.InfoSoft
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags of the Log in screen. */
object LoginScreenTestTags {
  const val SCREEN = "login_screen"
  const val BACK = "login_back"
  const val GOOGLE = "login_google"
  const val EMAIL = "login_email"
  const val PASSWORD = "login_password"
  const val PASSWORD_VISIBILITY = "login_password_visibility"
  const val ERROR = "login_error"
  const val FORGOT_PASSWORD = "login_forgot_password"
  const val UNVERIFIED_BANNER = "login_unverified_banner"
  const val LOG_IN = "login_log_in"
  const val CREATE_ACCOUNT = "login_create_account"
}

/** How long the "Taking you to the verification screen…" banner shows before redirecting. */
const val UNVERIFIED_REDIRECT_DELAY_MS = 1500L

/**
 * Log in screen. Reports where to go next through the callbacks, so the app's navigation (#41)
 * decides how to get there.
 */
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    onNeedsVerification: () -> Unit,
    onCreateAccount: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  LaunchedEffect(state.status) {
    when (state.status) {
      LoginStatus.LoggedIn -> onLoggedIn()
      LoginStatus.Unverified -> {
        delay(UNVERIFIED_REDIRECT_DELAY_MS)
        onNeedsVerification()
      }
      else -> Unit
    }
  }
  LoginContent(
      state = state,
      onBack = onBack,
      onEmailChange = viewModel::onEmailChange,
      onPasswordChange = viewModel::onPasswordChange,
      onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
      onLogIn = viewModel::onLogIn,
      onCreateAccount = onCreateAccount,
  )
}

/** Draws the Log in screen for [state]; all measurements come from the Figma frames. */
@Composable
fun LoginContent(
    state: LoginUiState,
    onBack: () -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onLogIn: () -> Unit,
    onCreateAccount: () -> Unit,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val notAvailable = stringResource(R.string.not_available_yet)
  val showNotAvailable: () -> Unit = {
    scope.launch { snackbarHostState.showSnackbar(notAvailable) }
  }
  val busy = state.status == LoginStatus.Loading || state.status == LoginStatus.LoggedIn
  val typography = MaterialTheme.typography

  Box(Modifier.fillMaxSize().background(Bg).testTag(LoginScreenTestTags.SCREEN)) {
    // When the screen is too short (small phone, large text, keyboard open), the form scrolls so
    // Log in stays reachable. Otherwise the bottom group sits at the bottom, as in Figma.
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      Column(
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .heightIn(min = maxHeight)
              .padding(horizontal = 20.dp),
          verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(Modifier.fillMaxWidth()) {
          // Back arrow: 22 dp icon at (19, 23), inside a 44 dp touch target.
          Box(
              Modifier.padding(top = 12.dp)
                  .offset(x = (-12).dp)
                  .size(44.dp)
                  .clickable(onClick = onBack)
                  .testTag(LoginScreenTestTags.BACK),
              contentAlignment = Alignment.Center,
          ) {
            Image(painterResource(R.drawable.ic_back), stringResource(R.string.login_back))
          }
          Spacer(Modifier.height(11.5.dp))
          Text(stringResource(R.string.login_title), style = typography.headlineMedium, color = Ink)
          Spacer(Modifier.height(6.dp))
          Text(stringResource(R.string.login_subtitle), style = typography.bodyLarge, color = Ink2)
          Spacer(Modifier.height(20.5.dp))
          GoogleButton(onClick = showNotAvailable)
          Spacer(Modifier.height(19.5.dp))
          OrWithEmailDivider()
          Spacer(Modifier.height(20.dp))

          FieldLabel(R.string.login_email_label)
          Spacer(Modifier.height(6.dp))
          LoginField(
              value = state.email,
              onValueChange = onEmailChange,
              height = 50.dp,
              enabled = !busy,
              placeholder = stringResource(R.string.login_email_placeholder),
              keyboardType = KeyboardType.Email,
              modifier = Modifier.testTag(LoginScreenTestTags.EMAIL),
          )
          Spacer(Modifier.height(13.5.dp))
          FieldLabel(R.string.login_password_label)
          Spacer(Modifier.height(6.dp))
          LoginField(
              value = state.password,
              onValueChange = onPasswordChange,
              height = 48.dp,
              enabled = !busy,
              isError = state.status == LoginStatus.WrongCredentials,
              keyboardType = KeyboardType.Password,
              visualTransformation =
                  if (state.passwordVisible) VisualTransformation.None
                  else PasswordVisualTransformation(),
              trailing = {
                Box(
                    Modifier.size(44.dp)
                        .clickable(onClick = onTogglePasswordVisibility)
                        .testTag(LoginScreenTestTags.PASSWORD_VISIBILITY),
                    contentAlignment = Alignment.Center,
                ) {
                  Image(
                      painterResource(R.drawable.ic_eye),
                      stringResource(
                          if (state.passwordVisible) R.string.login_hide_password
                          else R.string.login_show_password
                      ),
                  )
                }
              },
              modifier = Modifier.testTag(LoginScreenTestTags.PASSWORD),
          )

          // 48 dp between the password field and "Forgot password?"; the error message sits inside
          // it.
          Box(Modifier.fillMaxWidth().height(48.dp)) {
            val error =
                when (state.status) {
                  LoginStatus.WrongCredentials -> R.string.login_wrong_credentials
                  LoginStatus.CantConnect -> R.string.login_cant_connect
                  else -> null
                }
            if (error != null) ErrorMessage(error, Modifier.padding(top = 5.5.dp))
          }
          Text(
              stringResource(R.string.login_forgot_password),
              style = typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
              color = AccentText,
              textDecoration = TextDecoration.Underline,
              modifier =
                  Modifier.align(Alignment.End)
                      .clickable(onClick = showNotAvailable)
                      .testTag(LoginScreenTestTags.FORGOT_PASSWORD),
          )
          if (state.status == LoginStatus.Unverified) {
            Spacer(Modifier.height(23.4.dp))
            UnverifiedBanner()
          }
        }
        Column(Modifier.fillMaxWidth()) {
          // Keeps a gap above Log in when the form scrolls; hidden in the free space otherwise.
          Spacer(Modifier.height(24.dp))
          LogInButton(state = state, onClick = onLogIn)
          Spacer(Modifier.height(17.dp))
          Row(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
          ) {
            Text(
                stringResource(R.string.login_new_here),
                style = typography.bodyMedium,
                color = Ink2,
            )
            Text(
                stringResource(R.string.login_create_account),
                style = typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = AccentText,
                textDecoration = TextDecoration.Underline,
                modifier =
                    Modifier.clickable(onClick = onCreateAccount)
                        .testTag(LoginScreenTestTags.CREATE_ACCOUNT),
            )
          }
          Spacer(Modifier.height(41.4.dp))
        }
      }
    }
    SnackbarHost(
        snackbarHostState,
        Modifier.align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
      Snackbar(
          it,
          shape = RoundedCornerShape(14.dp),
          containerColor = Ink,
          contentColor = Bg,
      )
    }
  }
}

@Composable
private fun GoogleButton(onClick: () -> Unit) {
  Row(
      Modifier.fillMaxWidth()
          .height(52.dp)
          .background(Ink, MaterialTheme.shapes.large)
          .clickable(onClick = onClick)
          .testTag(LoginScreenTestTags.GOOGLE),
      horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(22.dp).background(Bg, CircleShape), contentAlignment = Alignment.Center) {
      Text(
          stringResource(R.string.login_google_initial),
          style =
              MaterialTheme.typography.titleSmall.copy(
                  fontWeight = FontWeight.ExtraBold,
                  fontSize = 13.sp,
              ),
          color = Ink,
      )
    }
    Text(
        stringResource(R.string.login_continue_with_google),
        style = MaterialTheme.typography.titleSmall,
        color = Bg,
    )
  }
}

@Composable
private fun OrWithEmailDivider() {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.weight(1f).height(1.dp).background(Border))
    Text(
        stringResource(R.string.login_or_with_email),
        style = MaterialTheme.typography.bodySmall,
        color = Ink3,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    Box(Modifier.weight(1f).height(1.dp).background(Border))
  }
}

@Composable
private fun FieldLabel(@StringRes text: Int) {
  Text(stringResource(text), style = MaterialTheme.typography.labelMedium, color = Ink2)
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    height: Dp,
    enabled: Boolean,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    placeholder: String = "",
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
  // While loading, Figma fades the field (border and text) to 45%; the eye icon stays opaque.
  val fade = if (enabled) 1f else 0.45f
  val textStyle =
      MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp, color = Ink.copy(alpha = fade))
  val shape = MaterialTheme.shapes.small
  BasicTextField(
      value = value,
      onValueChange = onValueChange,
      enabled = enabled,
      singleLine = true,
      textStyle = textStyle,
      cursorBrush = SolidColor(Ink),
      visualTransformation = visualTransformation,
      keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
      modifier = modifier.fillMaxWidth(),
      decorationBox = { innerTextField ->
        Row(
            Modifier.fillMaxWidth()
                .height(height)
                .background(Bg, shape)
                .border(
                    if (isError) 2.dp else 1.dp,
                    (if (isError) Accent else Border).copy(alpha = fade),
                    shape,
                )
                .padding(start = 14.dp, end = if (trailing != null) 2.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty())
                Text(placeholder, style = textStyle, color = Ink3)
            innerTextField()
          }
          trailing?.invoke()
        }
      },
  )
}

@Composable
private fun ErrorMessage(@StringRes text: Int, modifier: Modifier = Modifier) {
  Row(
      modifier.testTag(LoginScreenTestTags.ERROR),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Image(painterResource(R.drawable.ic_error), contentDescription = null)
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = AccentText)
  }
}

@Composable
private fun UnverifiedBanner() {
  Row(
      Modifier.fillMaxWidth()
          .background(InfoSoft, RoundedCornerShape(14.dp))
          .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
          .testTag(LoginScreenTestTags.UNVERIFIED_BANNER),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Image(
        painterResource(R.drawable.ic_info),
        contentDescription = null,
        modifier = Modifier.padding(top = 1.dp),
    )
    Text(
        stringResource(R.string.login_unverified),
        style = MaterialTheme.typography.labelMedium.copy(lineHeight = 19.sp),
        color = Info,
    )
  }
}

@Composable
private fun LogInButton(state: LoginUiState, onClick: () -> Unit) {
  val progress =
      when (state.status) {
        LoginStatus.Loading,
        LoginStatus.LoggedIn -> R.string.login_logging_in
        LoginStatus.Unverified -> R.string.login_redirecting
        else -> null
      }
  val disabled = progress == null && !state.canSubmit
  val shape = MaterialTheme.shapes.large
  Row(
      Modifier.fillMaxWidth()
          .height(52.dp)
          .background(if (disabled) Disabled else Bg, shape)
          .then(if (disabled) Modifier else Modifier.border(1.dp, Border, shape))
          .clickable(enabled = state.canSubmit, onClick = onClick)
          .testTag(LoginScreenTestTags.LOG_IN),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (progress != null) {
      CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          color = Ink,
          trackColor = Ink.copy(alpha = 0.3f),
          strokeWidth = 2.dp,
      )
    }
    Text(
        stringResource(progress ?: R.string.login_log_in),
        style = MaterialTheme.typography.titleSmall,
        color = if (disabled) Ink3 else Ink,
    )
  }
}
