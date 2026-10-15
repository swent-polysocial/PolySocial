// Contributors: OpenAI Codex (Profile tab, shared logout dialog and remembered action binding,
// #109).
package com.polysocial.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.resources.C

object ProfileTags {
  const val Logout = "profile_logout"
  const val Dialog = "logout_dialog"
  const val Confirm = "logout_confirm"
  const val Cancel = "logout_cancel"
  const val Error = "logout_error"
  const val Loading = "logout_loading"
}

@Composable
fun ProfileRoute(onLoggedOut: () -> Unit, viewModel: ProfileViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val actions = remember(viewModel) { LogoutActions(viewModel) }
  BackHandler(state != LogoutState.Idle, actions.cancel)
  val callback by rememberUpdatedState(onLoggedOut)
  LaunchedEffect(state) { if (state == LogoutState.LoggedOut) callback() }
  ProfileScreen(state, actions.request, actions.cancel, actions.confirm)
}

/** Keeps the screen and Back action bound to the same ViewModel across recomposition. */
private class LogoutActions(viewModel: ProfileViewModel) {
  val request = viewModel::requestLogout
  val cancel = viewModel::cancelLogout
  val confirm = viewModel::confirmLogout
}

@Composable
fun ProfileScreen(
    state: LogoutState,
    onLogout: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag(C.Tag.screen_profile)) {
    TextButton(
        onClick = onLogout,
        colors =
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.testTag(ProfileTags.Logout),
        enabled = state == LogoutState.Idle,
    ) {
      Text(stringResource(R.string.profile_log_out), style = MaterialTheme.typography.titleSmall)
    }
  }
  if (
      state == LogoutState.Confirming || state == LogoutState.Clearing || state == LogoutState.Error
  ) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(ProfileTags.Dialog),
        containerColor = MaterialTheme.colorScheme.background,
        title = {
          Text(
              stringResource(R.string.profile_log_out_title),
              style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
          )
        },
        text = {
          when (state) {
            LogoutState.Clearing -> CircularProgressIndicator(Modifier.testTag(ProfileTags.Loading))
            LogoutState.Error ->
                Text(
                    stringResource(R.string.profile_log_out_error),
                    Modifier.testTag(ProfileTags.Error),
                )
            else -> Text(stringResource(R.string.profile_log_out_question))
          }
        },
        confirmButton = {
          TextButton(
              colors =
                  ButtonDefaults.textButtonColors(
                      contentColor = MaterialTheme.colorScheme.secondary
                  ),
              onClick = onConfirm,
              enabled = state != LogoutState.Clearing,
              modifier = Modifier.testTag(ProfileTags.Confirm),
          ) {
            Text(
                stringResource(
                    if (state == LogoutState.Error) R.string.retry else R.string.profile_log_out
                )
            )
          }
        },
        dismissButton = {
          if (state == LogoutState.Confirming)
              TextButton(
                  colors =
                      ButtonDefaults.textButtonColors(
                          contentColor = MaterialTheme.colorScheme.secondary
                      ),
                  onClick = onCancel,
                  modifier = Modifier.testTag(ProfileTags.Cancel),
              ) {
                Text(stringResource(R.string.profile_log_out_cancel))
              }
        },
    )
  }
}
