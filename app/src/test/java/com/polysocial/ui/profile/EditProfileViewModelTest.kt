// Contributors: OpenAI Codex (tests for editing section, year and interests and recovering from
// repository failures, #235).
package com.polysocial.ui.profile

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UpdateProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditProfileViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val user =
      AuthUser(
          uid = "u1",
          email = "a@epfl.ch",
          isEmailVerified = true,
          displayName = "Test Student",
      )
  private val profile =
      UserProfile(
          uid = "u1",
          email = "a@epfl.ch",
          displayName = "Test Student",
          section = "IN",
          year = "BA3",
          interests = setOf("music"),
      )
  private val auth = FakeAuthRepository(user)
  private val profiles = FakeUserProfileRepository(ProfileResult.Found(profile))

  private fun viewModel(repository: UserProfileRepository = profiles) =
      EditProfileViewModel(auth, repository).also { settle() }

  private fun settle() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  private fun EditProfileViewModel.form() = uiState.value as EditProfileState.Editing

  @Test
  fun loadsTheSignedInStudentsProfile() {
    val vm = viewModel()

    assertEquals(profile, vm.form().original)
    assertEquals("IN", vm.form().section)
    assertEquals(setOf("music"), vm.form().interests)
    assertFalse(vm.form().changed)
  }

  @Test
  fun savingChangedValues_updatesThePrivateAndPublicProfile_andClosesTheEditor() {
    val vm = viewModel()
    vm.section("EL")
    vm.year("MA1")
    vm.toggleInterest("cinema")
    vm.toggleInterest("music")
    vm.save()
    settle()

    assertEquals(
        listOf(profile.copy(section = "EL", year = "MA1", interests = setOf("cinema"))),
        profiles.updatedProfiles,
    )
    assertTrue(vm.form().saved)
  }

  @Test
  fun savingWithoutAChange_isIgnored() {
    val vm = viewModel()
    vm.save()
    settle()

    assertTrue(profiles.updatedProfiles.isEmpty())
  }

  @Test
  fun unknownValues_areIgnored() {
    val vm = viewModel()
    vm.section("XX")
    vm.year("PhD")
    vm.toggleInterest("unknown")

    assertFalse(vm.form().changed)
  }

  @Test
  fun offline_keepsTheFormAndTheEditedValues_andRetrySucceeds() {
    profiles.updateProfileResult = UpdateProfileResult.NetworkError
    val vm = viewModel()
    vm.section("EL")
    vm.save()
    settle()

    assertEquals(EditProfileState.Failure.OFFLINE, vm.form().failure)
    assertEquals("EL", vm.form().section)
    assertFalse(vm.form().saved)

    profiles.updateProfileResult = UpdateProfileResult.Updated
    vm.save()
    settle()

    assertTrue(vm.form().saved)
    assertEquals(2, profiles.updatedProfiles.size)
  }

  @Test
  fun anUnexpectedFailure_keepsTheForm() {
    profiles.updateProfileResult = UpdateProfileResult.UnexpectedError
    val vm = viewModel()
    vm.year("BA4")
    vm.save()
    settle()

    assertEquals(EditProfileState.Failure.UNEXPECTED, vm.form().failure)
    assertEquals("BA4", vm.form().year)
  }

  @Test
  fun aThrownWriteFailure_offersRetryWithTheSameEdits() {
    var writeFails = true
    val repository =
        object : UserProfileRepository by profiles {
          override suspend fun updateProfile(profile: UserProfile): UpdateProfileResult {
            if (writeFails) error("Simulated repository failure")
            return profiles.updateProfile(profile)
          }
        }
    val vm = viewModel(repository)
    vm.year("BA4")
    vm.save()
    settle()

    assertEquals(EditProfileState.Failure.UNEXPECTED, vm.form().failure)
    assertEquals("BA4", vm.form().year)
    assertFalse(vm.form().locked)

    writeFails = false
    vm.save()
    settle()

    assertEquals(listOf(profile.copy(year = "BA4")), profiles.updatedProfiles)
    assertTrue(vm.form().saved)
  }

  @Test
  fun aCancelledWrite_unlocksTheFormAndKeepsTheEdits() {
    val repository =
        object : UserProfileRepository by profiles {
          override suspend fun updateProfile(profile: UserProfile): UpdateProfileResult =
              throw CancellationException("Simulated cancelled write")
        }
    val vm = viewModel(repository)
    vm.year("BA4")
    val edited = vm.form()
    vm.save()
    settle()

    assertEquals(edited, vm.uiState.value)
  }

  @Test
  fun editingAfterAFailure_clearsTheError() {
    profiles.updateProfileResult = UpdateProfileResult.NetworkError
    val vm = viewModel()
    vm.section("EL")
    vm.save()
    settle()
    vm.year("BA4")

    assertEquals(null, vm.form().failure)
  }

  @Test
  fun aSecondSave_isIgnored_andTheSavedFormStaysLocked() {
    val vm = viewModel()
    vm.section("EL")
    vm.save()
    vm.save()
    settle()

    assertEquals(1, profiles.updatedProfiles.size)
    vm.section("MA")
    assertEquals("EL", vm.form().section)
  }

  @Test
  fun aMissingProfile_offersRetry_andRecovers() {
    profiles.getProfileResult = ProfileResult.NotFound
    val vm = viewModel()
    assertEquals(EditProfileState.CouldNotLoad, vm.uiState.value)

    profiles.getProfileResult = ProfileResult.Found(profile)
    vm.retry()
    settle()

    assertTrue(vm.uiState.value is EditProfileState.Editing)
  }

  @Test
  fun aThrownReadFailure_offersRetryAndRecovers() {
    profiles.gate =
        CompletableDeferred<Unit>().apply {
          completeExceptionally(IllegalStateException("Simulated repository failure"))
        }
    val vm = viewModel()
    assertEquals(EditProfileState.CouldNotLoad, vm.uiState.value)

    profiles.gate = null
    vm.retry()
    settle()

    assertEquals(profile, vm.form().original)
  }

  @Test
  fun anAssociationProfile_isNotEditable() {
    profiles.getProfileResult =
        ProfileResult.Found(profile.copy(accountType = AccountType.ASSOCIATION))

    assertEquals(EditProfileState.CouldNotLoad, viewModel().uiState.value)
  }

  @Test
  fun noSignedInUser_cannotLoad() {
    auth.user = null

    assertEquals(EditProfileState.CouldNotLoad, viewModel().uiState.value)
  }

  @Test
  fun anotherAccountSigningInDuringTheEdit_isNeverSavedOver() {
    val vm = viewModel()
    vm.section("EL")
    auth.user = user.copy(uid = "other")
    vm.save()
    settle()

    assertTrue(profiles.updatedProfiles.isEmpty())
    assertEquals(EditProfileState.Failure.UNEXPECTED, vm.form().failure)
  }
}
