// Contributors: Claude (drafted the Create Event screen and restyled it from the Figma, section 04,
// following the Log in and profile screens).
package com.polysocial.ui.event.create

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.MAX_DESCRIPTION_LENGTH
import com.polysocial.model.event.MAX_TITLE_LENGTH
import com.polysocial.model.event.MIN_CAPACITY
import com.polysocial.resources.C
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.SuccessSoft
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The Create Event screen: the form, then the "Event created" confirmation.
 *
 * The location picker (a Mapbox map, set up in its own task) isn't built yet: [onPickLocation] is
 * where it will open, and it reports the picked place to [CreateEventViewModel.onLocationPick].
 */
@Composable
fun CreateEventScreen(
    onClose: () -> Unit,
    onPickLocation: () -> Unit,
    onViewEvent: (eventId: String) -> Unit,
    onBackToMap: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreateEventViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  when (val status = state.status) {
    is CreateEventStatus.Created ->
        EventCreated(status, onClose, onViewEvent = { onViewEvent(status.eventId) }, onBackToMap)
    else ->
        CreateEventFormContent(
            state = state,
            actions =
                CreateEventFormActions(
                    onTitleChange = viewModel::onTitleChange,
                    onDescriptionChange = viewModel::onDescriptionChange,
                    onCategorySelect = viewModel::onCategorySelect,
                    onDatePick = viewModel::onDatePick,
                    onStartTimePick = viewModel::onStartTimePick,
                    onEndTimePick = viewModel::onEndTimePick,
                    onCapacityChange = viewModel::onCapacityChange,
                    onVisibilityChange = viewModel::onVisibilityChange,
                    onSubmit = viewModel::onSubmit,
                    onClose = onClose,
                    onPickLocation = onPickLocation,
                ),
            modifier = modifier,
        )
  }
}

/** What the Create Event form reports: field edits, submit, close and the location picker. */
data class CreateEventFormActions(
    val onTitleChange: (String) -> Unit,
    val onDescriptionChange: (String) -> Unit,
    val onCategorySelect: (EventCategory) -> Unit,
    val onDatePick: (LocalDate) -> Unit,
    val onStartTimePick: (LocalTime) -> Unit,
    val onEndTimePick: (LocalTime?) -> Unit,
    val onCapacityChange: (String) -> Unit,
    val onVisibilityChange: (isPrivate: Boolean) -> Unit,
    val onSubmit: () -> Unit,
    val onClose: () -> Unit,
    val onPickLocation: () -> Unit,
)

/**
 * The Create Event form, drawn from [state] alone and reporting through [actions], so it can be
 * previewed and tested without a ViewModel.
 *
 * The layout follows the Figma frames (section 04): the gaps are the frame's, and a taller element
 * (a larger font size, an error message) pushes the ones below it down. The form scrolls above the
 * action bar, which stays at the bottom.
 */
@Composable
fun CreateEventFormContent(
    state: CreateEventUiState,
    actions: CreateEventFormActions,
    modifier: Modifier = Modifier,
) {
  val form = state.form
  val editable = state.status != CreateEventStatus.Saving
  var dialog by remember { mutableStateOf<PickerDialog?>(null) }
  val scroll = rememberScrollState()
  val failed = state.status is CreateEventStatus.Failed

  // Figma puts the failure banner at the end of the form, which is off screen after tapping
  // Create: scroll down so the organizer sees why nothing happened.
  LaunchedEffect(failed) { if (failed) scroll.animateScrollTo(scroll.maxValue) }

  // Leaving while saving would hide the result: the write can still succeed after the screen is
  // gone, and the organizer would never see the confirmation.
  BackHandler(enabled = !editable) {}

  Box(modifier.fillMaxSize().background(Bg).testTag(C.Tag.create_event_screen)) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp)) {
        TopBar(R.string.create_event_title, actions.onClose, closeEnabled = editable)
        // While saving, Figma fades the whole form (not the top bar) to 45%.
        Column(Modifier.alpha(if (editable) 1f else SAVING_ALPHA)) {
          Spacer(Modifier.height(4.dp))
          Text(
              stringResource(R.string.create_event_organized_by_you),
              style = MaterialTheme.typography.labelMedium,
              color = Ink2,
              modifier = Modifier.heightIn(min = 24.dp).padding(top = 3.dp),
          )

          Section(R.string.create_event_title_label) {
            InputField(
                value = form.title,
                onValueChange = actions.onTitleChange,
                enabled = editable,
                isError =
                    state.hasError(
                        CreateEventFormError.MISSING_TITLE,
                        CreateEventFormError.TITLE_TOO_LONG,
                    ),
                tag = C.Tag.create_event_title,
                placeholder = stringResource(R.string.create_event_title_placeholder),
            )
            Errors(state, CreateEventFormError.MISSING_TITLE, CreateEventFormError.TITLE_TOO_LONG)
          }

          Section(R.string.create_event_description_label) {
            InputField(
                value = form.description,
                onValueChange = actions.onDescriptionChange,
                enabled = editable,
                isError = state.hasError(CreateEventFormError.DESCRIPTION_TOO_LONG),
                tag = C.Tag.create_event_description,
                minHeight = DESCRIPTION_HEIGHT,
            )
            Errors(state, CreateEventFormError.DESCRIPTION_TOO_LONG)
          }

          Section(R.string.create_event_category_label, labelGap = 8.dp) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              EventCategory.entries.forEach { category ->
                CategoryChip(
                    label = category.label,
                    selected = form.category == category,
                    enabled = editable,
                    tag = C.Tag.createEventCategory(category.name),
                ) {
                  actions.onCategorySelect(category)
                }
              }
            }
            Errors(state, CreateEventFormError.MISSING_CATEGORY)
          }

          Spacer(Modifier.height(SECTION_GAP))
          // Figma's Date, Starts and Ends fields are 128, 88 and 88 dp wide.
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerField(
                label = R.string.create_event_date_label,
                value = form.date?.format(DATE_FORMAT),
                placeholder = R.string.create_event_pick_date,
                enabled = editable,
                isError =
                    state.hasError(
                        CreateEventFormError.MISSING_DATE,
                        CreateEventFormError.START_IN_PAST,
                    ),
                tag = C.Tag.create_event_date,
                modifier = Modifier.weight(128f),
            ) {
              dialog = PickerDialog.DATE
            }
            PickerField(
                label = R.string.create_event_starts_label,
                value = form.startTime?.format(TIME_FORMAT),
                placeholder = R.string.create_event_pick_date,
                enabled = editable,
                isError = false,
                tag = C.Tag.create_event_start,
                modifier = Modifier.weight(88f),
            ) {
              dialog = PickerDialog.START
            }
            Column(Modifier.weight(88f)) {
              PickerField(
                  label = R.string.create_event_ends_label,
                  value = form.endTime?.format(TIME_FORMAT),
                  placeholder = R.string.create_event_ends_optional,
                  enabled = editable,
                  isError = state.hasError(CreateEventFormError.END_SAME_AS_START),
                  tag = C.Tag.create_event_end,
              ) {
                dialog = PickerDialog.END
              }
              if (form.endsNextDay) {
                Text(
                    stringResource(R.string.create_event_ends_next_day),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    modifier =
                        Modifier.padding(top = 6.dp).testTag(C.Tag.create_event_end_next_day),
                )
              }
            }
          }
          Errors(
              state,
              CreateEventFormError.MISSING_DATE,
              CreateEventFormError.START_IN_PAST,
              CreateEventFormError.END_SAME_AS_START,
          )

          Section(R.string.create_event_location_label) {
            LocationField(
                name = form.location?.name,
                enabled = editable,
                isError = state.hasError(CreateEventFormError.MISSING_LOCATION),
                onClick = actions.onPickLocation,
            )
            Errors(state, CreateEventFormError.MISSING_LOCATION)
          }

          Section(R.string.create_event_capacity_label) {
            InputField(
                value = form.capacityText,
                onValueChange = actions.onCapacityChange,
                enabled = editable,
                isError =
                    state.hasError(
                        CreateEventFormError.CAPACITY_NOT_A_NUMBER,
                        CreateEventFormError.CAPACITY_TOO_SMALL,
                    ),
                tag = C.Tag.create_event_capacity,
                keyboardType = KeyboardType.Number,
                suffix = stringResource(R.string.create_event_capacity_suffix),
            )
            Errors(
                state,
                CreateEventFormError.CAPACITY_NOT_A_NUMBER,
                CreateEventFormError.CAPACITY_TOO_SMALL,
            )
          }

          Section(R.string.create_event_visibility_label, labelGap = 8.dp) {
            VisibilityOption(
                title = R.string.create_event_private,
                hint = R.string.create_event_private_hint,
                unselectedIcon = R.drawable.ic_radio_unselected,
                selected = form.isPrivate,
                enabled = editable,
                tag = C.Tag.create_event_private,
            ) {
              actions.onVisibilityChange(true)
            }
            Spacer(Modifier.height(8.dp))
            VisibilityOption(
                title = R.string.create_event_public,
                hint = R.string.create_event_public_hint,
                unselectedIcon = R.drawable.ic_globe,
                selected = !form.isPrivate,
                enabled = editable,
                tag = C.Tag.create_event_public,
            ) {
              actions.onVisibilityChange(false)
            }
          }

          (state.status as? CreateEventStatus.Failed)?.let {
            Spacer(Modifier.height(12.dp))
            FailureBanner(it.reason)
          }
          Spacer(Modifier.height(24.dp))
        }
      }
      ActionBar(state, actions.onSubmit)
    }
  }

  when (dialog) {
    PickerDialog.DATE ->
        DateDialog(form.date, state.today, onDismiss = { dialog = null }) {
          actions.onDatePick(it)
          dialog = null
        }
    PickerDialog.START ->
        TimeDialog(R.string.create_event_starts_label, form.startTime, null, { dialog = null }) {
          actions.onStartTimePick(it)
          dialog = null
        }
    PickerDialog.END ->
        TimeDialog(
            R.string.create_event_ends_label,
            form.endTime,
            onClear =
                form.endTime?.let {
                  {
                    actions.onEndTimePick(null)
                    dialog = null
                  }
                },
            onDismiss = { dialog = null },
        ) {
          actions.onEndTimePick(it)
          dialog = null
        }
    null -> Unit
  }
}

@Composable
private fun TopBar(@StringRes title: Int, onClose: () -> Unit, closeEnabled: Boolean = true) {
  // Close: 22 dp icon at (19, 23), inside a 44 dp touch target; the title starts at x = 56.
  Row(
      Modifier.padding(top = 12.dp).offset(x = (-12).dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        Modifier.size(44.dp)
            .clickable(enabled = closeEnabled, role = Role.Button, onClick = onClose)
            .testTag(C.Tag.create_event_close),
        contentAlignment = Alignment.Center,
    ) {
      Image(painterResource(R.drawable.ic_close), stringResource(R.string.create_event_close))
    }
    Spacer(Modifier.width(4.dp))
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = Ink)
  }
}

/** A field label, the field and its errors, below the previous section. */
@Composable
private fun Section(
    @StringRes label: Int,
    labelGap: Dp = 6.dp,
    content: @Composable () -> Unit,
) {
  Spacer(Modifier.height(SECTION_GAP))
  FieldLabel(label)
  Spacer(Modifier.height(labelGap))
  content()
}

@Composable
private fun FieldLabel(@StringRes text: Int) {
  Text(stringResource(text), style = MaterialTheme.typography.labelMedium, color = Ink2)
}

/** The outline every Figma field shares: white, 12 dp corners, a 2 dp accent border on error. */
private fun Modifier.fieldOutline(isError: Boolean, shape: RoundedCornerShape) =
    background(Bg, shape)
        .border(if (isError) 2.dp else 1.dp, if (isError) Accent else Border, shape)

@Composable
private fun fieldTextStyle(): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp, color = Ink)

@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
    tag: String,
    placeholder: String = "",
    minHeight: Dp = FIELD_HEIGHT,
    keyboardType: KeyboardType = KeyboardType.Text,
    suffix: String? = null,
) {
  val singleLine = minHeight == FIELD_HEIGHT
  val textStyle = fieldTextStyle()
  val shape = RoundedCornerShape(FIELD_RADIUS)
  BasicTextField(
      value = value,
      onValueChange = onValueChange,
      enabled = enabled,
      singleLine = singleLine,
      textStyle = textStyle,
      cursorBrush = SolidColor(Ink),
      keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
      // Figma shows the capacity as "8 people": the unit follows the number once there is one.
      visualTransformation =
          if (suffix == null) VisualTransformation.None else SuffixTransformation(" $suffix"),
      modifier = Modifier.fillMaxWidth().testTag(tag),
      decorationBox = { innerTextField ->
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = minHeight)
                .fieldOutline(isError, shape)
                .padding(horizontal = 14.dp, vertical = if (singleLine) 0.dp else 13.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
          Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
              Text(placeholder, style = textStyle, color = Ink3)
            }
            innerTextField()
          }
        }
      },
  )
}

@Composable
private fun PickerField(
    @StringRes label: Int,
    value: String?,
    @StringRes placeholder: Int,
    enabled: Boolean,
    isError: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(FIELD_RADIUS)
  Column(modifier) {
    FieldLabel(label)
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier.fillMaxWidth()
            .height(FIELD_HEIGHT)
            .clip(shape)
            .fieldOutline(isError, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.CenterStart,
    ) {
      Text(
          value ?: stringResource(placeholder),
          style = fieldTextStyle(),
          color = if (value == null) Ink3 else Ink,
          maxLines = 1,
      )
    }
  }
}

@Composable
private fun LocationField(name: String?, enabled: Boolean, isError: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(FIELD_RADIUS)
  Row(
      Modifier.fillMaxWidth()
          .height(LOCATION_HEIGHT)
          .clip(shape)
          .fieldOutline(isError, shape)
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .padding(start = 14.dp, end = 16.dp)
          .testTag(C.Tag.create_event_location),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // The pin is red once a place is picked, grey on the placeholder (Figma).
    Icon(
        painterResource(R.drawable.ic_pin),
        contentDescription = null,
        tint = if (name == null) Ink3 else AccentText,
    )
    Spacer(Modifier.width(10.dp))
    Text(
        name ?: stringResource(R.string.create_event_location_placeholder),
        style = fieldTextStyle(),
        color = if (name == null) Ink3 else Ink,
        maxLines = 1,
        modifier = Modifier.weight(1f),
    )
    Image(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
  }
}

@Composable
private fun CategoryChip(
    @StringRes label: Int,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
  Box(
      Modifier.height(36.dp)
          .clip(CircleShape)
          .background(if (selected) Ink else Bg)
          .then(if (selected) Modifier else Modifier.border(1.dp, Border, CircleShape))
          .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
          // 14 dp rather than Figma's 16: the rendered font is slightly wider, and 16 would push
          // Party onto the second row, where Figma only has Other.
          .padding(horizontal = 14.dp)
          .testTag(tag),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Bg else Ink,
    )
  }
}

@Composable
private fun VisibilityOption(
    @StringRes title: Int,
    @StringRes hint: Int,
    @DrawableRes unselectedIcon: Int,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
  val shape = RoundedCornerShape(14.dp)
  Row(
      Modifier.fillMaxWidth()
          .heightIn(min = 85.dp)
          .clip(shape)
          .background(Bg)
          .border(if (selected) 2.dp else 1.dp, if (selected) Ink else Border, shape)
          .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
          .padding(horizontal = 14.dp, vertical = 12.dp)
          .testTag(tag),
  ) {
    // Figma: a filled radio on the selected option; the unselected one shows its own icon.
    if (selected) {
      Box(Modifier.size(20.dp).background(Ink, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).background(Bg, CircleShape))
      }
    } else {
      Image(painterResource(unselectedIcon), contentDescription = null)
    }
    Spacer(Modifier.width(12.dp))
    Column {
      Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = Ink)
      Spacer(Modifier.height(3.dp))
      Text(stringResource(hint), style = secondaryTextStyle(), color = Ink2)
    }
  }
}

/** Shows [suffix] after a non-empty value, without making it part of the value. */
private class SuffixTransformation(private val suffix: String) : VisualTransformation {
  override fun filter(text: AnnotatedString): TransformedText {
    if (text.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
    val length = text.length
    return TransformedText(
        text + AnnotatedString(suffix),
        object : OffsetMapping {
          override fun originalToTransformed(offset: Int) = offset

          override fun transformedToOriginal(offset: Int) = offset.coerceAtMost(length)
        },
    )
  }
}

/** Figma's 13 sp regular text with 18 sp lines, used under the visibility options and banners. */
@Composable
private fun secondaryTextStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun ActionBar(state: CreateEventUiState, onSubmit: () -> Unit) {
  val failed = state.status is CreateEventStatus.Failed
  Column(Modifier.fillMaxWidth().background(Bg)) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp)) {
      PrimaryButton(
          text =
              when {
                state.status == CreateEventStatus.Saving -> R.string.create_event_saving
                failed -> R.string.create_event_try_again
                state.form.isPrivate -> R.string.create_event_submit_private
                else -> R.string.create_event_submit_public
              },
          enabled = state.canSubmit,
          loading = state.status == CreateEventStatus.Saving,
          tag = C.Tag.create_event_submit,
          onClick = onSubmit,
      )
    }
  }
}

/** Figma's dark pill button; greyed out when disabled, with a spinner while [loading]. */
@Composable
private fun PrimaryButton(
    @StringRes text: Int,
    enabled: Boolean,
    tag: String,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
  val greyed = !enabled && !loading
  Row(
      Modifier.fillMaxWidth()
          .height(BUTTON_HEIGHT)
          .clip(MaterialTheme.shapes.large)
          .background(if (greyed) Disabled else Ink)
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .testTag(tag),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (loading) {
      CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          color = Bg,
          trackColor = Bg.copy(alpha = 0.3f),
          strokeWidth = 2.dp,
      )
    }
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = if (greyed) Ink3 else Bg,
    )
  }
}

@Composable
private fun FailureBanner(reason: CreateEventStatus.Failed.Reason) {
  Row(
      Modifier.fillMaxWidth()
          .background(AccentSoft, RoundedCornerShape(14.dp))
          .padding(14.dp)
          .testTag(C.Tag.create_event_failure),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Image(
        painterResource(
            when (reason) {
              CreateEventStatus.Failed.Reason.OFFLINE -> R.drawable.ic_wifi_off
              CreateEventStatus.Failed.Reason.UNEXPECTED -> R.drawable.ic_error
            }
        ),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          stringResource(R.string.create_event_offline_title),
          style = secondaryTextStyle().copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
          color = Ink,
      )
      Text(
          stringResource(
              when (reason) {
                CreateEventStatus.Failed.Reason.OFFLINE -> R.string.create_event_offline_body
                CreateEventStatus.Failed.Reason.UNEXPECTED -> R.string.create_event_unexpected_body
              }
          ),
          style = secondaryTextStyle(),
          color = Ink2,
      )
    }
  }
}

/** The visible errors among [errors], each as Figma's red marker and message under its field. */
@Composable
private fun Errors(state: CreateEventUiState, vararg errors: CreateEventFormError) {
  errors
      .filter { it in state.visibleErrors }
      .forEach { error ->
        Row(
            // One semantic node, so the message is read (and found by tests) with its marker.
            Modifier.padding(top = 6.dp)
                .semantics(mergeDescendants = true) {}
                .testTag(C.Tag.createEventError(error.name)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Image(painterResource(R.drawable.ic_error), contentDescription = null)
          Text(error.message(), style = MaterialTheme.typography.bodySmall, color = AccentText)
        }
      }
}

@Composable
private fun CreateEventFormError.message(): String =
    when (this) {
      CreateEventFormError.MISSING_TITLE ->
          stringResource(R.string.create_event_error_missing_title)
      CreateEventFormError.MISSING_CATEGORY ->
          stringResource(R.string.create_event_error_missing_category)
      CreateEventFormError.TITLE_TOO_LONG ->
          pluralStringResource(
              R.plurals.create_event_error_title_too_long,
              MAX_TITLE_LENGTH,
              MAX_TITLE_LENGTH,
          )
      CreateEventFormError.DESCRIPTION_TOO_LONG ->
          pluralStringResource(
              R.plurals.create_event_error_description_too_long,
              MAX_DESCRIPTION_LENGTH,
              MAX_DESCRIPTION_LENGTH,
          )
      CreateEventFormError.MISSING_DATE -> stringResource(R.string.create_event_error_missing_date)
      CreateEventFormError.START_IN_PAST ->
          stringResource(R.string.create_event_error_start_in_past)
      CreateEventFormError.END_SAME_AS_START ->
          stringResource(R.string.create_event_error_end_same_as_start)
      CreateEventFormError.MISSING_LOCATION ->
          stringResource(R.string.create_event_error_missing_location)
      CreateEventFormError.CAPACITY_NOT_A_NUMBER ->
          stringResource(R.string.create_event_error_capacity_not_a_number)
      CreateEventFormError.CAPACITY_TOO_SMALL ->
          pluralStringResource(
              R.plurals.create_event_error_capacity_too_small,
              MIN_CAPACITY,
              MIN_CAPACITY,
          )
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(
    selected: LocalDate?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
  val firstDay = today.toEpochDay() * MILLIS_PER_DAY
  val pickerState =
      rememberDatePickerState(
          initialSelectedDateMillis = selected?.let { it.toEpochDay() * MILLIS_PER_DAY },
          selectableDates =
              object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= firstDay

                override fun isSelectableYear(year: Int) = year >= today.year
              },
      )
  DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(
            onClick = {
              pickerState.selectedDateMillis?.let {
                onPick(LocalDate.ofEpochDay(it / MILLIS_PER_DAY))
              } ?: onDismiss()
            },
            modifier = Modifier.testTag(C.Tag.create_event_dialog_confirm),
        ) {
          Text(stringResource(R.string.create_event_ok))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss, Modifier.testTag(C.Tag.create_event_dialog_dismiss)) {
          Text(stringResource(R.string.create_event_cancel))
        }
      },
  ) {
    DatePicker(pickerState)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    @StringRes title: Int,
    selected: LocalTime?,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
    onPick: (LocalTime) -> Unit,
) {
  val pickerState =
      rememberTimePickerState(
          initialHour = selected?.hour ?: DEFAULT_HOUR,
          initialMinute = selected?.minute ?: 0,
          is24Hour = true,
      )
  TimePickerDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(title)) },
      confirmButton = {
        TextButton(
            onClick = { onPick(LocalTime.of(pickerState.hour, pickerState.minute)) },
            modifier = Modifier.testTag(C.Tag.create_event_dialog_confirm),
        ) {
          Text(stringResource(R.string.create_event_ok))
        }
      },
      dismissButton = {
        Row {
          if (onClear != null) {
            TextButton(onClick = onClear, Modifier.testTag(C.Tag.create_event_dialog_clear)) {
              Text(stringResource(R.string.create_event_clear_end_time))
            }
          }
          TextButton(onClick = onDismiss, Modifier.testTag(C.Tag.create_event_dialog_dismiss)) {
            Text(stringResource(R.string.create_event_cancel))
          }
        }
      },
  ) {
    TimePicker(pickerState)
  }
}

@Composable
private fun EventCreated(
    status: CreateEventStatus.Created,
    onClose: () -> Unit,
    onViewEvent: () -> Unit,
    onBackToMap: () -> Unit,
) {
  Box(Modifier.fillMaxSize().background(Bg).testTag(C.Tag.event_created_screen)) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp, 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(Modifier.fillMaxWidth()) { TopBar(R.string.create_event_title, onClose) }
      // Figma centers the message between the top bar and the buttons.
      Spacer(Modifier.weight(1f))
      Box(Modifier.size(80.dp).background(SuccessSoft, CircleShape), Alignment.Center) {
        Image(painterResource(R.drawable.ic_check), contentDescription = null)
      }
      Spacer(Modifier.height(16.dp))
      Text(
          stringResource(R.string.event_created_title),
          style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
          color = Ink,
      )
      Spacer(Modifier.height(17.dp))
      Text(
          stringResource(
              if (status.isPrivate) R.string.event_created_private_message
              else R.string.event_created_public_message,
              status.title,
          ),
          style = MaterialTheme.typography.bodyLarge,
          color = Ink2,
          textAlign = TextAlign.Center,
          modifier = Modifier.widthIn(max = 287.dp),
      )
      Spacer(Modifier.weight(1f))
      PrimaryButton(
          text = R.string.event_created_view_event,
          enabled = true,
          tag = C.Tag.event_created_view_event,
          onClick = onViewEvent,
      )
      Spacer(Modifier.height(12.dp))
      Box(
          Modifier.fillMaxWidth()
              .height(BUTTON_HEIGHT)
              .clip(MaterialTheme.shapes.large)
              .background(Bg)
              .border(1.dp, Border, MaterialTheme.shapes.large)
              .clickable(role = Role.Button, onClick = onBackToMap)
              .testTag(C.Tag.event_created_back_to_map),
          contentAlignment = Alignment.Center,
      ) {
        Text(
            stringResource(R.string.event_created_back_to_map),
            style = MaterialTheme.typography.titleSmall,
            color = Ink,
        )
      }
      Spacer(Modifier.height(28.dp))
    }
  }
}

private fun CreateEventUiState.hasError(vararg errors: CreateEventFormError) = errors.any {
  it in visibleErrors
}

private val EventCategory.label: Int
  get() =
      when (this) {
        EventCategory.STUDY -> R.string.create_event_category_study
        EventCategory.SPORTS -> R.string.create_event_category_sports
        EventCategory.CULTURE -> R.string.create_event_category_culture
        EventCategory.PARTY -> R.string.create_event_category_party
        EventCategory.OTHER -> R.string.create_event_category_other
      }

private enum class PickerDialog {
  DATE,
  START,
  END,
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private const val MILLIS_PER_DAY = 86_400_000L
private const val DEFAULT_HOUR = 18

// Sizes from the Figma frames (section 04).
private val SECTION_GAP = 14.dp
private val FIELD_HEIGHT = 50.dp
private val DESCRIPTION_HEIGHT = 92.dp
private val LOCATION_HEIGHT = 48.dp
private val FIELD_RADIUS = 12.dp
private val BUTTON_HEIGHT = 52.dp
private const val SAVING_ALPHA = 0.45f
