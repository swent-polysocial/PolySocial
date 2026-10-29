// Contributors: OpenAI Codex (association onboarding form and Figma review fixes, #125).
package com.polysocial.ui.association

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.association.AssociationType
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Warning
import com.polysocial.ui.theme.WarningSoft

object AssociationSetupTags {
  const val Screen = "association_setup"
  const val Submit = "association_setup_submit"
  const val Back = "association_setup_back"
  const val Error = "association_setup_error"
  const val Initials = "association_setup_initials"
  const val InitialsBox = "association_setup_initials_box"
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
    AssociationSetupInitials(state.form.name)
    AssociationSetupField.entries.forEach { field ->
      AssociationSetupInput(field, state, enabled, onChange, onType)
    }
    AssociationReviewNotice()
    state.error?.let {
      Text(
          stringResource(
              when (it) {
                AssociationSetupFailure.Network -> R.string.association_setup_network
                AssociationSetupFailure.WrongAccount -> R.string.association_setup_wrong_account
                AssociationSetupFailure.Unexpected,
                AssociationSetupFailure.NotSignedIn -> R.string.association_setup_error
              }
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

@Composable
private fun AssociationSetupInitials(name: String) {
  Row(
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        Modifier.size(64.dp)
            .background(com.polysocial.ui.theme.Surface, RoundedCornerShape(18.dp))
            .testTag(AssociationSetupTags.InitialsBox)
            .drawBehind {
              val width = 2.dp.toPx()
              drawRoundRect(
                  color = Border,
                  topLeft = Offset(width / 2, width / 2),
                  size = Size(size.width - width, size.height - width),
                  cornerRadius = CornerRadius(18.dp.toPx()),
                  style =
                      Stroke(
                          width,
                          pathEffect =
                              PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                      ),
              )
            },
        contentAlignment = Alignment.Center,
    ) {
      Text(
          associationInitials(name),
          Modifier.testTag(AssociationSetupTags.Initials),
          color = Accent,
          fontSize = 24.sp,
          fontWeight = FontWeight.ExtraBold,
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
}

@Composable
private fun AssociationSetupInput(
    field: AssociationSetupField,
    state: AssociationSetupUiState,
    enabled: Boolean,
    onChange: (AssociationSetupField, String) -> Unit,
    onType: (AssociationType) -> Unit,
) {
  val error = state.form.error(field).takeIf { field in state.touched }
  val label = stringResource(field.label())
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, color = Ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (field == AssociationSetupField.Type) {
      AssociationSetupTypes(state.form.type, enabled, onType)
    } else {
      val value =
          when (field) {
            AssociationSetupField.Name -> state.form.name
            AssociationSetupField.About -> state.form.about
            AssociationSetupField.Website -> state.form.website
            AssociationSetupField.Type -> state.form.website
          }
      OutlinedTextField(
          value,
          { onChange(field, it) },
          enabled = enabled,
          placeholder = {
            if (field == AssociationSetupField.Website)
                Text(stringResource(R.string.association_website_placeholder))
          },
          isError = error != null,
          singleLine = field != AssociationSetupField.About,
          minLines = if (field == AssociationSetupField.About) 2 else 1,
          modifier =
              Modifier.fillMaxWidth()
                  .defaultMinSize(
                      minHeight = if (field == AssociationSetupField.About) 70.dp else 52.dp
                  )
                  .semantics { contentDescription = label }
                  .testTag(AssociationSetupTags.field(field)),
      )
    }
    if (error != null)
        Text(
            stringResource(error.message()),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("${AssociationSetupTags.field(field)}_error"),
        )
  }
}

@Composable
private fun AssociationSetupTypes(
    selected: AssociationType?,
    enabled: Boolean,
    onType: (AssociationType) -> Unit,
) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    AssociationType.entries.forEach { type ->
      FilterChip(
          selected = selected == type,
          onClick = { onType(type) },
          enabled = enabled,
          label = { Text(stringResource(type.label())) },
          colors =
              FilterChipDefaults.filterChipColors(
                  selectedContainerColor = Ink,
                  selectedLabelColor = Bg,
              ),
          modifier = Modifier.testTag(AssociationSetupTags.type(type)),
      )
    }
  }
}

@Composable
private fun AssociationReviewNotice() {
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
