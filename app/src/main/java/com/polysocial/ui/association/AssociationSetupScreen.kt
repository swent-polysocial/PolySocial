// Contributors: OpenAI Codex (editable association onboarding form, #125).
package com.polysocial.ui.association

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.association.AssociationType
import com.polysocial.ui.theme.Warning
import com.polysocial.ui.theme.WarningSoft

object AssociationSetupTags {
  const val Screen = "association_setup"
  const val Submit = "association_setup_submit"
  const val Back = "association_setup_back"
  const val Error = "association_setup_error"
  const val Initials = "association_setup_initials"
  const val ReviewNotice = "association_setup_review_notice"

  fun field(field: AssociationSetupField) = "association_setup_${field.name}"

  fun type(type: AssociationType) = "association_type_${type.name}"
}

@Composable
fun AssociationSetupRoute(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: AssociationSetupViewModel = hiltViewModel(),
) {
  val actions = remember(viewModel) { AssociationSetupActions(viewModel) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var opened by remember(viewModel) { mutableStateOf(false) }
  LaunchedEffect(viewModel) {
    viewModel.open()
    opened = true
  }
  if (!opened) return
  val saved by rememberUpdatedState(onSaved)
  LaunchedEffect(state.saved) { if (state.saved) saved() }
  BackHandler(state.saving) {}
  AssociationSetupScreen(state, actions.update, actions.selectType, actions.submit, onBack)
}

/** Keeps form actions bound to the current model when its owner changes. */
private class AssociationSetupActions(viewModel: AssociationSetupViewModel) {
  val update = viewModel::update
  val selectType = viewModel::selectType
  val submit = viewModel::submit
}

@Composable
fun AssociationSetupScreen(
    state: AssociationSetupUiState,
    onChange: (AssociationSetupField, String) -> Unit,
    onType: (AssociationType) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
  val enabled = !state.saving && !state.saved
  Column(
      Modifier.fillMaxSize()
          .safeDrawingPadding()
          .imePadding()
          .verticalScroll(rememberScrollState())
          .padding(20.dp)
          .testTag(AssociationSetupTags.Screen),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    IconButton(onBack, enabled = enabled, modifier = Modifier.testTag(AssociationSetupTags.Back)) {
      Icon(
          painterResource(R.drawable.signup_back),
          contentDescription = stringResource(R.string.association_setup_back),
          modifier = Modifier.size(22.dp),
      )
    }
    Text(
        stringResource(R.string.association_setup_title),
        style = MaterialTheme.typography.headlineMedium,
    )
    Text(stringResource(R.string.association_setup_description))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Surface(
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.surfaceVariant,
      ) {
        Text(
            associationInitials(state.form.name),
            Modifier.padding(18.dp).testTag(AssociationSetupTags.Initials),
            style = MaterialTheme.typography.headlineSmall,
        )
      }
      Column {
        Text(
            stringResource(R.string.association_initials),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.association_initials_description),
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    AssociationSetupField.entries.forEach { field ->
      val error = state.form.error(field).takeIf { field in state.touched }
      if (field == AssociationSetupField.Type) {
        Text(stringResource(field.label()))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          AssociationType.entries.forEach { type ->
            FilterChip(
                selected = state.form.type == type,
                onClick = { onType(type) },
                enabled = enabled,
                label = { Text(stringResource(type.label())) },
                modifier = Modifier.testTag(AssociationSetupTags.type(type)),
            )
          }
        }
      } else {
        val value =
            when (field) {
              AssociationSetupField.Name -> state.form.name
              AssociationSetupField.About -> state.form.about
              else -> state.form.website
            }
        OutlinedTextField(
            value,
            { onChange(field, it) },
            enabled = enabled,
            label = { Text(stringResource(field.label())) },
            isError = error != null,
            singleLine = field != AssociationSetupField.About,
            modifier = Modifier.fillMaxWidth().testTag(AssociationSetupTags.field(field)),
        )
      }
      if (error != null)
          Text(
              stringResource(error.message()),
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.testTag("${AssociationSetupTags.field(field)}_error"),
          )
    }
    Surface(
        color = WarningSoft,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(AssociationSetupTags.ReviewNotice),
    ) {
      Row(
          Modifier.padding(12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.Top,
      ) {
        Icon(
            painterResource(R.drawable.ic_shield),
            contentDescription = null,
            tint = Warning,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(R.string.association_review_notice),
            color = Warning,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
      }
    }
    state.error?.let {
      Text(
          stringResource(
              if (it == AssociationSetupFailure.Network) R.string.association_setup_network
              else R.string.association_setup_error
          ),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag(AssociationSetupTags.Error),
      )
    }
    Button(
        onSubmit,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(AssociationSetupTags.Submit),
    ) {
      Text(
          stringResource(
              if (state.saving) R.string.association_saving else R.string.association_submit
          )
      )
    }
  }
}

internal fun AssociationType.label(): Int =
    when (this) {
      AssociationType.ASSOCIATION -> R.string.association_type_association
      AssociationType.COMMISSION -> R.string.association_type_commission
      AssociationType.CLUB -> R.string.association_type_club
      AssociationType.EPFL_UNIT -> R.string.association_type_epfl_unit
    }

private fun AssociationSetupField.label(): Int =
    when (this) {
      AssociationSetupField.Name -> R.string.association_name
      AssociationSetupField.Type -> R.string.association_type
      AssociationSetupField.About -> R.string.association_about
      AssociationSetupField.Website -> R.string.association_website
    }

private fun AssociationSetupError.message(): Int =
    when (this) {
      AssociationSetupError.Required -> R.string.association_required
      AssociationSetupError.TooLong -> R.string.association_too_long
      AssociationSetupError.InvalidWebsite -> R.string.association_website_invalid
    }
