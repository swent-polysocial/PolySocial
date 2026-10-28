// Contributors: Claude (the association shown by the pending association app, #127; read again
// for each session, after a review, #127).
package com.polysocial.ui.association

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The signed-in association, as the pending association app shows it. */
sealed interface PendingAssociationUiState {
  data object Loading : PendingAssociationUiState

  data class Loaded(val association: Association) : PendingAssociationUiState

  /**
   * Its `associations/{uid}` couldn't be read: offline and not cached, missing (setup not
   * finished, #125), or unreadable. The tabs still work, without its name, and Log out stays
   * available.
   */
  data object Failed : PendingAssociationUiState
}

/**
 * Reads the signed-in association's details once, for every tab of its pending app (#127): the
 * Events and Map headers show its name and initials, and the Organization tab its details. A failed
 * read can be retried.
 */
@HiltViewModel
class PendingAssociationViewModel
@Inject
constructor(
    private val auth: AuthRepository,
    private val associations: AssociationRepository,
) : ViewModel() {
  private val _uiState =
      MutableStateFlow<PendingAssociationUiState>(PendingAssociationUiState.Loading)
  val uiState: StateFlow<PendingAssociationUiState> = _uiState.asStateFlow()

  /** The account whose details are shown or being read, so another account reads its own. */
  private var shownUid: String? = null

  /** The running read, cancelled by a newer one so an old account's details can't come back. */
  private var read: Job? = null

  init {
    load()
  }

  /**
   * The app opened for the signed-in account. This ViewModel outlives a session (it belongs to the
   * activity), so details of another account, or none after [close], are read again.
   */
  fun open() {
    if (auth.currentUser()?.uid != shownUid) load()
  }

  /** The account logged out: forgets its details, so nothing of them shows to the next one. */
  fun close() {
    read?.cancel()
    shownUid = null
    _uiState.value = PendingAssociationUiState.Loading
  }

  /** Reads the details again after [PendingAssociationUiState.Failed]; ignored otherwise. */
  fun retry() {
    if (_uiState.value == PendingAssociationUiState.Failed) load()
  }

  private fun load() {
    read?.cancel()
    val uid = auth.currentUser()?.uid
    shownUid = uid
    if (uid == null) {
      _uiState.value = PendingAssociationUiState.Failed
      return
    }
    _uiState.value = PendingAssociationUiState.Loading
    read = viewModelScope.launch {
      _uiState.value =
          when (val result = associations.getAssociation(uid)) {
            is AssociationResult.Found -> PendingAssociationUiState.Loaded(result.association)
            AssociationResult.NotFound,
            AssociationResult.NetworkError,
            AssociationResult.UnexpectedError -> PendingAssociationUiState.Failed
          }
    }
  }
}
