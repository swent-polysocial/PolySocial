// Contributors: Claude (drafted the Create Event ViewModel); Mohamed Khellaf (reviewed).
package com.polysocial.ui.event.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.event.CreateEventResult
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the Create Event screen is in its flow. */
sealed interface CreateEventStatus {
  data object Editing : CreateEventStatus

  data object Saving : CreateEventStatus

  /** The event was saved; the screen shows the "Event created" confirmation. */
  data class Created(val eventId: String, val title: String, val isPrivate: Boolean) :
      CreateEventStatus

  /** Saving failed. The form keeps its input and the button becomes "Try again". */
  data class Failed(val reason: Reason) : CreateEventStatus {
    enum class Reason {
      OFFLINE,
      UNEXPECTED,
    }
  }
}

/**
 * Everything the Create Event screen shows.
 *
 * @property visibleErrors the errors to show now: those of the fields the organizer has changed, or
 *   all of them after a submit attempt.
 * @property canSubmit whether the Create button is enabled. It is disabled while saving, and after
 *   a submit attempt until the errors it revealed are fixed.
 * @property today the first date the date picker offers.
 */
data class CreateEventUiState(
    val today: LocalDate,
    val form: CreateEventForm = CreateEventForm(),
    val visibleErrors: Set<CreateEventFormError> = emptySet(),
    val canSubmit: Boolean = true,
    val status: CreateEventStatus = CreateEventStatus.Editing,
)

/**
 * Holds the Create Event form, checks it as the organizer types, and creates the event through
 * [EventRepository].
 *
 * Errors appear next to a field once it has been changed, and all at once when the organizer taps
 * Create with problems left; nothing is sent in that case. Offline and other failures keep the form
 * so the organizer can try again.
 *
 * @param clock gives the current time and time zone, so tests can fix them.
 */
@HiltViewModel
class CreateEventViewModel
@Inject
constructor(
    private val repository: EventRepository,
    private val clock: Clock,
) : ViewModel() {

  private val _uiState = MutableStateFlow(CreateEventUiState(today = LocalDate.now(clock)))
  val uiState: StateFlow<CreateEventUiState> = _uiState.asStateFlow()

  private val changedFields = mutableSetOf<Field>()
  private var submitAttempted = false

  fun onTitleChange(title: String) = edit(Field.TITLE) { it.copy(title = title) }

  fun onDescriptionChange(description: String) =
      edit(Field.DESCRIPTION) { it.copy(description = description) }

  fun onCategorySelect(category: EventCategory) =
      edit(Field.CATEGORY) { it.copy(category = category) }

  fun onDatePick(date: LocalDate) = edit(Field.DATE) { it.copy(date = date) }

  fun onStartTimePick(time: LocalTime) = edit(Field.DATE) { it.copy(startTime = time) }

  /** Sets the end time, or clears it with null (the end time is optional). */
  fun onEndTimePick(time: LocalTime?) = edit(Field.DATE) { it.copy(endTime = time) }

  /** Called with the place the location picker returns. */
  fun onLocationPick(location: PickedLocation) =
      edit(Field.LOCATION) { it.copy(location = location) }

  fun onCapacityChange(text: String) = edit(Field.CAPACITY) { it.copy(capacityText = text) }

  fun onVisibilityChange(isPrivate: Boolean) = edit(null) { it.copy(isPrivate = isPrivate) }

  /**
   * Creates the event if the form has no errors; otherwise shows every error and sends nothing.
   * Also used by "Try again" after a failure.
   */
  fun onSubmit() {
    val state = _uiState.value
    if (state.status == CreateEventStatus.Saving || state.status is CreateEventStatus.Created) {
      return
    }
    val errors = currentErrors(state.form)
    val event = state.form.toEvent(clock.zone)
    if (errors.isNotEmpty() || event == null) {
      submitAttempted = true
      publish(state.form, state.status)
      return
    }
    _uiState.update { it.copy(status = CreateEventStatus.Saving, canSubmit = false) }
    viewModelScope.launch {
      val status =
          when (val result = repository.createEvent(event)) {
            is CreateEventResult.Created ->
                CreateEventStatus.Created(result.eventId, event.title, event.isPrivate)
            is CreateEventResult.Invalid -> {
              submitAttempted = true
              CreateEventStatus.Editing
            }
            CreateEventResult.NetworkError ->
                CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.OFFLINE)
            CreateEventResult.NotSignedIn,
            CreateEventResult.UnexpectedError ->
                CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.UNEXPECTED)
          }
      publish(_uiState.value.form, status)
    }
  }

  private fun edit(field: Field?, change: (CreateEventForm) -> CreateEventForm) {
    val state = _uiState.value
    if (state.status == CreateEventStatus.Saving || state.status is CreateEventStatus.Created) {
      return
    }
    field?.let { changedFields += it }
    val status =
        if (state.status is CreateEventStatus.Failed) CreateEventStatus.Editing else state.status
    publish(change(state.form), status)
  }

  private fun publish(form: CreateEventForm, status: CreateEventStatus) {
    val errors = currentErrors(form)
    val shown = if (submitAttempted) errors else errors.filter { it.field in changedFields }.toSet()
    _uiState.value =
        CreateEventUiState(
            today = LocalDate.now(clock),
            form = form,
            visibleErrors = shown,
            canSubmit =
                status != CreateEventStatus.Saving && !(submitAttempted && errors.isNotEmpty()),
            status = status,
        )
  }

  private fun currentErrors(form: CreateEventForm) = form.errors(clock.instant(), clock.zone)

  /** The form field an error is shown under. */
  private enum class Field {
    TITLE,
    DESCRIPTION,
    CATEGORY,
    DATE,
    LOCATION,
    CAPACITY,
  }

  private val CreateEventFormError.field: Field
    get() =
        when (this) {
          CreateEventFormError.MISSING_TITLE,
          CreateEventFormError.TITLE_TOO_LONG -> Field.TITLE
          CreateEventFormError.DESCRIPTION_TOO_LONG -> Field.DESCRIPTION
          CreateEventFormError.MISSING_CATEGORY -> Field.CATEGORY
          CreateEventFormError.MISSING_DATE,
          CreateEventFormError.START_IN_PAST,
          CreateEventFormError.END_SAME_AS_START -> Field.DATE
          CreateEventFormError.MISSING_LOCATION -> Field.LOCATION
          CreateEventFormError.CAPACITY_NOT_A_NUMBER,
          CreateEventFormError.CAPACITY_TOO_SMALL -> Field.CAPACITY
        }
}
