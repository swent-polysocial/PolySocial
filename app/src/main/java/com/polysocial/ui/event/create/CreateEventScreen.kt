// Contributors: Claude (drafted the Create Event screen from the Figma, section 04).
package com.polysocial.ui.event.create

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.MAX_DESCRIPTION_LENGTH
import com.polysocial.model.event.MAX_TITLE_LENGTH
import com.polysocial.model.event.MIN_CAPACITY
import com.polysocial.resources.C
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
    else -> CreateEventFormContent(state, viewModel, onClose, onPickLocation, modifier)
  }
}

@Composable
private fun CreateEventFormContent(
    state: CreateEventUiState,
    viewModel: CreateEventViewModel,
    onClose: () -> Unit,
    onPickLocation: () -> Unit,
    modifier: Modifier,
) {
  val form = state.form
  val editable = state.status != CreateEventStatus.Saving
  var dialog by remember { mutableStateOf<PickerDialog?>(null) }

  Scaffold(
      modifier = modifier.testTag(C.Tag.create_event_screen),
      topBar = { TopRow(R.string.create_event_title, onClose) },
      bottomBar = { ActionBar(state, viewModel::onSubmit) },
  ) { padding ->
    Column(
        modifier =
            Modifier.padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
          stringResource(R.string.create_event_organized_by_you),
          style = MaterialTheme.typography.labelLarge,
      )

      Labeled(R.string.create_event_title_label) {
        OutlinedTextField(
            value = form.title,
            onValueChange = viewModel::onTitleChange,
            placeholder = { Text(stringResource(R.string.create_event_title_placeholder)) },
            singleLine = true,
            enabled = editable,
            isError =
                state.hasError(
                    CreateEventFormError.MISSING_TITLE,
                    CreateEventFormError.TITLE_TOO_LONG,
                ),
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.create_event_title),
        )
        Errors(state, CreateEventFormError.MISSING_TITLE, CreateEventFormError.TITLE_TOO_LONG)
      }

      Labeled(R.string.create_event_description_label) {
        OutlinedTextField(
            value = form.description,
            onValueChange = viewModel::onDescriptionChange,
            minLines = 3,
            enabled = editable,
            isError = state.hasError(CreateEventFormError.DESCRIPTION_TOO_LONG),
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.create_event_description),
        )
        Errors(state, CreateEventFormError.DESCRIPTION_TOO_LONG)
      }

      Labeled(R.string.create_event_category_label) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          EventCategory.entries.forEach { category ->
            FilterChip(
                selected = form.category == category,
                onClick = { viewModel.onCategorySelect(category) },
                label = { Text(stringResource(category.label)) },
                enabled = editable,
                modifier = Modifier.testTag(C.Tag.createEventCategory(category.name)),
            )
          }
        }
        Errors(state, CreateEventFormError.MISSING_CATEGORY)
      }

      Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          PickerField(
              label = R.string.create_event_date_label,
              value = form.date?.format(DATE_FORMAT),
              enabled = editable,
              tag = C.Tag.create_event_date,
              modifier = Modifier.weight(1.4f),
          ) {
            dialog = PickerDialog.DATE
          }
          PickerField(
              label = R.string.create_event_starts_label,
              value = form.startTime?.format(TIME_FORMAT),
              enabled = editable,
              tag = C.Tag.create_event_start,
              modifier = Modifier.weight(1f),
          ) {
            dialog = PickerDialog.START
          }
          Column(Modifier.weight(1f)) {
            PickerField(
                label = R.string.create_event_ends_label,
                value = form.endTime?.format(TIME_FORMAT),
                placeholder = R.string.create_event_ends_optional,
                enabled = editable,
                tag = C.Tag.create_event_end,
            ) {
              dialog = PickerDialog.END
            }
            if (form.endsNextDay) {
              Text(
                  stringResource(R.string.create_event_ends_next_day),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(top = 4.dp).testTag(C.Tag.create_event_end_next_day),
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
      }

      Labeled(R.string.create_event_location_label) {
        OutlinedCard(
            onClick = onPickLocation,
            enabled = editable,
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.create_event_location),
        ) {
          Text(
              form.location?.name ?: stringResource(R.string.create_event_location_placeholder),
              color =
                  if (form.location == null) MaterialTheme.colorScheme.onSurfaceVariant
                  else MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(16.dp),
          )
        }
        Errors(state, CreateEventFormError.MISSING_LOCATION)
      }

      Labeled(R.string.create_event_capacity_label) {
        OutlinedTextField(
            value = form.capacityText,
            onValueChange = viewModel::onCapacityChange,
            suffix = { Text(stringResource(R.string.create_event_capacity_suffix)) },
            singleLine = true,
            enabled = editable,
            isError =
                state.hasError(
                    CreateEventFormError.CAPACITY_NOT_A_NUMBER,
                    CreateEventFormError.CAPACITY_TOO_SMALL,
                ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.create_event_capacity),
        )
        Errors(
            state,
            CreateEventFormError.CAPACITY_NOT_A_NUMBER,
            CreateEventFormError.CAPACITY_TOO_SMALL,
        )
      }

      Labeled(R.string.create_event_visibility_label) {
        VisibilityOption(
            title = R.string.create_event_private,
            hint = R.string.create_event_private_hint,
            selected = form.isPrivate,
            enabled = editable,
            tag = C.Tag.create_event_private,
        ) {
          viewModel.onVisibilityChange(true)
        }
        Spacer(Modifier.height(8.dp))
        VisibilityOption(
            title = R.string.create_event_public,
            hint = R.string.create_event_public_hint,
            selected = !form.isPrivate,
            enabled = editable,
            tag = C.Tag.create_event_public,
        ) {
          viewModel.onVisibilityChange(false)
        }
      }
    }
  }

  when (dialog) {
    PickerDialog.DATE ->
        DateDialog(form.date, state.today, onDismiss = { dialog = null }) {
          viewModel.onDatePick(it)
          dialog = null
        }
    PickerDialog.START ->
        TimeDialog(R.string.create_event_starts_label, form.startTime, null, { dialog = null }) {
          viewModel.onStartTimePick(it)
          dialog = null
        }
    PickerDialog.END ->
        TimeDialog(
            R.string.create_event_ends_label,
            form.endTime,
            onClear =
                form.endTime?.let {
                  {
                    viewModel.onEndTimePick(null)
                    dialog = null
                  }
                },
            onDismiss = { dialog = null },
        ) {
          viewModel.onEndTimePick(it)
          dialog = null
        }
    null -> Unit
  }
}

@Composable
private fun TopRow(@StringRes title: Int, onClose: () -> Unit) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
  ) {
    IconButton(onClick = onClose, modifier = Modifier.testTag(C.Tag.create_event_close)) {
      Icon(
          painterResource(R.drawable.ic_close),
          contentDescription = stringResource(R.string.create_event_close),
      )
    }
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
  }
}

@Composable
private fun ActionBar(state: CreateEventUiState, onSubmit: () -> Unit) {
  Surface(tonalElevation = 2.dp) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
      val failure = state.status as? CreateEventStatus.Failed
      if (failure != null) {
        FailureBanner(failure.reason)
        Spacer(Modifier.height(12.dp))
      }
      Button(
          onClick = onSubmit,
          enabled = state.canSubmit,
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.create_event_submit),
      ) {
        when {
          state.status == CreateEventStatus.Saving -> {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.create_event_saving))
          }
          failure != null -> Text(stringResource(R.string.create_event_try_again))
          state.form.isPrivate -> Text(stringResource(R.string.create_event_submit_private))
          else -> Text(stringResource(R.string.create_event_submit_public))
        }
      }
    }
  }
}

@Composable
private fun FailureBanner(reason: CreateEventStatus.Failed.Reason) {
  Column(
      Modifier.fillMaxWidth()
          .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
          .padding(14.dp)
          .testTag(C.Tag.create_event_failure)
  ) {
    Text(
        stringResource(R.string.create_event_offline_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
    Text(
        stringResource(
            when (reason) {
              CreateEventStatus.Failed.Reason.OFFLINE -> R.string.create_event_offline_body
              CreateEventStatus.Failed.Reason.UNEXPECTED -> R.string.create_event_unexpected_body
            }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
  }
}

@Composable
private fun Labeled(@StringRes label: Int, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    content()
  }
}

@Composable
private fun PickerField(
    @StringRes label: Int,
    value: String?,
    enabled: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    @StringRes placeholder: Int = R.string.create_event_pick_date,
    onClick: () -> Unit,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
      Text(
          value ?: stringResource(placeholder),
          color =
              if (value == null) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
      )
    }
  }
}

@Composable
private fun VisibilityOption(
    @StringRes title: Int,
    @StringRes hint: Int,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
  OutlinedCard(
      border =
          BorderStroke(
              if (selected) 2.dp else 1.dp,
              if (selected) MaterialTheme.colorScheme.onSurface
              else MaterialTheme.colorScheme.outlineVariant,
          ),
      modifier =
          Modifier.fillMaxWidth()
              .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
              .testTag(tag),
  ) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
      RadioButton(selected = selected, onClick = null, enabled = enabled)
      Spacer(Modifier.width(8.dp))
      Column {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun Errors(state: CreateEventUiState, vararg errors: CreateEventFormError) {
  errors
      .filter { it in state.visibleErrors }
      .forEach { error ->
        Text(
            error.message(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp).testTag(C.Tag.createEventError(error.name)),
        )
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
  Scaffold(
      modifier = Modifier.testTag(C.Tag.event_created_screen),
      topBar = { TopRow(R.string.create_event_title, onClose) },
  ) { padding ->
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.weight(1f))
      Box(
          contentAlignment = Alignment.Center,
          modifier =
              Modifier.size(80.dp)
                  .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
      ) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(36.dp),
        )
      }
      Spacer(Modifier.height(16.dp))
      Text(
          stringResource(R.string.event_created_title),
          style = MaterialTheme.typography.headlineSmall,
      )
      Spacer(Modifier.height(8.dp))
      Text(
          stringResource(
              if (status.isPrivate) R.string.event_created_private_message
              else R.string.event_created_public_message,
              status.title,
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.weight(1f))
      Button(
          onClick = onViewEvent,
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.event_created_view_event),
      ) {
        Text(stringResource(R.string.event_created_view_event))
      }
      Spacer(Modifier.height(12.dp))
      OutlinedButton(
          onClick = onBackToMap,
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.event_created_back_to_map),
      ) {
        Text(stringResource(R.string.event_created_back_to_map))
      }
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
