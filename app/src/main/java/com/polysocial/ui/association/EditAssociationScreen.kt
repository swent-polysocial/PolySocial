// Contributors: Claude (the pending association's Edit details screen, from the Figma
// "Association · pending · Edit details" frames, #127; TalkBack labels and errors, keyboard
// padding, after a review, #127).
package com.polysocial.ui.association

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationType
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.PlusJakartaSans
import com.polysocial.ui.theme.exact

/** Test tags of Edit details (#127). */
object EditAssociationTags {
  const val SCREEN = "edit_association"
  const val BACK = "edit_association_back"
  const val NAME = "edit_association_name"
  const val ABOUT = "edit_association_about"
  const val WEBSITE = "edit_association_website"
  const val SAVE = "edit_association_save"
  const val FAILURE = "edit_association_failure"

  fun type(type: AssociationType) = "edit_association_type_${type.name}"

  fun error(error: AssociationFormError) = "edit_association_error_${error.name}"
}

private fun jakarta(weight: FontWeight, size: Int, lineHeight: Float) =
    TextStyle(
            fontFamily = PlusJakartaSans,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
        )
        .exact()

private val TITLE_STYLE = jakarta(FontWeight.ExtraBold, 26, 32f).copy(letterSpacing = (-0.26).sp)
private val LABEL_STYLE = jakarta(FontWeight.SemiBold, 13, 16.4f)
private val FIELD_STYLE = jakarta(FontWeight.Normal, 15, 20f)
private val MULTILINE_FIELD_STYLE = jakarta(FontWeight.Normal, 15, 22f)
private val CHIP_STYLE = jakarta(FontWeight.SemiBold, 14, 17.6f)
private val ERROR_STYLE = jakarta(FontWeight.SemiBold, 12, 15f)
private val BUTTON_STYLE = jakarta(FontWeight.Bold, 15, 18.9f)
private val BANNER_TITLE_STYLE = jakarta(FontWeight.Bold, 14, 18f)
private val BANNER_BODY_STYLE = jakarta(FontWeight.Normal, 13, 18f)

/**
 * Edit details for [association], with [viewModel]'s form. Once saved, [onSaved] gets the new
 * details; [onBack] leaves without saving (not while saving, so the result isn't lost).
 */
@Composable
fun EditAssociationRoute(
    association: Association,
    viewModel: EditAssociationViewModel,
    onBack: () -> Unit,
    onSaved: (Association) -> Unit,
) {
  LaunchedEffect(viewModel, association) { viewModel.start(association) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val latestOnSaved by rememberUpdatedState(onSaved)
  val saved = state.status as? EditAssociationStatus.Saved
  LaunchedEffect(saved) { saved?.let { latestOnSaved(it.association) } }
  val busy = state.status == EditAssociationStatus.Saving || saved != null
  BackHandler(busy) {}
  EditAssociationScreen(
      state = state,
      onBack = onBack,
      onNameChange = viewModel::onNameChange,
      onTypeChange = viewModel::onTypeChange,
      onAboutChange = viewModel::onAboutChange,
      onWebsiteChange = viewModel::onWebsiteChange,
      onSave = viewModel::save,
  )
}

/** Figma "Association · pending · Edit details", with its errors and offline frames. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditAssociationScreen(
    state: EditAssociationUiState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onTypeChange: (AssociationType) -> Unit,
    onAboutChange: (String) -> Unit,
    onWebsiteChange: (String) -> Unit,
    onSave: () -> Unit,
) {
  val form = state.form ?: return
  val editable =
      state.status !is EditAssociationStatus.Saving && state.status !is EditAssociationStatus.Saved
  val errors = state.visibleErrors
  // imePadding: the keyboard shrinks the form, so the field being typed in stays visible.
  Column(Modifier.fillMaxSize().background(Bg).imePadding().testTag(EditAssociationTags.SCREEN)) {
    Column(
        Modifier.weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
      // Back: Figma's 22 dp arrow at (19, 23), in a 44 dp touch target.
      Box(
          Modifier.padding(top = 12.dp)
              .offset(x = (-12).dp)
              .size(44.dp)
              .clickable(enabled = editable, role = Role.Button, onClick = onBack)
              .testTag(EditAssociationTags.BACK),
          contentAlignment = Alignment.Center,
      ) {
        Image(painterResource(R.drawable.ic_back), stringResource(R.string.association_edit_back))
      }
      Spacer(Modifier.height(8.dp))
      Text(
          stringResource(R.string.association_edit_details),
          style = TITLE_STYLE,
          color = Ink,
          modifier = Modifier.semantics { heading() },
      )
      Label(R.string.association_edit_name, top = 16.dp)
      Field(
          label = stringResource(R.string.association_edit_name),
          errorMessage = AssociationFormError.NAME_MISSING.takeIf { it in errors }?.message(),
          value = form.name,
          onValueChange = onNameChange,
          placeholder = R.string.association_edit_name_placeholder,
          enabled = editable,
          tag = EditAssociationTags.NAME,
          capitalization = KeyboardCapitalization.Words,
      )
      FieldError(AssociationFormError.NAME_MISSING, errors)
      Label(R.string.association_edit_type)
      Spacer(Modifier.height(1.dp))
      FlowRow(
          modifier = Modifier.selectableGroup(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        AssociationType.entries.forEach { type ->
          TypeChip(type, selected = type == form.type, enabled = editable) { onTypeChange(type) }
        }
      }
      Label(R.string.association_edit_about)
      Field(
          label = stringResource(R.string.association_edit_about),
          errorMessage = AssociationFormError.ABOUT_MISSING.takeIf { it in errors }?.message(),
          value = form.about,
          onValueChange = onAboutChange,
          placeholder = R.string.association_edit_about_placeholder,
          enabled = editable,
          tag = EditAssociationTags.ABOUT,
          minHeight = 70.dp,
          capitalization = KeyboardCapitalization.Sentences,
      )
      FieldError(AssociationFormError.ABOUT_MISSING, errors)
      Label(R.string.association_edit_website)
      Field(
          label = stringResource(R.string.association_edit_website),
          errorMessage = AssociationFormError.WEBSITE_INVALID.takeIf { it in errors }?.message(),
          value = form.website,
          onValueChange = onWebsiteChange,
          placeholder = R.string.association_edit_website_placeholder,
          enabled = editable,
          tag = EditAssociationTags.WEBSITE,
          minHeight = 50.dp,
          keyboardType = KeyboardType.Uri,
      )
      FieldError(AssociationFormError.WEBSITE_INVALID, errors)
      Spacer(Modifier.height(16.dp))
    }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
      (state.status as? EditAssociationStatus.Failed)?.let {
        FailureBanner(it.offline)
        Spacer(Modifier.height(16.dp))
      }
      SaveButton(state, onSave)
    }
  }
}

/**
 * A field label, 13.5 dp under the field above it (14.5 under an error) and 6 dp over its own
 * field, as in Figma.
 */
@Composable
private fun Label(@StringRes text: Int, top: Dp = 13.5.dp) {
  Spacer(Modifier.height(top))
  Text(stringResource(text), style = LABEL_STYLE, color = Ink2)
  Spacer(Modifier.height(6.dp))
}

/**
 * Figma's field: white with 12 dp corners and a 1 dp border, 2 dp and dark while typing in it, or 2
 * dp and red on error. A field taller than [FIELD_HEIGHT] grows with its text.
 */
@Composable
private fun Field(
    label: String,
    errorMessage: String?,
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes placeholder: Int,
    enabled: Boolean,
    tag: String,
    minHeight: Dp = FIELD_HEIGHT,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
  val isError = errorMessage != null
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val singleLine = minHeight <= FIELD_HEIGHT
  val style = if (singleLine) FIELD_STYLE else MULTILINE_FIELD_STYLE
  val shape = RoundedCornerShape(12.dp)
  BasicTextField(
      value = value,
      onValueChange = onValueChange,
      enabled = enabled,
      singleLine = singleLine,
      textStyle = style.copy(color = Ink),
      cursorBrush = SolidColor(Ink),
      keyboardOptions =
          KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType),
      interactionSource = interaction,
      // TalkBack reads the field's label (the placeholder only shows while it is empty) and its
      // error, like Create Event's fields.
      modifier =
          Modifier.fillMaxWidth()
              .semantics {
                contentDescription = label
                if (errorMessage != null) error(errorMessage)
              }
              .testTag(tag),
      decorationBox = { inner ->
        Box(
            Modifier.fillMaxWidth()
                .heightIn(min = minHeight)
                .background(Bg, shape)
                .border(
                    width = if (isError || focused) 2.dp else 1.dp,
                    color =
                        when {
                          isError -> Accent
                          focused -> Ink
                          else -> Border
                        },
                    shape = shape,
                )
                .padding(horizontal = 15.dp, vertical = if (singleLine) 0.dp else 13.dp),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
          if (value.isEmpty()) Text(stringResource(placeholder), style = style, color = Ink3)
          inner()
        }
      },
  )
}

/** [error]'s red marker and message under its field, when it shows. */
@Composable
private fun FieldError(error: AssociationFormError, errors: Set<AssociationFormError>) {
  if (error !in errors) return
  Row(
      // One node, so the message is read (and found by tests) with its marker.
      Modifier.padding(top = 6.dp, bottom = 1.dp)
          .semantics(mergeDescendants = true) {}
          .testTag(EditAssociationTags.error(error)),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Image(painterResource(R.drawable.ic_error), contentDescription = null)
    Text(
        error.message(),
        style = ERROR_STYLE,
        color = AccentText,
    )
  }
}

/** One Type chip: dark when selected, white with a border otherwise. */
@Composable
private fun TypeChip(
    type: AssociationType,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
  Box(
      Modifier.heightIn(min = 36.dp)
          .clip(CircleShape)
          .background(if (selected) Ink else Bg)
          .then(if (selected) Modifier else Modifier.border(1.dp, Border, CircleShape))
          .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
          .padding(horizontal = 14.dp, vertical = 8.dp)
          .testTag(EditAssociationTags.type(type)),
      contentAlignment = Alignment.Center,
  ) {
    Text(stringResource(type.label), style = CHIP_STYLE, color = if (selected) Bg else Ink)
  }
}

/** Figma's red banner over the button when a save fails, offline or not. */
@Composable
private fun FailureBanner(offline: Boolean) {
  Row(
      Modifier.fillMaxWidth()
          .background(AccentSoft, RoundedCornerShape(14.dp))
          .padding(14.dp)
          .semantics(mergeDescendants = true) {}
          .testTag(EditAssociationTags.FAILURE),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Image(
        painterResource(if (offline) R.drawable.ic_wifi_off else R.drawable.ic_error),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          stringResource(R.string.association_edit_failed_title),
          style = BANNER_TITLE_STYLE,
          color = Ink,
      )
      Text(
          stringResource(
              if (offline) R.string.association_edit_offline_body
              else R.string.association_edit_unexpected_body
          ),
          style = BANNER_BODY_STYLE,
          color = Ink2,
      )
    }
  }
}

/** "Save changes", "Try again" after a failure, greyed out while an error shows. */
@Composable
private fun SaveButton(state: EditAssociationUiState, onSave: () -> Unit) {
  val saving = state.status == EditAssociationStatus.Saving
  val greyed = !state.canSave && !saving
  Row(
      Modifier.fillMaxWidth()
          .heightIn(min = 52.dp)
          .clip(RoundedCornerShape(26.dp))
          .background(if (greyed) Disabled else Ink)
          .clickable(enabled = state.canSave, role = Role.Button, onClick = onSave)
          .padding(vertical = 8.dp)
          .testTag(EditAssociationTags.SAVE),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (saving) {
      CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          color = Bg,
          trackColor = Bg.copy(alpha = 0.3f),
          strokeWidth = 2.dp,
      )
    }
    Text(
        stringResource(
            when (state.status) {
              EditAssociationStatus.Saving -> R.string.association_edit_saving
              is EditAssociationStatus.Failed -> R.string.association_edit_try_again
              else -> R.string.association_edit_save
            }
        ),
        style = BUTTON_STYLE,
        color = if (greyed) Ink3 else Bg,
    )
  }
}

/** The message Figma shows under a field with this error. */
@Composable
private fun AssociationFormError.message(): String =
    stringResource(
        when (this) {
          AssociationFormError.NAME_MISSING -> R.string.association_edit_error_name
          AssociationFormError.ABOUT_MISSING -> R.string.association_edit_error_about
          AssociationFormError.WEBSITE_INVALID -> R.string.association_edit_error_website
        }
    )

/** Figma's single-line field height. */
private val FIELD_HEIGHT = 52.dp
