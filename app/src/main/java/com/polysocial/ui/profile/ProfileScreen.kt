// Contributors: OpenAI Codex (6.2 Sol, high; Friends entry beside My friend code, #360).
// Contributors: OpenAI Codex (Profile tab, shared logout dialog and remembered action binding,
// #109; matched the logout row and icon to Figma).
// Contributors: OpenAI Codex (privacy entry alongside confirmed logout after rebase, #103).
// Contributors: Claude (the logout dialog shared with the association's Organization tab, #127;
// the activity-scoped logout confirmation shared with the app-start error, #208).
// Contributors: Claude Opus 5.5 (the My friend code row, #339); Konstantinos Perdikeas (directed,
// reviewed and owns it).
// Contributors: Claude (Sonnet 5.5, Anthropic) (the My profile row, first on the Profile tab,
// #495).
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
import com.polysocial.ui.friend.FriendsTags
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
    onOpenFriends: (() -> Unit)? = null,
    onOpenMyProfile: (() -> Unit)? = null,
    onOpenFriendCode: (() -> Unit)? = null,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val actions = remember(viewModel) { LogoutActions(viewModel) }
  BackHandler(state != LogoutState.Idle, actions.cancel)
  val callback by rememberUpdatedState(onLoggedOut)
  LaunchedEffect(state) { if (state == LogoutState.LoggedOut) callback() }
  ProfileScreen(
      state,
      actions.request,
      actions.cancel,
      actions.confirm,
      onOpenPrivacy,
      onOpenFriends,
      onOpenMyProfile,
      onOpenFriendCode,
  )
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
    onOpenFriends: (() -> Unit)? = null,
    // Before onOpenFriendCode, which callers pass as a trailing lambda.
    onOpenMyProfile: (() -> Unit)? = null,
    onOpenFriendCode: (() -> Unit)? = null,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag(C.Tag.screen_profile)) {
    // The association's Organization tab supplies none of the student-only actions.
    if (onOpenMyProfile != null) {
      PrivacyActionRow(
          R.string.my_profile_title,
          R.drawable.ic_tab_profile,
          C.Tag.profile_my_profile_entry,
          onOpenMyProfile,
          Modifier.fillMaxWidth(),
          dividerAtBottom = true,
      )
    }
    if (onOpenFriendCode != null) {
      PrivacyActionRow(
          R.string.friend_code_title,
          R.drawable.ic_qr,
          C.Tag.profile_friend_code_entry,
          onOpenFriendCode,
          Modifier.fillMaxWidth(),
          dividerAtBottom = true,
      )
    }
    if (onOpenFriends != null) {
      PrivacyActionRow(
          R.string.friends_title,
          R.drawable.ic_friends_profile_people,
          FriendsTags.ENTRY,
          onOpenFriends,
          Modifier.fillMaxWidth(),
          dividerAtBottom = true,
      )
    }
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
 * The logout of an app whose [viewModel] belongs to the activity, which outlives the session: the
 * confirmation [LogoutDialog] (cache cleared, then signed out, #109), then, once logged out, the
 * ViewModel is reset for the next account and [onLoggedOut] is called. Shared by the association's
 * pending app (#127) and the app-start error (#208); [ProfileViewModel.requestLogout] opens it.
 */
@Composable
fun ActivityLogout(viewModel: ProfileViewModel, onLoggedOut: () -> Unit) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val latestOnLoggedOut by rememberUpdatedState(onLoggedOut)
  LaunchedEffect(state) {
    if (state == LogoutState.LoggedOut) {
      viewModel.reset()
      latestOnLoggedOut()
    }
  }
  LogoutDialog(state, viewModel::cancelLogout, viewModel::confirmLogout)
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
