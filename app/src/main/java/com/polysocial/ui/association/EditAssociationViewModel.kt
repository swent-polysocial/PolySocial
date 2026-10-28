// Contributors: Claude (the Edit details form of a pending association, its checks and its save,
// #127; no emoji cut at a limit, after a review, #127).
package com.polysocial.ui.association

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.MAX_ASSOCIATION_ABOUT_LENGTH
import com.polysocial.model.association.MAX_ASSOCIATION_NAME_LENGTH
import com.polysocial.model.association.MAX_ASSOCIATION_WEBSITE_LENGTH
import com.polysocial.model.association.UpdateAssociationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The fields of the Edit details form, as typed. */
data class AssociationForm(
    val name: String,
    val type: AssociationType,
    val about: String,
    val website: String,
)

/** What keeps the form from being saved, each shown under its field (Figma "· errors"). */
enum class AssociationFormError {
  NAME_MISSING,
  ABOUT_MISSING,
  /** Not empty, and not a link the Security Rules accept: `https://` then no whitespace. */
  WEBSITE_INVALID,
}

/**
 * The errors of [form], with the Security Rules' checks (#126): a name and an about that aren't
 * blank, and an optional website of the form `https://…` without whitespace. Spaces around a field
 * don't count, since it is saved trimmed. The lengths can't be exceeded: the form stops at them.
 */
fun associationFormErrors(form: AssociationForm): Set<AssociationFormError> = buildSet {
  if (form.name.isBlank()) add(AssociationFormError.NAME_MISSING)
  if (form.about.isBlank()) add(AssociationFormError.ABOUT_MISSING)
  val website = form.website.trim()
  if (website.isNotEmpty() && !WEBSITE.matches(website)) add(AssociationFormError.WEBSITE_INVALID)
}

/**
 * At most [length] characters, without cutting a character made of two (an emoji) in half, as the
 * event form does with place names (#46).
 */
private fun String.takeWhole(length: Int): String {
  val cut = take(length)
  return if (cut.length == length && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** The Security Rules' website check (`https://[^\s]+`). */
private val WEBSITE = Regex("https://\\S+")

/** Where saving the form is. */
sealed interface EditAssociationStatus {
  data object Editing : EditAssociationStatus

  data object Saving : EditAssociationStatus

  /** Nothing was saved; the form is kept. [offline] picks Figma's offline message. */
  data class Failed(val offline: Boolean) : EditAssociationStatus

  /** Saved: the screen closes and shows [association]. */
  data class Saved(val association: Association) : EditAssociationStatus
}

/**
 * The Edit details form. [form] is null until [EditAssociationViewModel.start]. Errors show only
 * once Save was tapped ([showErrors]), as on Create event, and Save stays disabled while one shows.
 */
data class EditAssociationUiState(
    val form: AssociationForm? = null,
    val status: EditAssociationStatus = EditAssociationStatus.Editing,
    val showErrors: Boolean = false,
) {
  val visibleErrors: Set<AssociationFormError>
    get() = if (showErrors && form != null) associationFormErrors(form) else emptySet()

  val canSave: Boolean
    get() =
        form != null &&
            status != EditAssociationStatus.Saving &&
            status !is EditAssociationStatus.Saved &&
            visibleErrors.isEmpty()
}

/**
 * Edits a pending association's details (Figma "Association · pending · Edit details", #127): name,
 * type, about and optional website, saved with [AssociationRepository.updateAssociation]. The form
 * starts from the details shown on the Organization tab. A failed save keeps the form and offers
 * Try again; the offline case has its own message.
 */
@HiltViewModel
class EditAssociationViewModel
@Inject
constructor(private val associations: AssociationRepository) : ViewModel() {
  private val _uiState = MutableStateFlow(EditAssociationUiState())
  val uiState: StateFlow<EditAssociationUiState> = _uiState.asStateFlow()

  /** The association being edited, which the save keeps everything else of (its UID). */
  private var original: Association? = null

  /** Fills the form from [association] once; later calls (a recreated screen) keep the edits. */
  fun start(association: Association) {
    if (original != null) return
    original = association
    _uiState.value =
        EditAssociationUiState(
            AssociationForm(
                association.name,
                association.type,
                association.about,
                association.website.orEmpty(),
            )
        )
  }

  fun onNameChange(name: String) = edit { copy(name = name.takeWhole(MAX_ASSOCIATION_NAME_LENGTH)) }

  fun onTypeChange(type: AssociationType) = edit { copy(type = type) }

  fun onAboutChange(about: String) = edit {
    copy(about = about.takeWhole(MAX_ASSOCIATION_ABOUT_LENGTH))
  }

  fun onWebsiteChange(website: String) = edit {
    copy(website = website.takeWhole(MAX_ASSOCIATION_WEBSITE_LENGTH))
  }

  /**
   * Saves the form, trimmed, with an empty website removed; or, with an error, shows the errors and
   * saves nothing. Ignored while saving or once saved.
   */
  fun save() {
    val state = _uiState.value
    val form = state.form ?: return
    val association = original ?: return
    if (state.status == EditAssociationStatus.Saving || state.status is EditAssociationStatus.Saved)
        return
    if (associationFormErrors(form).isNotEmpty()) {
      _uiState.update { it.copy(showErrors = true) }
      return
    }
    val updated =
        association.copy(
            name = form.name.trim(),
            type = form.type,
            about = form.about.trim(),
            website = form.website.trim().ifEmpty { null },
        )
    _uiState.update { it.copy(status = EditAssociationStatus.Saving) }
    viewModelScope.launch {
      val status =
          when (associations.updateAssociation(updated)) {
            UpdateAssociationResult.Updated -> EditAssociationStatus.Saved(updated)
            UpdateAssociationResult.NetworkError -> EditAssociationStatus.Failed(offline = true)
            UpdateAssociationResult.NotFound,
            UpdateAssociationResult.UnexpectedError -> EditAssociationStatus.Failed(offline = false)
          }
      _uiState.update { it.copy(status = status) }
    }
  }

  /** Changes the form, unless it is saving or saved; a failed save's message goes away. */
  private fun edit(change: AssociationForm.() -> AssociationForm) {
    _uiState.update { state ->
      val form = state.form
      if (
          form == null ||
              state.status == EditAssociationStatus.Saving ||
              state.status is EditAssociationStatus.Saved
      )
          state
      else state.copy(form = form.change(), status = EditAssociationStatus.Editing)
    }
  }
}
