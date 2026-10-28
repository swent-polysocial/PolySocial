// Contributors: OpenAI Codex (Profile tab, shared logout dialog and remembered action binding,
// #109; matched the logout row and icon to Figma).
// Contributors: OpenAI Codex (privacy entry alongside confirmed logout after rebase, #103).
// Contributors: Claude (the logout dialog shared with the association's Organization tab, #127).
package com.polysocial.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.theme.exact

object ProfileTags {
  const val Logout = "profile_logout"
  const val LogoutIcon = "profile_logout_icon"
  const val Dialog = "logout_dialog"
  const val Confirm = "logout_confirm"
  const val Cancel = "logout_cancel"
  const val Error = "logout_error"
  const val Loading = "logout_loading"
}

@Composable
fun ProfileRoute(
    onLoggedOut: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
    onOpenPrivacy: (() -> Unit)? = null,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val actions = remember(viewModel) { LogoutActions(viewModel) }
  BackHandler(state != LogoutState.Idle, actions.cancel)
  val callback by rememberUpdatedState(onLoggedOut)
  LaunchedEffect(state) { if (state == LogoutState.LoggedOut) callback() }
  ProfileScreen(state, actions.request, actions.cancel, actions.confirm, onOpenPrivacy)
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
    onOpenPrivacy: (() -> Unit)? = null,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag(C.Tag.screen_profile)) {
    if (onOpenPrivacy != null) {
      ProfilePrivacyEntry(onOpenPrivacy, Modifier.fillMaxWidth())
    }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag(ProfileTags.Logout)
                .clickable(
                    enabled = state == LogoutState.Idle,
                    role = Role.Button,
                    onClick = onLogout,
                )
                .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          painterResource(R.drawable.ic_log_out),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(20.dp).testTag(ProfileTags.LogoutIcon),
      )
      Spacer(Modifier.width(14.dp))
      Text(
          stringResource(R.string.profile_log_out),
          style =
              MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold).exact(),
          color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
  LogoutDialog(state, onCancel, onConfirm)
}

/**
 * The logout confirmation for [state] (#109): shown while confirming, clearing the cache or after a
 * failed clear, and nothing otherwise. Shared by the students' Profile and the association's
 * Organization tab (#127).
 */
@Composable
internal fun LogoutDialog(state: LogoutState, onCancel: () -> Unit, onConfirm: () -> Unit) {
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
