// Contributors: Claude (drafted the Create Event ViewModel); Mohamed Khellaf (reviewed). Claude
// (editing an event, #131; the repository's errors, #131 review; the organizer row, #134).
package com.polysocial.ui.event.create

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.CreateEventResult
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.event.EventValidationError
import com.polysocial.model.event.UpdateEventResult
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.ui.event.detail.EventDetailViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where the Create Event screen (or Edit event, see [CreateEventUiState.isEditing]) is in its flow.
 */
sealed interface CreateEventStatus {
  /** Edit event only: the event to edit is being read, to fill in the form. */
  data object Loading : CreateEventStatus

  data object Editing : CreateEventStatus

  data object Saving : CreateEventStatus

  /** The event was saved; the screen shows the "Event created" confirmation. */
  data class Created(val eventId: String, val title: String, val isPrivate: Boolean) :
      CreateEventStatus

  /** Edit event only: the changes were saved, so the screen closes on the updated event. */
  data object Saved : CreateEventStatus

  /**
   * Edit event only: the event can't be read (deleted, not allowed, or offline and never loaded),
   * so the screen closes and the event detail below it shows why.
   */
  data object Unavailable : CreateEventStatus

  /** Saving failed. The form keeps its input and the button becomes "Try again". */
  data class Failed(val reason: Reason) : CreateEventStatus {
    enum class Reason {
      OFFLINE,
      UNEXPECTED,
    }
  }
}

/**
 * Who the form says organizes the event, shown at its top (#134). It is read once when the screen
 * opens, so it starts as [Loading].
 */
sealed interface Organizer {
  /** The account is still being read: the row stays empty, so no wrong organizer flashes. */
  data object Loading : Organizer

  /**
   * "Organized by you": a student, an association an admin hasn't verified yet, nobody signed in,
   * or an account whose profile or association can't be read (offline, missing): the form works the
   * same.
   */
  data object You : Organizer

  /** A verified association, which publishes under its [name] with the Verified badge. */
  data class Association(val name: String) : Organizer
}

/**
 * Everything the Create Event screen shows.
 *
 * @property visibleErrors the errors to show now: those of the fields the organizer has changed, or
 *   all of them after a submit attempt, plus those the repository refused the event for.
 * @property canSubmit whether the Create button is enabled. It is disabled while saving, and after
 *   a submit attempt until the errors it revealed are fixed.
 * @property today the first date the date picker offers.
 * @property isEditing true for Edit event, which saves changes to an existing event instead of
 *   creating one.
 * @property organizer who the form says organizes the event.
 */
data class CreateEventUiState(
    val today: LocalDate,
    val isEditing: Boolean = false,
    val form: CreateEventForm = CreateEventForm(),
    val visibleErrors: Set<CreateEventFormError> = emptySet(),
    val canSubmit: Boolean = true,
    val status: CreateEventStatus = CreateEventStatus.Editing,
    val organizer: Organizer = Organizer.Loading,
)

/**
 * Holds the Create Event form, checks it as the organizer types, and creates the event through
 * [EventRepository].
 *
 * Errors appear next to a field once it has been changed, and all at once when the organizer taps
 * Create with problems left; nothing is sent in that case. Offline and other failures keep the form
 * so the organizer can try again.
 *
 * With an [EventDetailViewModel.EVENT_ID_ARG] navigation argument, it edits that event instead
 * (#131): it reads the event once ([CreateEventStatus.Loading]) to fill in the form, then saves the
 * changes with [EventRepository.updateEvent]. Edit event opens from the event detail, which has
 * already read the event, so the read normally answers at once from Firestore's cache. A start that
 * is already past is accepted while it is the stored one (see [errors]).
 *
 * It also reads who organizes the event (#134): a verified association sees its name, from its
 * profile ([UserProfileRepository]) and its association ([AssociationRepository]); everyone else,
 * and an account whose profile or association can't be read, sees "Organized by you". The form
 * never waits for it.
 *
 * @param clock gives the current time and time zone, so tests can fix them.
 * @param savedStateHandle holds the navigation arguments; without an event ID, it creates an event.
 * @param auth gives the signed-in account, whose organizer row is shown.
 * @param profiles reads that account's profile, to know whether it is a verified association.
 * @param associations reads a verified association's name.
 */
@HiltViewModel
class CreateEventViewModel
@Inject
constructor(
    private val repository: EventRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val profiles: UserProfileRepository,
    private val associations: AssociationRepository,
) : ViewModel() {

  /** The ID of the event being edited, or null when creating one. */
  private val editedId: String? = savedStateHandle.get<String>(EventDetailViewModel.EVENT_ID_ARG)

  private val _uiState =
      MutableStateFlow(
          CreateEventUiState(
              today = LocalDate.now(clock),
              isEditing = editedId != null,
              status =
                  if (editedId != null) CreateEventStatus.Loading else CreateEventStatus.Editing,
          )
      )
  val uiState: StateFlow<CreateEventUiState> = _uiState.asStateFlow()

  private val changedFields = mutableSetOf<Field>()
  private var submitAttempted = false

  /**
   * The rules the repository refused the last save for, shown until the form changes. They can
   * differ from the form's own errors: the minute may have passed, or the stored start may have
   * changed since the screen opened.
   */
  private var refused: Set<CreateEventFormError> = emptySet()

  /** The event being edited, as read when the screen opened. */
  private var stored: Event? = null

  init {
    viewModelScope.launch {
      val organizer = readOrganizer()
      _uiState.update { it.copy(organizer = organizer) }
    }
    editedId?.let { id -> viewModelScope.launch { load(id) } }
  }

  /**
   * The signed-in account's organizer row. Only a verified association's name is read, and any
   * failure gives [Organizer.You], since the repositories report failures as results.
   */
  private suspend fun readOrganizer(): Organizer {
    val uid = auth.currentUser()?.uid ?: return Organizer.You
    val profile = (profiles.getProfile(uid) as? ProfileResult.Found)?.profile
    if (profile?.isVerifiedAssociation != true) return Organizer.You
    val name = (associations.getAssociation(uid) as? AssociationResult.Found)?.association?.name
    return if (name.isNullOrBlank()) Organizer.You else Organizer.Association(name)
  }

  private suspend fun load(id: String) {
    when (val result = repository.getEvent(id).first()) {
      is EventResult.Found -> {
        stored = result.event
        publish(result.event.toForm(clock.zone), CreateEventStatus.Editing)
      }
      EventResult.NotFound,
      EventResult.NotCached -> _uiState.update { it.copy(status = CreateEventStatus.Unavailable) }
    }
  }

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
   * Creates the event (or saves the changes, when editing) if the form has no errors; otherwise
   * shows every error and sends nothing. Also used by "Try again" after a failure.
   */
  fun onSubmit() {
    val state = _uiState.value
    if (state.status.isLocked) return
    val errors = currentErrors(state.form)
    val event = state.form.toEvent(clock.zone)
    if (errors.isNotEmpty() || event == null) {
      submitAttempted = true
      publish(state.form, state.status)
      return
    }
    _uiState.update { it.copy(status = CreateEventStatus.Saving, canSubmit = false) }
    viewModelScope.launch {
      val status = editedId?.let { save(event.copy(id = it)) } ?: create(event)
      publish(_uiState.value.form, status)
    }
  }

  private suspend fun save(event: Event): CreateEventStatus =
      when (val result = repository.updateEvent(event)) {
        UpdateEventResult.Updated -> CreateEventStatus.Saved
        is UpdateEventResult.Invalid -> refuse(result.errors)
        UpdateEventResult.NetworkError ->
            CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.OFFLINE)
        // Never reported as saved: the form stays, with Try again (#131).
        UpdateEventResult.NotOrganizer,
        UpdateEventResult.NotFound,
        UpdateEventResult.UnexpectedError ->
            CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.UNEXPECTED)
      }

  private suspend fun create(event: Event): CreateEventStatus =
      when (val result = repository.createEvent(event)) {
        is CreateEventResult.Created ->
            CreateEventStatus.Created(result.eventId, event.title, event.isPrivate)
        is CreateEventResult.Invalid -> refuse(result.errors)
        CreateEventResult.NetworkError ->
            CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.OFFLINE)
        CreateEventResult.NotSignedIn,
        CreateEventResult.UnexpectedError ->
            CreateEventStatus.Failed(CreateEventStatus.Failed.Reason.UNEXPECTED)
      }

  private fun refuse(errors: List<EventValidationError>): CreateEventStatus {
    submitAttempted = true
    refused = errors.map { it.toFormError() }.toSet()
    return CreateEventStatus.Editing
  }

  private fun edit(field: Field?, change: (CreateEventForm) -> CreateEventForm) {
    val state = _uiState.value
    if (state.status.isLocked) return
    field?.let { changedFields += it }
    refused = emptySet()
    val status =
        if (state.status is CreateEventStatus.Failed) CreateEventStatus.Editing else state.status
    publish(change(state.form), status)
  }

  private fun publish(form: CreateEventForm, status: CreateEventStatus) {
    val errors = currentErrors(form) + refused
    val shown = if (submitAttempted) errors else errors.filter { it.field in changedFields }.toSet()
    // update, not a plain set: the organizer read may answer in between, and must be kept.
    _uiState.update {
      CreateEventUiState(
          today = LocalDate.now(clock),
          isEditing = editedId != null,
          form = form,
          visibleErrors = shown,
          canSubmit =
              status != CreateEventStatus.Saving && !(submitAttempted && errors.isNotEmpty()),
          status = status,
          organizer = it.organizer,
      )
    }
  }

  private fun currentErrors(form: CreateEventForm) =
      form.errors(clock.instant(), clock.zone, keptStart = stored?.startTime)

  /** True while the form can't be changed or sent: before it is filled in, or once it is sent. */
  private val CreateEventStatus.isLocked: Boolean
    get() =
        when (this) {
          CreateEventStatus.Editing,
          is CreateEventStatus.Failed -> false
          CreateEventStatus.Loading,
          CreateEventStatus.Saving,
          is CreateEventStatus.Created,
          CreateEventStatus.Saved,
          CreateEventStatus.Unavailable -> true
        }

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
