// Contributors: OpenAI Codex (Figma recovery screen and account-neutral confirmation for #164).
package com.polysocial.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.auth.PasswordResetResult
import com.polysocial.ui.login.LoginField
import com.polysocial.ui.theme.*

object PasswordRecoveryTags {
  const val Screen = "password_recovery"
  const val Email = "password_recovery_email"
  const val Submit = "password_recovery_submit"
  const val Back = "password_recovery_back"
  const val BackLink = "password_recovery_back_link"
  const val Error = "password_recovery_error"
  const val Confirmation = "password_recovery_confirmation"
  const val Loading = "password_recovery_loading"
}

@Composable
fun PasswordRecoveryRoute(viewModel: PasswordRecoveryViewModel, onBack: () -> Unit) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  BackHandler(state.loading) {}
  PasswordRecoveryContent(state, viewModel::editEmail, viewModel::submit, onBack)
}

@Composable
fun PasswordRecoveryContent(
    state: PasswordRecoveryState,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
  val confirmed = state.result == PasswordResetResult.Sent
  val error =
      when (state.result) {
        PasswordResetResult.InvalidEmail -> R.string.recovery_invalid_email
        PasswordResetResult.NetworkError -> R.string.recovery_network
        PasswordResetResult.TooManyRequests -> R.string.recovery_too_many
        PasswordResetResult.UnexpectedError -> R.string.recovery_unexpected
        else -> null
      }
  BoxWithConstraints(
      Modifier.fillMaxSize()
          .background(Bg)
          .safeDrawingPadding()
          .imePadding()
          .testTag(PasswordRecoveryTags.Screen)
  ) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(Modifier.fillMaxWidth()) {
        IconButton(
            onClick = onBack,
            enabled = !state.loading,
            modifier =
                Modifier.padding(top = 12.dp)
                    .offset(x = (-12).dp)
                    .size(44.dp)
                    .testTag(PasswordRecoveryTags.Back),
        ) {
          Image(
              painterResource(R.drawable.signup_back),
              stringResource(R.string.recovery_back),
              Modifier.size(22.dp),
          )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(
                if (confirmed) R.string.recovery_submitted_title else R.string.recovery_title
            ),
            style = MaterialTheme.typography.headlineMedium,
            color = Ink,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            if (confirmed) stringResource(R.string.recovery_submitted_message, state.email.trim())
            else stringResource(R.string.recovery_description),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
            color = Ink2,
            modifier =
                if (confirmed) Modifier.testTag(PasswordRecoveryTags.Confirmation) else Modifier,
        )
        if (!confirmed) {
          Spacer(Modifier.height(20.dp))
          Text(
              stringResource(R.string.recovery_email),
              style = MaterialTheme.typography.labelMedium,
              color = Ink2,
          )
          Spacer(Modifier.height(8.dp))
          LoginField(
              state.email,
              onEmailChange,
              50.dp,
              !state.loading,
              state.loading,
              KeyboardType.Email,
              Modifier.testTag(PasswordRecoveryTags.Email),
              isError = error != null,
              errorBorderWidth = 1.dp,
          )
          if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(error),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = AccentText,
                modifier = Modifier.testTag(PasswordRecoveryTags.Error),
            )
          }
        }
      }
      Column(
          Modifier.fillMaxWidth().padding(top = 32.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Button(
            onClick = if (confirmed) onBack else onSubmit,
            enabled = confirmed || state.canSubmit,
            shape = MaterialTheme.shapes.large,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = Ink,
                    contentColor = Bg,
                    disabledContainerColor = Ink,
                    disabledContentColor = Bg,
                ),
            contentPadding = PaddingValues(0.dp),
            modifier =
                Modifier.fillMaxWidth()
                    .height(52.dp)
                    .alpha(if (state.loading) 0.5f else 1f)
                    .testTag(PasswordRecoveryTags.Submit),
        ) {
          Text(
              stringResource(
                  when {
                    confirmed -> R.string.recovery_back
                    state.loading -> R.string.recovery_sending
                    error != null && state.result != PasswordResetResult.InvalidEmail ->
                        R.string.recovery_retry
                    else -> R.string.recovery_send
                  }
              ),
              style = MaterialTheme.typography.titleSmall,
              modifier =
                  if (state.loading) Modifier.testTag(PasswordRecoveryTags.Loading) else Modifier,
          )
        }
        if (!confirmed) {
          Spacer(Modifier.height(16.dp))
          Text(
              stringResource(R.string.recovery_back),
              color = AccentText,
              style =
                  MaterialTheme.typography.bodyMedium.copy(
                      fontWeight = FontWeight.Bold,
                      lineHeight = 18.sp,
                  ),
              textDecoration = TextDecoration.Underline,
              modifier =
                  Modifier.clickable(enabled = !state.loading, onClick = onBack)
                      .testTag(PasswordRecoveryTags.BackLink),
          )
        }
      }
    }
  }
}
