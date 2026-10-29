// Contributors: Claude (Opus 5.5, Anthropic) drafted the Delete account sheet after the Cancel
// event sheet, #207; Mohamed Khellaf (reviewed and revised).
package com.polysocial.ui.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.model.auth.SignInMethod
import com.polysocial.ui.auth.GoogleSignInButton
import com.polysocial.ui.common.FieldLabel
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.login.LoginField
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.exact

object DeleteAccountTags {
  const val Sheet = "delete_account_sheet"
  const val Title = "delete_account_title"
  const val Confirm = "delete_account_confirm"
  const val Cancel = "delete_account_cancel"
  const val Password = "delete_account_password"
  const val Google = "delete_account_google"
  const val Error = "delete_account_error"
  const val Progress = "delete_account_progress"
  const val Retry = "delete_account_retry"
  const val Contact = "delete_account_contact"
  const val ContactMessage = "delete_account_contact_message"
}

/** What the Delete account sheet's buttons do; bound once per ViewModel by the route. */
internal class DeleteAccountActions(
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onSubmitPassword: () -> Unit,
    val onGoogle: () -> Unit,
    val onRetry: () -> Unit,
    val onContact: () -> Unit,
)

/**
 * The Delete account bottom sheet (#207), styled like the "Cancel this event?" sheet (#131)
 * until #205's Figma frames exist: the consequences, then confirming it's you, then progress. While
 * a call runs, or when this device's data couldn't be cleared after the deletion, the sheet can't
 * be dismissed. [contactMessage] explains why the privacy contact's email draft didn't open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeleteAccountSheet(
    state: DeleteAccountUiState,
    contactMessage: PrivacyContactMessage?,
    actions: DeleteAccountActions,
) {
  val locked by rememberUpdatedState(!state.dismissible)
  val sheetState =
      rememberModalBottomSheetState(
          skipPartiallyExpanded = true,
          confirmValueChange = { it != SheetValue.Hidden || !locked },
      )
  ModalBottomSheet(
      onDismissRequest = actions.onCancel,
      sheetState = sheetState,
      shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
      containerColor = Bg,
      scrimColor = Ink.copy(alpha = 0.45f),
      dragHandle = null,
      modifier = Modifier.testTag(DeleteAccountTags.Sheet),
  ) {
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(Modifier.size(width = 36.dp, height = 4.dp).background(Border, RoundedCornerShape(2.dp)))
      Box(
          Modifier.size(44.dp).background(AccentSoft, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Image(
            painterResource(R.drawable.privacy_delete),
            contentDescription = null,
            colorFilter = ColorFilter.tint(AccentText),
            modifier = Modifier.size(22.dp),
        )
      }
      SheetBody(state, contactMessage, actions)
    }
  }
}

@Composable
private fun SheetBody(
    state: DeleteAccountUiState,
    contactMessage: PrivacyContactMessage?,
    actions: DeleteAccountActions,
) {
  when (state.step) {
    DeleteAccountStep.Reauthenticate,
    DeleteAccountStep.Reauthenticating -> ReauthBody(state, actions)
    DeleteAccountStep.NotAllowed -> {
      Title(R.string.delete_account_association_title)
      Body(R.string.delete_account_association_body)
      contactMessage?.let {
        Message(
            when (it) {
              PrivacyContactMessage.NotConfigured -> R.string.privacy_contact_pending
              PrivacyContactMessage.NoEmailApp -> R.string.privacy_contact_no_app
            },
            DeleteAccountTags.ContactMessage,
        )
      }
      PrimaryButton(
          stringResource(R.string.privacy_contact_team),
          enabled = true,
          tag = DeleteAccountTags.Contact,
          onClick = actions.onContact,
      )
      SecondaryButton(R.string.delete_account_close, enabled = true, actions.onCancel)
    }
    DeleteAccountStep.CleanupFailed -> {
      Title(R.string.delete_account_cleanup_title)
      Message(R.string.delete_account_cleanup_error, DeleteAccountTags.Error)
      DestructiveButton(R.string.retry, DeleteAccountTags.Retry, actions.onRetry)
    }
    else -> {
      Title(R.string.delete_account_title)
      Body(R.string.delete_account_body)
      ErrorMessage(state.error)
      when {
        state.busy -> Progress(state.step)
        state.step == DeleteAccountStep.DeleteFailed ->
            DestructiveButton(R.string.retry, DeleteAccountTags.Retry, actions.onRetry)
        else ->
            DestructiveButton(
                R.string.delete_account_confirm,
                DeleteAccountTags.Confirm,
                actions.onConfirm,
            )
      }
      SecondaryButton(R.string.delete_account_cancel, enabled = !state.busy, actions.onCancel)
    }
  }
}

/** Confirming it's you: the password field and/or the Google button, as the account allows. */
@Composable
private fun ReauthBody(state: DeleteAccountUiState, actions: DeleteAccountActions) {
  val password = SignInMethod.PASSWORD in state.methods
  val google = SignInMethod.GOOGLE in state.methods
  Title(R.string.delete_account_reauth_title)
  Body(
      when {
        password && google -> R.string.delete_account_reauth_both
        google -> R.string.delete_account_reauth_google
        else -> R.string.delete_account_reauth_password
      }
  )
  if (password) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      FieldLabel(R.string.login_password_label)
      LoginField(
          value = state.password,
          onValueChange = actions.onPasswordChange,
          height = 48.dp,
          enabled = !state.busy,
          faded = state.busy,
          isError = state.error == DeleteAccountError.WrongPassword,
          keyboardType = KeyboardType.Password,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.testTag(DeleteAccountTags.Password),
      )
    }
  }
  ErrorMessage(state.error)
  if (state.busy) {
    Progress(state.step)
  } else {
    if (password) {
      DestructiveButton(
          R.string.delete_account_confirm,
          DeleteAccountTags.Confirm,
          actions.onSubmitPassword,
          enabled = state.password.isNotEmpty(),
      )
    }
    if (google) GoogleSignInButton(DeleteAccountTags.Google, actions.onGoogle)
  }
  SecondaryButton(R.string.delete_account_cancel, enabled = !state.busy, actions.onCancel)
}

@Composable
private fun Title(@StringRes text: Int) {
  Text(
      text = stringResource(text),
      style =
          MaterialTheme.typography.titleLarge
              .copy(fontSize = 20.sp, lineHeight = TextUnit.Unspecified)
              .exact(),
      fontWeight = FontWeight.ExtraBold,
      color = Ink,
      modifier = Modifier.testTag(DeleteAccountTags.Title).semantics { heading() },
  )
}

@Composable
private fun Body(@StringRes text: Int) {
  Text(
      text = stringResource(text),
      style = MaterialTheme.typography.bodyMedium.exact(),
      color = Ink2,
  )
}

@Composable
private fun Message(@StringRes text: Int, tag: String) {
  Text(
      text = stringResource(text),
      style = MaterialTheme.typography.bodySmall.exact(),
      color = AccentText,
      modifier = Modifier.testTag(tag),
  )
}

@Composable
private fun ErrorMessage(error: DeleteAccountError?) {
  if (error == null) return
  Message(
      when (error) {
        DeleteAccountError.Offline -> R.string.delete_account_offline
        DeleteAccountError.WrongPassword -> R.string.delete_account_wrong_password
        DeleteAccountError.SignInAgain -> R.string.delete_account_sign_in_again
        DeleteAccountError.Network -> R.string.delete_account_network
        DeleteAccountError.Unexpected -> R.string.delete_account_unexpected
      },
      DeleteAccountTags.Error,
  )
}

/** The red button with a spinner, in place of the actions while a call runs. */
@Composable
private fun Progress(step: DeleteAccountStep) {
  PrimaryButton(
      stringResource(
          when (step) {
            DeleteAccountStep.Reauthenticating -> R.string.delete_account_reauthenticating
            DeleteAccountStep.Deleting -> R.string.delete_account_deleting
            else -> R.string.delete_account_clearing
          }
      ),
      enabled = false,
      tag = DeleteAccountTags.Progress,
      loading = true,
      color = Accent,
      onClick = {},
  )
}

@Composable
private fun DestructiveButton(
    @StringRes text: Int,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
  PrimaryButton(stringResource(text), enabled, tag, color = Accent, onClick = onClick)
}

/** The Cancel event sheet's "Keep event" style: a plain pill, faded while it can't be used. */
@Composable
private fun SecondaryButton(@StringRes text: Int, enabled: Boolean, onClick: () -> Unit) {
  Box(
      Modifier.fillMaxWidth()
          .height(47.dp)
          .alpha(if (enabled) 1f else 0.4f)
          .clip(RoundedCornerShape(26.dp))
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .testTag(DeleteAccountTags.Cancel),
      contentAlignment = Alignment.Center,
  ) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, color = Ink)
  }
}
