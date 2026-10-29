// Contributors: OpenAI Codex (Organization identity and shared privacy/logout controls, #140).
package com.polysocial.ui.association

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.profile.*
import com.polysocial.ui.theme.*
import kotlinx.coroutines.launch

object OrganizationTags {
  const val Screen = "screen_organization"
  const val Initials = "organization_initials"
  const val Verified = "organization_verified"
  const val Members = "organization_members"
  const val Qr = "organization_qr"
  const val Reports = "organization_reports"
}

/** Own identity for the approved shell. #127 supplies the pending experience. */
@Composable
fun OrganizationRoute(
    onLoggedOut: () -> Unit,
    onOpenPrivacy: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
  val organization by viewModel.organization.collectAsStateWithLifecycle()
  val logout by viewModel.uiState.collectAsStateWithLifecycle()
  val actions = remember(viewModel) { OrganizationActions(viewModel) }
  var opened by remember(viewModel) { mutableStateOf(false) }
  LaunchedEffect(viewModel) {
    viewModel.loadOrganization()
    opened = true
  }
  val done by rememberUpdatedState(onLoggedOut)
  LaunchedEffect(logout) { if (opened && logout == LogoutState.LoggedOut) done() }
  BackHandler(logout != LogoutState.Idle, actions.cancel)
  if (opened)
      OrganizationScreen(
          organization,
          logout,
          actions.retry,
          actions.request,
          actions.cancel,
          actions.confirm,
          onOpenPrivacy,
      )
}

private class OrganizationActions(model: ProfileViewModel) {
  val retry = model::loadOrganization
  val request = model::requestLogout
  val cancel = model::cancelLogout
  val confirm = model::confirmLogout
}

@Composable
fun OrganizationScreen(
    state: OrganizationUiState,
    logout: LogoutState,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val unavailable = stringResource(R.string.not_available_yet)
  val showUnavailable = {
    snackbar.currentSnackbarData?.dismiss()
    scope.launch { snackbar.showSnackbar(unavailable) }
    Unit
  }
  Box(Modifier.fillMaxSize().testTag(OrganizationTags.Screen)) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
      when (state) {
        OrganizationUiState.Loading ->
            Box(Modifier.height(300.dp)) {
              LoadingState(stringResource(R.string.organization_loading))
            }
        OrganizationUiState.Error ->
            Box(Modifier.height(300.dp)) {
              ErrorState(
                  title = stringResource(R.string.organization_error_title),
                  message = stringResource(R.string.organization_error_message),
                  onRetry = onRetry,
              )
            }
        is OrganizationUiState.Ready -> {
          val association = state.association
          Column(
              Modifier.fillMaxWidth().padding(20.dp),
              verticalArrangement = Arrangement.spacedBy(18.dp),
          ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              Surface(shape = RoundedCornerShape(18.dp), color = WarningSoft) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                  Text(
                      associationInitials(association.name),
                      Modifier.testTag(OrganizationTags.Initials),
                      style = MaterialTheme.typography.titleLarge,
                      color = Warning,
                      fontWeight = FontWeight.ExtraBold,
                  )
                }
              }
              Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    association.name,
                    style =
                        MaterialTheme.typography.titleLarge.copy(
                            fontSize = 19.sp,
                            lineHeight = 24.sp,
                        ),
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    stringResource(association.type.label()),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                    color = Ink2,
                )
                Surface(shape = RoundedCornerShape(12.dp), color = InfoSoft) {
                  Row(
                      Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    Icon(
                        painterResource(R.drawable.ic_check_small),
                        null,
                        Modifier.size(12.dp),
                        tint = Info,
                    )
                    Text(
                        stringResource(R.string.organization_verified),
                        Modifier.testTag(OrganizationTags.Verified),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Info,
                    )
                  }
                }
              }
            }
            Text(association.about, style = MaterialTheme.typography.bodyMedium, color = Ink2)
            association.website?.let {
              Text(it, style = MaterialTheme.typography.bodySmall, color = Ink2)
            }
          }
          OrganizationEntry(
              R.string.organization_members,
              R.drawable.ic_organization_members,
              OrganizationTags.Members,
              logout == LogoutState.Idle,
              showUnavailable,
          )
          OrganizationEntry(
              R.string.organization_qr,
              R.drawable.ic_organization_qr,
              OrganizationTags.Qr,
              logout == LogoutState.Idle,
              showUnavailable,
          )
          OrganizationEntry(
              R.string.organization_reports,
              R.drawable.ic_organization_reports,
              OrganizationTags.Reports,
              logout == LogoutState.Idle,
              showUnavailable,
          )
        }
      }
      Box(Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
        ProfileScreen(logout, onLogout, onCancel, onConfirm, onOpenPrivacy)
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
  }
}

@Composable
private fun OrganizationEntry(
    label: Int,
    icon: Int,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
  Column(Modifier.padding(horizontal = 20.dp)) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 53.dp)
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(painterResource(icon), null, Modifier.size(22.dp), tint = Ink)
      Spacer(Modifier.width(14.dp))
      Text(
          stringResource(label),
          Modifier.weight(1f),
          style = MaterialTheme.typography.titleSmall,
          color = Ink,
      )
      Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp), tint = Ink3)
    }
    HorizontalDivider(color = Border)
  }
}
