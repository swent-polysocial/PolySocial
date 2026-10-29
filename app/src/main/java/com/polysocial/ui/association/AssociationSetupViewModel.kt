// Contributors: OpenAI Codex (association setup state, sequential writes and retry, #125).
package com.polysocial.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.CreateAssociationResult
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AssociationSetupFailure {
  Network,
  Unexpected,
  NotSignedIn,
  WrongAccount,
}

data class AssociationSetupUiState(
    val form: AssociationSetupForm = AssociationSetupForm(),
    val touched: Set<AssociationSetupField> = emptySet(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AssociationSetupFailure? = null,
)

/**
 * Saves the private association account before its public identity, as #126's rules require.
 * Retries preserve the form; a lost create response is recovered through AlreadyExists. Only
 * noncredential form values are restored, and only for the same authenticated UID.
 */
@HiltViewModel
class AssociationSetupViewModel
@Inject
constructor(
    private val auth: AuthRepository,
    private val profiles: UserProfileRepository,
    private val associations: AssociationRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
  private var ownerUid = auth.currentUser()?.uid
  private var save: Job? = null
  private val state = MutableStateFlow(restore(ownerUid))
  val uiState = state.asStateFlow()

  /** Clears another session's form when this retained model is opened after an account change. */
  fun open() {
    val uid = auth.currentUser()?.uid
    if (uid == ownerUid) return
    save?.cancel()
    ownerUid = uid
    state.value = AssociationSetupUiState()
    persist()
  }

  fun update(field: AssociationSetupField, value: String) {
    edit(field) { form ->
      when (field) {
        AssociationSetupField.Name -> form.copy(name = value)
        AssociationSetupField.Type ->
            form.copy(type = AssociationType.entries.firstOrNull { it.name == value })
        AssociationSetupField.About -> form.copy(about = value)
        AssociationSetupField.Website -> form.copy(website = value)
      }
    }
  }

  fun selectType(type: AssociationType) = edit(AssociationSetupField.Type) { it.copy(type = type) }

  fun submit() {
    open()
    val before = state.value
    if (before.saving || before.saved) return
    if (!before.form.isValid) {
      state.value = before.copy(touched = AssociationSetupField.entries.toSet())
      return
    }
    val user = auth.currentUser()
    if (user == null || !user.isEmailVerified) {
      state.value = before.copy(error = AssociationSetupFailure.NotSignedIn)
      return
    }
    state.value = before.copy(saving = true, error = null)
    save = viewModelScope.launch {
      try {
        val failure = save(user, before.form)
        if (ownerUid == user.uid) {
          val outcome = if (sameSession(user.uid)) failure else AssociationSetupFailure.NotSignedIn
          state.value = state.value.copy(saving = false, saved = outcome == null, error = outcome)
        }
      } catch (cancelled: CancellationException) {
        if (ownerUid == user.uid) state.value = state.value.copy(saving = false)
        throw cancelled
      } catch (_: Exception) {
        if (ownerUid == user.uid) {
          state.value = state.value.copy(saving = false, error = AssociationSetupFailure.Unexpected)
        }
      }
    }
  }

  private suspend fun save(user: AuthUser, form: AssociationSetupForm): AssociationSetupFailure? {
    val current = profiles.getProfile(user.uid)
    if (!sameSession(user.uid)) return AssociationSetupFailure.NotSignedIn
    val accountFailure =
        when (current) {
          is ProfileResult.Found -> accountFailure(current)
          ProfileResult.NetworkError -> AssociationSetupFailure.Network
          ProfileResult.UnexpectedError -> AssociationSetupFailure.Unexpected
          ProfileResult.NotFound -> createAccount(user, form)
        }
    if (accountFailure != null) return accountFailure
    if (!sameSession(user.uid)) return AssociationSetupFailure.NotSignedIn
    return when (associations.createAssociation(form.association(user.uid))) {
      CreateAssociationResult.Created,
      CreateAssociationResult.AlreadyExists -> null
      CreateAssociationResult.NetworkError -> AssociationSetupFailure.Network
      CreateAssociationResult.UnexpectedError -> AssociationSetupFailure.Unexpected
    }
  }

  private suspend fun createAccount(
      user: AuthUser,
      form: AssociationSetupForm,
  ): AssociationSetupFailure? {
    val profile =
        UserProfile(
            uid = user.uid,
            email = user.email,
            displayName = form.name.trim(),
            section = "",
            year = "",
            accountType = AccountType.ASSOCIATION,
        )
    return when (profiles.createProfile(profile)) {
      CreateProfileResult.Created -> null
      CreateProfileResult.NetworkError -> AssociationSetupFailure.Network
      CreateProfileResult.UnexpectedError -> AssociationSetupFailure.Unexpected
      CreateProfileResult.AlreadyExists -> {
        if (!sameSession(user.uid)) AssociationSetupFailure.NotSignedIn
        else accountFailure(profiles.getProfile(user.uid))
      }
    }
  }

  private fun accountFailure(result: ProfileResult): AssociationSetupFailure? =
      when (result) {
        is ProfileResult.Found ->
            if (result.profile.accountType == AccountType.ASSOCIATION) null
            else AssociationSetupFailure.WrongAccount
        ProfileResult.NetworkError -> AssociationSetupFailure.Network
        ProfileResult.NotFound,
        ProfileResult.UnexpectedError -> AssociationSetupFailure.Unexpected
      }

  private fun sameSession(uid: String): Boolean =
      auth.currentUser()?.let { it.uid == uid && it.isEmailVerified } == true

  private fun edit(
      field: AssociationSetupField,
      change: (AssociationSetupForm) -> AssociationSetupForm,
  ) {
    open()
    val current = state.value
    if (current.saving || current.saved) return
    state.value =
        current.copy(form = change(current.form), touched = current.touched + field, error = null)
    persist()
  }

  private fun restore(uid: String?): AssociationSetupUiState {
    if (uid == null || savedStateHandle.get<Any>("association.uid") != uid) {
      clearSavedForm()
      return AssociationSetupUiState()
    }
    fun text(key: String) = savedStateHandle.get<Any>("association.$key") as? String ?: ""
    return AssociationSetupUiState(
        form =
            AssociationSetupForm(
                text("name"),
                AssociationType.entries.firstOrNull { it.name == text("type") },
                text("about"),
                text("website"),
            )
    )
  }

  private fun persist() {
    clearSavedForm()
    val uid = ownerUid ?: return
    savedStateHandle["association.uid"] = uid
    val form = state.value.form
    savedStateHandle["association.name"] = form.name
    savedStateHandle["association.type"] = form.type?.name
    savedStateHandle["association.about"] = form.about
    savedStateHandle["association.website"] = form.website
  }

  private fun clearSavedForm() {
    listOf("uid", "name", "type", "about", "website").forEach {
      savedStateHandle.remove<Any>("association.$it")
    }
  }
}
