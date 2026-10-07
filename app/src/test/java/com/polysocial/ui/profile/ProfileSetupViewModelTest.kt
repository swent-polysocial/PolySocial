// Contributors: Claude Opus 5.5 (wrote these tests; unverified account refused); Claude Opus 5.5
// (testing agent: the lock after saving, no signed-in user).
package com.polysocial.ui.profile

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.UserProfile
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileSetupViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val user =
      AuthUser(
          uid = "u1",
          email = "a@epfl.ch",
          isEmailVerified = true,
          displayName = "Test Student",
      )
  private val auth = FakeAuthRepository(user)
  private val profiles = FakeUserProfileRepository()

  private fun viewModel() = ProfileSetupViewModel(auth, profiles)

  private fun ProfileSetupViewModel.fill(section: String = "IN", year: String = "BA3") {
    onSectionChange(section)
    onYearChange(year)
  }

  private fun ProfileSetupViewModel.continueAndSettle() {
    onContinue()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
  }

  private fun ProfileSetupViewModel.status() = uiState.value.status

  @Test
  fun initialState_prefillsTheNameAndVerifiedEmailAndCannotContinue() {
    val state = viewModel().uiState.value

    assertEquals("Test Student", state.displayName)
    assertEquals("a@epfl.ch", state.email)
    assertEquals(null, state.section)
    assertEquals(null, state.year)
    assertEquals(ProfileSetupStatus.Editing, state.status)
    assertFalse(state.canContinue)
  }

  @Test
  fun accountWithoutDisplayName_startsWithAnEmptyName() {
    auth.user = user.copy(displayName = "")

    assertEquals("", viewModel().uiState.value.displayName)
  }

  @Test
  fun continue_createsExactlyOneProfileWithTheUidNameSectionAndYear() {
    val vm = viewModel()
    vm.fill(section = "SC", year = "MA1")

    vm.continueAndSettle()

    assertEquals(
        listOf(
            UserProfile(
                uid = "u1",
                email = "a@epfl.ch",
                displayName = "Test Student",
                section = "SC",
                year = "MA1",
                accountType = AccountType.STUDENT,
            )
        ),
        profiles.createdProfiles,
    )
    assertEquals(ProfileSetupStatus.Saved, vm.status())
  }

  @Test
  fun continue_savesTheEditedNameTrimmed() {
    val vm = viewModel()
    vm.onDisplayNameChange("  New Name ")
    vm.fill()

    vm.continueAndSettle()

    assertEquals("New Name", profiles.createdProfiles.single().displayName)
  }

  @Test
  fun missingSection_blocksContinueBeforeAnyRepositoryCall() {
    val vm = viewModel()
    vm.onYearChange("BA3")

    assertFalse(vm.uiState.value.canContinue)
    vm.continueAndSettle()

    assertTrue(profiles.createdProfiles.isEmpty())
    assertEquals(ProfileSetupStatus.Editing, vm.status())
  }

  @Test
  fun missingYear_blocksContinueBeforeAnyRepositoryCall() {
    val vm = viewModel()
    vm.onSectionChange("IN")

    assertFalse(vm.uiState.value.canContinue)
    vm.continueAndSettle()

    assertTrue(profiles.createdProfiles.isEmpty())
  }

  @Test
  fun blankName_blocksContinueBeforeAnyRepositoryCall() {
    val vm = viewModel()
    vm.fill()
    vm.onDisplayNameChange("   ")

    assertFalse(vm.uiState.value.canContinue)
    vm.continueAndSettle()

    assertTrue(profiles.createdProfiles.isEmpty())
  }

  @Test
  fun sectionAndYear_enableContinue() {
    val vm = viewModel()
    vm.fill()

    assertTrue(vm.uiState.value.canContinue)
  }

  @Test
  fun saving_isShownWhileTheProfileIsWrittenAndBlocksASecondTap() {
    val vm = viewModel()
    vm.fill()
    profiles.createGate = CompletableDeferred()

    vm.continueAndSettle()

    assertEquals(ProfileSetupStatus.Saving, vm.status())
    assertFalse(vm.uiState.value.canContinue)
    vm.continueAndSettle()
    assertEquals(1, profiles.createdProfiles.size)

    profiles.createGate?.complete(Unit)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    assertEquals(ProfileSetupStatus.Saved, vm.status())
  }

  @Test
  fun editing_isIgnoredWhileSaving() {
    val vm = viewModel()
    vm.fill()
    profiles.createGate = CompletableDeferred()
    vm.continueAndSettle()

    vm.onSectionChange("SC")
    vm.onDisplayNameChange("Other")

    assertEquals("IN", vm.uiState.value.section)
    assertEquals("Test Student", vm.uiState.value.displayName)
    assertEquals(ProfileSetupStatus.Saving, vm.status())
  }

  @Test
  fun existingProfile_countsAsSaved() {
    profiles.createProfileResult = CreateProfileResult.AlreadyExists
    val vm = viewModel()
    vm.fill()

    vm.continueAndSettle()

    assertEquals(ProfileSetupStatus.Saved, vm.status())
  }

  @Test
  fun offline_showsCouldNotSaveAndContinueRetries() {
    profiles.createProfileResult = CreateProfileResult.NetworkError
    val vm = viewModel()
    vm.fill()

    vm.continueAndSettle()
    assertEquals(ProfileSetupStatus.CouldNotSave, vm.status())
    assertTrue(vm.uiState.value.canContinue)

    profiles.createProfileResult = CreateProfileResult.Created
    vm.continueAndSettle()

    assertEquals(2, profiles.createdProfiles.size)
    assertEquals(ProfileSetupStatus.Saved, vm.status())
  }

  @Test
  fun unexpectedError_showsCouldNotSave() {
    profiles.createProfileResult = CreateProfileResult.UnexpectedError
    val vm = viewModel()
    vm.fill()

    vm.continueAndSettle()

    assertEquals(ProfileSetupStatus.CouldNotSave, vm.status())
  }

  @Test
  fun editingAfterAFailedSave_clearsTheError() {
    profiles.createProfileResult = CreateProfileResult.NetworkError
    val vm = viewModel()
    vm.fill()
    vm.continueAndSettle()

    vm.onYearChange("BA4")

    assertEquals(ProfileSetupStatus.Editing, vm.status())
    assertEquals("BA4", vm.uiState.value.year)
  }

  @Test
  fun signedOutUser_showsCouldNotSaveWithoutARepositoryCall() {
    val vm = viewModel()
    vm.fill()
    auth.user = null

    vm.continueAndSettle()

    assertTrue(profiles.createdProfiles.isEmpty())
    assertEquals(ProfileSetupStatus.CouldNotSave, vm.status())
  }

  @Test
  fun unverifiedUser_neverGetsAProfile() {
    auth.user = user.copy(isEmailVerified = false)
    val vm = viewModel()
    vm.fill()

    vm.continueAndSettle()

    assertTrue(profiles.createdProfiles.isEmpty())
    assertEquals(ProfileSetupStatus.CouldNotSave, vm.status())
  }

  @Test
  fun afterSaving_continueAndEditsAreIgnored() {
    val vm = viewModel()
    vm.fill()
    vm.continueAndSettle()

    vm.onDisplayNameChange("Other")
    vm.onSectionChange("SC")
    vm.onYearChange("MA1")
    vm.continueAndSettle()

    assertEquals(1, profiles.createdProfiles.size)
    assertEquals(ProfileSetupStatus.Saved, vm.status())
    assertEquals("Test Student", vm.uiState.value.displayName)
    assertEquals("IN", vm.uiState.value.section)
    assertEquals("BA3", vm.uiState.value.year)
  }

  @Test
  fun noSignedInUser_startsWithAnEmptyNameAndEmail() {
    auth.user = null

    val state = viewModel().uiState.value

    assertEquals("", state.displayName)
    assertEquals("", state.email)
  }

  @Test
  fun initialsOf_takesTheFirstAndLastWords() {
    assertEquals("AM", initialsOf("Alex Morel"))
    assertEquals("JD", initialsOf("  jean   paul  doe "))
    assertEquals("A", initialsOf("Ada"))
    assertEquals("", initialsOf("   "))
  }
}
