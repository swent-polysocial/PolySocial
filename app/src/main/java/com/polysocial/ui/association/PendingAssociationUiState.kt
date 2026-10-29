// Contributors: Claude (pending association UI state, #127); OpenAI Codex (shared session
// controller, #140).
package com.polysocial.ui.association

import com.polysocial.model.association.Association

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
