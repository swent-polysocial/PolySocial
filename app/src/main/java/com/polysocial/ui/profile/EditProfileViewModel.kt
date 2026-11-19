// Contributors: OpenAI Codex (student profile editing and retryable saves, #235).
package com.polysocial.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.INTERESTS
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.SECTIONS
import com.polysocial.model.user.UpdateProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.model.user.YEARS
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The editor retains all values after a failed write; only a confirmed update closes it. */
sealed interface EditProfileState {
  data object Loading : EditProfileState

  data object CouldNotLoad : EditProfileState

  data class Editing(
      val original: UserProfile,
      val section: String = original.section,
      val year: String = original.year,
      val interests: Set<String> = original.interests,
      val saving: Boolean = false,
      val failure: Failure? = null,
      val saved: Boolean = false,
  ) : EditProfileState {
    val locked
      get() = saving || saved

    val valid
      get() = section in SECTIONS && year in YEARS

    val changed
      get() =
          section != original.section || year != original.year || interests != original.interests
  }

  enum class Failure {
    OFFLINE,
    UNEXPECTED,
  }
}

/**
 * Edits only section, year and interests on the signed-in student's existing profile. The
 * repository writes the private and public copies together. Each visit is scoped to its navigation
 * entry; a different account can never save the previous account's form. Missing or failed reads
 * offer Retry and Back, and a failed write retains the form for an explicit retry.
 */
@HiltViewModel
class EditProfileViewModel
@Inject
constructor(
    private val auth: AuthRepository,
    private val profiles: UserProfileRepository,
) : ViewModel() {
  private val uid = auth.currentUser()?.uid
  private val state = MutableStateFlow<EditProfileState>(EditProfileState.Loading)
  val uiState = state.asStateFlow()

  init {
    load()
  }

  fun retry() {
    if (state.value == EditProfileState.CouldNotLoad) load()
  }

  fun section(value: String) {
    if (value in SECTIONS) edit { it.copy(section = value) }
  }

  fun year(value: String) {
    if (value in YEARS) edit { it.copy(year = value) }
  }

  fun toggleInterest(value: String) {
    if (value in INTERESTS)
        edit {
          it.copy(
              interests = if (value in it.interests) it.interests - value else it.interests + value
          )
        }
  }

  fun save() {
    val form = state.value as? EditProfileState.Editing ?: return
    if (form.locked || !form.valid || !form.changed) return
    if (!sameAccount()) {
      state.value = form.copy(failure = EditProfileState.Failure.UNEXPECTED)
      return
    }
    state.value = form.copy(saving = true, failure = null)
    viewModelScope.launch {
      val result =
          try {
            profiles.updateProfile(
                form.original.copy(
                    section = form.section,
                    year = form.year,
                    interests = form.interests,
                )
            )
          } catch (cancelled: CancellationException) {
            state.value = form
            throw cancelled
          } catch (_: Exception) {
            UpdateProfileResult.UnexpectedError
          }
      state.value =
          when (result) {
            UpdateProfileResult.Updated -> form.copy(saved = true)
            UpdateProfileResult.NetworkError ->
                form.copy(failure = EditProfileState.Failure.OFFLINE)
            else -> form.copy(failure = EditProfileState.Failure.UNEXPECTED)
          }
    }
  }

  private fun sameAccount() =
      auth.currentUser()?.let { it.uid == uid && it.isEmailVerified } == true

  private fun load() {
    state.value = EditProfileState.Loading
    viewModelScope.launch {
      val profile =
          try {
            if (uid == null || !sameAccount()) null
            else
                withTimeoutOrNull(10_000) {
                  (profiles.getProfile(uid) as? ProfileResult.Found)?.profile
                }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            null
          }
      state.value =
          if (
              profile != null &&
                  profile.uid == uid &&
                  sameAccount() &&
                  profile.accountType == AccountType.STUDENT
          )
              EditProfileState.Editing(profile)
          else EditProfileState.CouldNotLoad
    }
  }

  private fun edit(change: (EditProfileState.Editing) -> EditProfileState.Editing) = state.update {
    if (it is EditProfileState.Editing && !it.locked) change(it).copy(failure = null) else it
  }
}
