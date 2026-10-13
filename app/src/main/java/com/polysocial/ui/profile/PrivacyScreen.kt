// Contributors: OpenAI Codex (Profile privacy entry, dated offline notice, Figma UI and contact,
// #103).
package com.polysocial.ui.profile

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.polysocial.R
import com.polysocial.model.privacy.AndroidPrivacyContactLauncher
import com.polysocial.resources.C
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3

/** Profile menu entry, independent of account data and the future full Profile tab. */
@Composable
fun ProfilePrivacyEntry(onOpenPrivacy: () -> Unit, modifier: Modifier = Modifier) {
  PrivacyActionRow(
      R.string.privacy_title,
      R.drawable.privacy_shield,
      C.Tag.profile_privacy_entry,
      onOpenPrivacy,
      modifier,
      dividerAtBottom = true,
  )
}

/** Navigation boundary for the read-only privacy screen; no backend or device access is needed. */
@Composable
fun PrivacyRoute(
    onBack: () -> Unit,
    onFullNotice: () -> Unit,
    viewModel: PrivacyViewModel = viewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  PrivacyScreen(
      state,
      onBack,
      onFullNotice,
      viewModel::onUnavailableAction,
      viewModel::onMessageDismissed,
  )
}

/** Notice summary with unavailable actions that do not change consent or collect any new data. */
@Composable
fun PrivacyScreen(
    state: PrivacyUiState,
    onBack: () -> Unit,
    onFullNotice: () -> Unit,
    onUnavailableAction: () -> Unit,
    onMessageDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val unavailable = stringResource(R.string.privacy_unavailable)
  val matchingLabel = stringResource(R.string.privacy_matching)
  val snackbar =
      privacySnackbar(if (state.showUnavailable) unavailable else null, onMessageDismissed)
  Scaffold(
      modifier = modifier.testTag(C.Tag.privacy_screen),
      containerColor = Bg,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      snackbarHost = { SnackbarHost(snackbar, Modifier.testTag(C.Tag.privacy_snackbar)) },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
      PrivacyHeader(R.string.privacy_title, C.Tag.privacy_heading, onBack)
      Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            stringResource(R.string.privacy_summary),
            style = privacyTextStyle(13, 16.4f, FontWeight.Bold),
            color = Ink2,
        )
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth().border(1.dp, Border, RoundedCornerShape(16.dp)).padding(1.dp)
        ) {
          PrivacyDataRow(
              R.string.privacy_profile_title,
              R.string.privacy_profile_body,
              R.drawable.privacy_person,
          )
          HorizontalDivider(color = Border)
          PrivacyDataRow(
              R.string.privacy_location_title,
              R.string.privacy_location_body,
              R.drawable.privacy_pin,
          )
          HorizontalDivider(color = Border)
          PrivacyDataRow(
              R.string.privacy_reminders_title,
              R.string.privacy_reminders_body,
              R.drawable.privacy_message,
          )
          HorizontalDivider(color = Border)
          PrivacyDataRow(
              R.string.privacy_attendance_title,
              R.string.privacy_attendance_body,
              R.drawable.privacy_check,
          )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(
                matchingLabel,
                style = privacyTextStyle(15, 18.9f, FontWeight.SemiBold),
            )
            Text(
                stringResource(R.string.privacy_matching_unavailable),
                style = privacyTextStyle(12, 15.1f),
                color = Ink3,
            )
          }
          Switch(
              checked = false,
              onCheckedChange = { onUnavailableAction() },
              modifier =
                  Modifier.testTag(C.Tag.privacy_matching).semantics {
                    contentDescription = matchingLabel
                  },
          )
        }
        Spacer(Modifier.height(16.dp))
        PrivacyActionRow(
            R.string.privacy_download,
            R.drawable.privacy_download,
            C.Tag.privacy_download,
            onUnavailableAction,
        )
        Spacer(Modifier.height(10.dp))
        PrivacyActionRow(
            R.string.privacy_full_notice,
            R.drawable.privacy_info,
            C.Tag.privacy_full_notice,
            onFullNotice,
            subtitle = R.string.privacy_updated,
        )
        Spacer(Modifier.height(10.dp))
        PrivacyActionRow(
            R.string.privacy_delete,
            R.drawable.privacy_delete,
            C.Tag.privacy_delete,
            onUnavailableAction,
            color = AccentText,
            showChevron = false,
        )
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

/** Supplies the private email action without querying account data or sending a message. */
@Composable
fun FullPrivacyNoticeRoute(onBack: () -> Unit, viewModel: PrivacyViewModel? = null) {
  val context = LocalContext.current.applicationContext
  val email = stringResource(R.string.privacy_contact_email)
  val subject = stringResource(R.string.privacy_contact_subject)
  val factory =
      remember(context, email) {
        viewModelFactory {
          initializer { PrivacyViewModel(email, AndroidPrivacyContactLauncher(context)) }
        }
      }
  val model: PrivacyViewModel = viewModel ?: viewModel(factory = factory)
  val state by model.uiState.collectAsStateWithLifecycle()
  FullPrivacyNoticeScreen(
      onBack,
      state,
      { model.onContactRequested(subject) },
      model::onContactMessageDismissed,
  )
}

/** Full notice is bundled with the app and readable without an account query or a network. */
@Composable
fun FullPrivacyNoticeScreen(
    onBack: () -> Unit,
    state: PrivacyUiState,
    onContact: () -> Unit,
    onMessageDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val message =
      when (state.contactMessage) {
        PrivacyContactMessage.NotConfigured -> stringResource(R.string.privacy_contact_pending)
        PrivacyContactMessage.NoEmailApp -> stringResource(R.string.privacy_contact_no_app)
        null -> null
      }
  val snackbar = privacySnackbar(message, onMessageDismissed)
  Scaffold(
      modifier = modifier.testTag(C.Tag.privacy_notice_screen),
      containerColor = Bg,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      snackbarHost = { SnackbarHost(snackbar, Modifier.testTag(C.Tag.privacy_snackbar)) },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
      PrivacyHeader(R.string.privacy_full_notice, C.Tag.privacy_notice_heading, onBack)
      SelectionContainer {
        Column(Modifier.padding(horizontal = 20.dp)) {
          Text(
              stringResource(R.string.privacy_updated),
              color = Ink3,
              style = privacyTextStyle(12, 16f),
              modifier = Modifier.testTag(C.Tag.privacy_notice_date),
          )
          NoticeSection(R.string.privacy_notice_draft_title, R.string.privacy_notice_draft_body)
          NoticeSection(R.string.privacy_notice_account_title, R.string.privacy_notice_account_body)
          NoticeSection(R.string.privacy_notice_events_title, R.string.privacy_notice_events_body)
          NoticeSection(
              R.string.privacy_notice_location_title,
              R.string.privacy_notice_location_body,
          )
          NoticeSection(
              R.string.privacy_notice_services_title,
              R.string.privacy_notice_services_body,
          )
          NoticeSection(
              R.string.privacy_notice_attendance_title,
              R.string.privacy_notice_attendance_body,
          )
          NoticeSection(R.string.privacy_notice_device_title, R.string.privacy_notice_device_body)
          NoticeSection(
              R.string.privacy_notice_deletion_title,
              R.string.privacy_notice_deletion_body,
          )
        }
      }
      Column(Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        PrivacyActionRow(
            R.string.privacy_contact_team,
            R.drawable.privacy_message,
            C.Tag.privacy_contact,
            onContact,
        )
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

/**
 * Both notices clear feedback after dismissal or navigation, so a later action can be explained.
 */
@Composable
private fun privacySnackbar(message: String?, onMessageDismissed: () -> Unit): SnackbarHostState {
  val snackbar = remember { SnackbarHostState() }
  val dismiss = stringResource(R.string.privacy_dismiss)
  LaunchedEffect(message) {
    if (message != null) {
      try {
        snackbar.showSnackbar(message, actionLabel = dismiss, duration = SnackbarDuration.Short)
      } finally {
        onMessageDismissed()
      }
    }
  }
  return snackbar
}

@Composable
private fun PrivacyHeader(@StringRes title: Int, tag: String, onBack: () -> Unit) {
  Row(
      Modifier.fillMaxWidth().padding(start = 6.dp, top = 10.dp, end = 20.dp).height(48.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onBack, Modifier.testTag(C.Tag.privacy_back)) {
      Image(
          painterResource(R.drawable.privacy_back),
          stringResource(R.string.privacy_back),
          Modifier.size(22.dp),
      )
    }
    Spacer(Modifier.width(2.dp))
    Text(
        stringResource(title),
        style = privacyTextStyle(17, 21.4f, FontWeight.Bold),
        modifier = Modifier.testTag(tag),
    )
  }
}

@Composable
private fun PrivacyDataRow(@StringRes title: Int, @StringRes body: Int, @DrawableRes icon: Int) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
    Image(painterResource(icon), null, Modifier.padding(top = 2.dp).size(18.dp))
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(stringResource(title), style = privacyTextStyle(14, 17.6f, FontWeight.Bold))
      Spacer(Modifier.height(3.dp))
      Text(stringResource(body), color = Ink2, style = privacyTextStyle(13, 18f))
    }
  }
}

@Composable
private fun PrivacyActionRow(
    @StringRes title: Int,
    @DrawableRes icon: Int,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes subtitle: Int? = null,
    color: Color = Ink,
    showChevron: Boolean = true,
    dividerAtBottom: Boolean = false,
) {
  Column(modifier.fillMaxWidth()) {
    if (!dividerAtBottom) HorizontalDivider(color = Border)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 50.dp)
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Image(painterResource(icon), null, Modifier.size(20.dp))
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
        Text(
            stringResource(title),
            color = color,
            style = privacyTextStyle(15, 18.9f, FontWeight.SemiBold),
        )
        if (subtitle != null)
            Text(stringResource(subtitle), color = Ink3, style = privacyTextStyle(12, 15.1f))
      }
      if (showChevron)
          Image(painterResource(R.drawable.privacy_chevron), null, Modifier.size(18.dp))
    }
    if (dividerAtBottom) HorizontalDivider(color = Border)
  }
}

@Composable
private fun NoticeSection(@StringRes title: Int, @StringRes body: Int) {
  Spacer(Modifier.height(20.dp))
  Text(stringResource(title), style = privacyTextStyle(15, 20f, FontWeight.Bold))
  Spacer(Modifier.height(6.dp))
  Text(stringResource(body), color = Ink2, style = privacyTextStyle(14, 21f))
}

@Composable
private fun privacyTextStyle(
    size: Int,
    lineHeight: Float,
    weight: FontWeight = FontWeight.Normal,
): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        fontWeight = weight,
        textMotion = TextMotion.Animated,
        lineHeightStyle =
            LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
