// Contributors: Claude (Opus 5.5, Anthropic) drafted the Delete account sheet from the Figma
// "Delete account" frames (section 08, #205), #207; Mohamed Khellaf (reviewed and revised).
package com.polysocial.ui.profile

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
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
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Surface
import com.polysocial.ui.theme.exact

object DeleteAccountTags {
  const val Sheet = "delete_account_sheet"
  const val Title = "delete_account_title"
  const val Confirm = "delete_account_confirm"
  const val Ok = "delete_account_ok"
  const val Continue = "delete_account_continue"
  const val Cancel = "delete_account_cancel"
  const val Password = "delete_account_password"
  const val PasswordVisibility = "delete_account_password_visibility"
  const val Google = "delete_account_google"
  const val Banner = "delete_account_banner"
  const val Error = "delete_account_error"
  const val Progress = "delete_account_progress"
  const val Retry = "delete_account_retry"
  const val Contact = "delete_account_contact"
  const val ContactMessage = "delete_account_contact_message"
  const val CleanupDialog = "delete_account_cleanup_dialog"
}

/** What the Delete account sheet's buttons do; bound once per ViewModel by the route. */
internal class DeleteAccountActions(
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onTogglePassword: () -> Unit,
    val onSubmitPassword: () -> Unit,
    val onGoogle: () -> Unit,
    val onRetry: () -> Unit,
    val onContact: () -> Unit,
)

/**
 * The Delete account flow from the Figma "Delete account" frames (section 08, #205): a bottom sheet
 * over Privacy & data for every step, and logout's blocking dialog when this device can't be
 * cleared. While a call runs the sheet can't be dismissed. [contactMessage] explains why the
 * privacy contact's email draft didn't open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeleteAccountSheet(
    state: DeleteAccountUiState,
    contactMessage: PrivacyContactMessage?,
    actions: DeleteAccountActions,
) {
  if (state.step == DeleteAccountStep.CleanupFailed) {
    CleanupDialog(state.deleted, actions.onRetry)
    return
  }
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
      // Figma puts the handle at the start, in the sheet's content.
      dragHandle = null,
      modifier = Modifier.testTag(DeleteAccountTags.Sheet),
  ) {
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(Modifier.size(width = 36.dp, height = 4.dp).background(Border, RoundedCornerShape(2.dp)))
      when (state.step) {
        DeleteAccountStep.Confirm -> ConfirmContent(state, actions)
        DeleteAccountStep.DeleteFailed -> FailedContent(state, actions)
        DeleteAccountStep.NotAllowed -> AssociationContent(contactMessage, actions)
        else -> ReauthContent(state, actions)
      }
    }
  }
}

/** Figma "confirm" and "offline": the consequences, then Delete account (or OK when offline). */
@Composable
private fun ConfirmContent(state: DeleteAccountUiState, actions: DeleteAccountActions) {
  val offline = state.error == DeleteAccountError.Offline
  IconBadge(
      if (offline) R.drawable.ic_delete_account_offline else R.drawable.ic_delete_account_trash
  )
  Title(R.string.delete_account_title)
  Body(stringResource(R.string.delete_account_body))
  if (offline) {
    Message(R.string.delete_account_offline)
    PrimaryButton(
        stringResource(R.string.delete_account_ok),
        enabled = true,
        tag = DeleteAccountTags.Ok,
        onClick = actions.onCancel,
    )
  } else {
    if (state.error != null) Message(R.string.delete_account_unexpected)
    RedButton(R.string.delete_account_confirm, DeleteAccountTags.Confirm, actions.onConfirm)
    SecondaryButton(R.string.delete_account_cancel, enabled = true, actions.onCancel)
  }
}

/**
 * Figma "password", "wrong password", "Google", "Google cancelled", "session expired" and
 * "deleting": confirming it's you with the account's sign-in methods, then the progress. Figma's
 * outlined "Continue with Google" is the app's official Google button ([GoogleSignInButton]), as
 * Welcome and Log in use, to keep Google's unchanged branding.
 */
@Composable
private fun ReauthContent(state: DeleteAccountUiState, actions: DeleteAccountActions) {
  if (state.methods.isEmpty()) {
    // Signed out before the flow could confirm anyone: only the cleanup's progress.
    IconBadge(R.drawable.ic_delete_account_trash)
    Title(R.string.delete_account_title)
    Progress()
    return
  }
  val password = SignInMethod.PASSWORD in state.methods
  val google = SignInMethod.GOOGLE in state.methods
  IconBadge(
      if (google && !password) R.drawable.ic_delete_account_user_check
      else R.drawable.ic_delete_account_lock
  )
  if (state.error == DeleteAccountError.SignInAgain) {
    Banner(
        if (password) R.string.delete_account_confirm_again_password
        else R.string.delete_account_confirm_again_google
    )
  }
  Title(
      if (google && !password) R.string.delete_account_google_title
      else R.string.delete_account_password_title
  )
  Body(
      stringResource(
          when {
            password && google -> R.string.delete_account_both_body
            google -> R.string.delete_account_google_body
            else -> R.string.delete_account_password_body
          },
          state.email,
      )
  )
  if (password) PasswordField(state, actions)
  when (state.error) {
    DeleteAccountError.GoogleCancelled ->
        Message(R.string.delete_account_google_cancelled, color = Ink2)
    DeleteAccountError.Offline -> Message(R.string.delete_account_offline)
    DeleteAccountError.Network -> Message(R.string.delete_account_reauth_failed)
    DeleteAccountError.Unexpected -> Message(R.string.delete_account_unexpected)
    else -> Unit
  }
  if (state.busy) {
    Progress()
  } else {
    if (password) {
      RedButton(
          R.string.delete_account_continue,
          DeleteAccountTags.Continue,
          actions.onSubmitPassword,
      )
    }
    if (google) GoogleSignInButton(DeleteAccountTags.Google, actions.onGoogle)
  }
  SecondaryButton(R.string.delete_account_cancel, enabled = !state.busy, actions.onCancel)
}

@Composable
private fun PasswordField(state: DeleteAccountUiState, actions: DeleteAccountActions) {
  val wrong = state.error == DeleteAccountError.WrongPassword
  Column(
      Modifier.fillMaxWidth().alpha(if (state.busy) 0.5f else 1f),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    FieldLabel(R.string.delete_account_password_placeholder)
    LoginField(
        value = state.password,
        onValueChange = actions.onPasswordChange,
        height = 48.dp,
        enabled = !state.busy,
        faded = false,
        isError = wrong,
        keyboardType = KeyboardType.Password,
        placeholder = stringResource(R.string.delete_account_password_placeholder),
        visualTransformation =
            if (state.passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
        trailing = {
          Box(
              // Android's 48 dp minimum touch target around Figma's 20 dp eye.
              Modifier.size(48.dp)
                  .clickable(enabled = !state.busy, onClick = actions.onTogglePassword)
                  .testTag(DeleteAccountTags.PasswordVisibility),
              contentAlignment = Alignment.Center,
          ) {
            Image(
                painterResource(R.drawable.ic_delete_account_eye),
                stringResource(
                    if (state.passwordVisible) R.string.delete_account_hide_password
                    else R.string.delete_account_show_password
                ),
                Modifier.size(20.dp),
            )
          }
        },
        modifier = Modifier.testTag(DeleteAccountTags.Password),
    )
    if (wrong) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.testTag(DeleteAccountTags.Error),
      ) {
        Image(painterResource(R.drawable.ic_delete_account_error), null, Modifier.size(14.dp))
        Text(
            stringResource(R.string.delete_account_wrong_password),
            style = messageStyle(),
            color = AccentText,
        )
      }
    }
  }
}

/** Figma "couldn't delete": the call failed; Retry calls it again. */
@Composable
private fun FailedContent(state: DeleteAccountUiState, actions: DeleteAccountActions) {
  IconBadge(R.drawable.ic_delete_account_alert)
  Title(R.string.delete_account_failed_title)
  Body(stringResource(R.string.delete_account_failed_body))
  if (state.error == DeleteAccountError.Offline) Message(R.string.delete_account_offline)
  RedButton(R.string.retry, DeleteAccountTags.Retry, actions.onRetry)
  SecondaryButton(R.string.delete_account_cancel, enabled = true, actions.onCancel)
}

/** Figma "association": the PolySocial team deletes association accounts. */
@Composable
private fun AssociationContent(
    contactMessage: PrivacyContactMessage?,
    actions: DeleteAccountActions,
) {
  IconBadge(R.drawable.ic_delete_account_building, background = Surface)
  Title(R.string.delete_account_association_title)
  Body(stringResource(R.string.delete_account_association_body))
  Box(
      // Figma's text link, with Android's 48 dp minimum touch target.
      Modifier.heightIn(min = 48.dp)
          .clickable(role = Role.Button, onClick = actions.onContact)
          .testTag(DeleteAccountTags.Contact),
      contentAlignment = Alignment.CenterStart,
  ) {
    Text(
        stringResource(R.string.privacy_contact_team),
        style =
            MaterialTheme.typography.bodyMedium
                .copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
                .exact(),
        color = AccentText,
    )
  }
  contactMessage?.let {
    Message(
        when (it) {
          PrivacyContactMessage.NotConfigured -> R.string.privacy_contact_pending
          PrivacyContactMessage.NoEmailApp -> R.string.privacy_contact_no_app
        },
        tag = DeleteAccountTags.ContactMessage,
    )
  }
  SecondaryButton(R.string.delete_account_close, enabled = true, actions.onCancel)
}

/**
 * Figma "finishing failed": logout's blocking dialog (#109), with Retry only. [deleted] says
 * whether the server confirmed the deletion; after a lost session the text doesn't claim it.
 */
@Composable
private fun CleanupDialog(deleted: Boolean, onRetry: () -> Unit) {
  AlertDialog(
      onDismissRequest = {},
      modifier = Modifier.testTag(DeleteAccountTags.CleanupDialog),
      containerColor = MaterialTheme.colorScheme.background,
      title = {
        Text(
            stringResource(R.string.delete_account_cleanup_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.testTag(DeleteAccountTags.Title),
        )
      },
      text = {
        Text(
            stringResource(
                if (deleted) R.string.delete_account_cleanup_deleted
                else R.string.delete_account_cleanup_session
            ),
            modifier = Modifier.testTag(DeleteAccountTags.Error),
        )
      },
      confirmButton = {
        TextButton(
            colors =
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
            onClick = onRetry,
            modifier = Modifier.testTag(DeleteAccountTags.Retry),
        ) {
          Text(stringResource(R.string.retry))
        }
      },
  )
}

@Composable
private fun IconBadge(@DrawableRes icon: Int, background: Color = ICON_BACKGROUND) {
  Box(
      Modifier.size(44.dp).background(background, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
  }
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
private fun Body(text: String) {
  Text(text = text, style = MaterialTheme.typography.bodyMedium.exact(), color = Ink2)
}

@Composable
private fun messageStyle(): TextStyle =
    MaterialTheme.typography.bodySmall
        .copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        .exact()

/** Figma's 12 sp semi-bold line under the text: red for a failure, grey for a cancelled picker. */
@Composable
private fun Message(
    @StringRes text: Int,
    color: Color = AccentText,
    tag: String = DeleteAccountTags.Error,
) {
  Text(
      stringResource(text),
      style = messageStyle(),
      color = color,
      modifier = Modifier.testTag(tag),
  )
}

/** Figma "session expired": the grey banner asking to confirm again. */
@Composable
private fun Banner(@StringRes text: Int) {
  Row(
      Modifier.fillMaxWidth()
          .background(Surface, RoundedCornerShape(12.dp))
          .padding(horizontal = 14.dp, vertical = 12.dp)
          .testTag(DeleteAccountTags.Banner),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Image(painterResource(R.drawable.ic_delete_account_info), null, Modifier.size(18.dp))
    Text(stringResource(text), style = MaterialTheme.typography.labelMedium, color = Ink)
  }
}

@Composable
private fun RedButton(@StringRes text: Int, tag: String, onClick: () -> Unit) {
  PrimaryButton(stringResource(text), enabled = true, tag = tag, color = Accent, onClick = onClick)
}

/** Figma "deleting": the red button faded, with a spinner. */
@Composable
private fun Progress() {
  PrimaryButton(
      stringResource(R.string.delete_account_deleting),
      enabled = false,
      tag = DeleteAccountTags.Progress,
      loading = true,
      modifier = Modifier.alpha(0.85f),
      color = Accent,
      onClick = {},
  )
}

/** The Cancel event sheet's "Keep event" style: a plain pill, faded while it can't be used. */
@Composable
private fun SecondaryButton(@StringRes text: Int, enabled: Boolean, onClick: () -> Unit) {
  Box(
      Modifier.fillMaxWidth()
          // Figma draws it 47 dp high; 48 dp is Android's minimum touch target.
          .heightIn(min = 48.dp)
          .alpha(if (enabled) 1f else 0.4f)
          .clip(RoundedCornerShape(26.dp))
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .testTag(DeleteAccountTags.Cancel),
      contentAlignment = Alignment.Center,
  ) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, color = Ink)
  }
}

/** Figma's pale red behind the sheet's icon, as on the Cancel event sheet. */
private val ICON_BACKGROUND = Color(0xFFFCE7E8)
