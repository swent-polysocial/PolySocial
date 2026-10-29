// Contributors: OpenAI Codex (association setup persistence, failure and session tests, #125).
package com.polysocial.ui.association

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.CreateAssociationResult
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AssociationSetupViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val user = AuthUser("association-test", "club@example.org", true)
  private val auth = FakeAuthRepository(user)
  private val profiles = FakeUserProfileRepository()
  private val associations = FakeAssociationRepository()

  private fun model(
      profileRepository: UserProfileRepository = profiles,
      associationRepository: AssociationRepository = associations,
      handle: SavedStateHandle = SavedStateHandle(),
  ) = AssociationSetupViewModel(auth, profileRepository, associationRepository, handle)

  private fun fill(viewModel: AssociationSetupViewModel) {
    viewModel.update(AssociationSetupField.Name, " Example Club ")
    viewModel.selectType(AssociationType.CLUB)
    viewModel.update(AssociationSetupField.About, " A fictional club. ")
    viewModel.update(AssociationSetupField.Website, " https://example.org ")
  }

  private fun found(type: AccountType = AccountType.ASSOCIATION) =
      ProfileResult.Found(UserProfile(user.uid, user.email, "Example Club", "", "", type))

  @Test
  fun missingFieldsAreTouchedWithoutCallingEitherRepository() = runTest {
    val viewModel = model()
    viewModel.submit()
    advanceUntilIdle()
    assertEquals(AssociationSetupField.entries.toSet(), viewModel.uiState.value.touched)
    assertEquals(0, profiles.getProfileCalls)
    assertTrue(profiles.createdProfiles.isEmpty())
    assertTrue(associations.createdAssociations.isEmpty())
    assertFalse(viewModel.uiState.value.saving)
  }

  @Test
  fun invalidWebsiteBlocksWrites() = runTest {
    val viewModel = model()
    fill(viewModel)
    viewModel.update(AssociationSetupField.Website, "http://example.org")
    viewModel.submit()
    advanceUntilIdle()
    assertEquals(0, profiles.getProfileCalls)
    assertFalse(viewModel.uiState.value.saved)
  }

  @Test
  fun savesPrivateAssociationAccountBeforeItsPublicIdentity() = runTest {
    val viewModel = model()
    fill(viewModel)
    profiles.createGate = CompletableDeferred()
    viewModel.submit()
    runCurrent()
    assertTrue(viewModel.uiState.value.saving)
    assertTrue(associations.createdAssociations.isEmpty())
    val privateProfile = profiles.createdProfiles.single()
    assertEquals(user.uid, privateProfile.uid)
    assertEquals(user.email, privateProfile.email)
    assertEquals(AccountType.ASSOCIATION, privateProfile.accountType)
    assertEquals("Example Club", privateProfile.displayName)
    assertEquals("", privateProfile.section)
    assertEquals("", privateProfile.year)
    assertFalse(privateProfile.isAssociationVerified)
    profiles.createGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(
        Association(
            user.uid,
            "Example Club",
            AssociationType.CLUB,
            "A fictional club.",
            "https://example.org",
        ),
        associations.createdAssociations.single(),
    )
    assertTrue(viewModel.uiState.value.saved)
    assertFalse(viewModel.uiState.value.saving)
    assertNull(viewModel.uiState.value.error)
  }

  @Test
  fun existingAssociationAccountSkipsPrivateCreate() = runTest {
    profiles.getProfileResult = found()
    val viewModel = model()
    fill(viewModel)
    viewModel.submit()
    advanceUntilIdle()
    assertTrue(profiles.createdProfiles.isEmpty())
    assertEquals(1, associations.createdAssociations.size)
    assertTrue(viewModel.uiState.value.saved)
  }

  @Test
  fun existingStudentAccountIsNeverConverted() = runTest {
    profiles.getProfileResult = found(AccountType.STUDENT)
    val viewModel = model()
    fill(viewModel)
    viewModel.submit()
    advanceUntilIdle()
    assertEquals(AssociationSetupFailure.WrongAccount, viewModel.uiState.value.error)
    assertTrue(profiles.createdProfiles.isEmpty())
    assertTrue(associations.createdAssociations.isEmpty())
  }

  @Test
  fun profileReadFailuresPreserveInputsAndStopBeforeWrites() = runTest {
    for ((result, expected) in
        listOf(
            ProfileResult.NetworkError to AssociationSetupFailure.Network,
            ProfileResult.UnexpectedError to AssociationSetupFailure.Unexpected,
        )) {
      profiles.getProfileResult = result
      val viewModel = model()
      fill(viewModel)
      val original = viewModel.uiState.value.form
      viewModel.submit()
      advanceUntilIdle()
      assertEquals(expected, viewModel.uiState.value.error)
      assertEquals(original, viewModel.uiState.value.form)
      assertFalse(viewModel.uiState.value.saving)
    }
    assertTrue(profiles.createdProfiles.isEmpty())
    assertTrue(associations.createdAssociations.isEmpty())
  }

  @Test
  fun privateCreateFailuresDoNotWriteAnAssociation() = runTest {
    for ((result, expected) in
        listOf(
            CreateProfileResult.NetworkError to AssociationSetupFailure.Network,
            CreateProfileResult.UnexpectedError to AssociationSetupFailure.Unexpected,
        )) {
      profiles.createProfileResult = result
      val viewModel = model()
      fill(viewModel)
      viewModel.submit()
      advanceUntilIdle()
      assertEquals(expected, viewModel.uiState.value.error)
      assertFalse(viewModel.uiState.value.saved)
    }
    assertTrue(associations.createdAssociations.isEmpty())
  }

  @Test
  fun alreadyExistingPrivateAccountIsReadAgainBeforeContinuing() = runTest {
    profiles.createProfileResult = CreateProfileResult.AlreadyExists
    var reads = 0
    val repository =
        object : UserProfileRepository by profiles {
          override suspend fun getProfile(uid: String): ProfileResult =
              if (reads++ == 0) ProfileResult.NotFound else found()
        }
    val viewModel = model(profileRepository = repository)
    fill(viewModel)
    viewModel.submit()
    advanceUntilIdle()
    assertEquals(2, reads)
    assertTrue(viewModel.uiState.value.saved)
    assertEquals(1, associations.createdAssociations.size)
  }

  @Test
  fun collisionWithAStudentOrUnreadablePrivateAccountIsRefused() = runTest {
    profiles.createProfileResult = CreateProfileResult.AlreadyExists
    for ((result, expected) in
        listOf(
            found(AccountType.STUDENT) to AssociationSetupFailure.WrongAccount,
            ProfileResult.NetworkError to AssociationSetupFailure.Network,
            ProfileResult.UnexpectedError to AssociationSetupFailure.Unexpected,
            ProfileResult.NotFound to AssociationSetupFailure.Unexpected,
        )) {
      var reads = 0
      val repository =
          object : UserProfileRepository by profiles {
            override suspend fun getProfile(uid: String): ProfileResult =
                if (reads++ == 0) ProfileResult.NotFound else result
          }
      val viewModel = model(profileRepository = repository)
      fill(viewModel)
      viewModel.submit()
      advanceUntilIdle()
      assertEquals(expected, viewModel.uiState.value.error)
      assertEquals(2, reads)
    }
    assertTrue(associations.createdAssociations.isEmpty())
  }

  @Test
  fun publicCreateFailuresPreserveTheFormAndAllowRetry() = runTest {
    profiles.getProfileResult = found()
    for ((result, expected) in
        listOf(
            CreateAssociationResult.NetworkError to AssociationSetupFailure.Network,
            CreateAssociationResult.UnexpectedError to AssociationSetupFailure.Unexpected,
        )) {
      associations.createAssociationResult = result
      val viewModel = model()
      fill(viewModel)
      val original = viewModel.uiState.value.form
      viewModel.submit()
      advanceUntilIdle()
      assertEquals(expected, viewModel.uiState.value.error)
      assertEquals(original, viewModel.uiState.value.form)
      associations.createAssociationResult = CreateAssociationResult.Created
      viewModel.submit()
      advanceUntilIdle()
      assertTrue(viewModel.uiState.value.saved)
      assertNull(viewModel.uiState.value.error)
    }
  }

  @Test
  fun lostCreateResponseIsRecoveredWithoutUpdatingTheExistingAssociation() = runTest {
    profiles.getProfileResult = found()
    associations.createAssociationResult = CreateAssociationResult.AlreadyExists
    val viewModel = model()
    fill(viewModel)
    viewModel.submit()
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.saved)
    assertTrue(associations.updatedAssociations.isEmpty())
  }

  @Test
  fun unsignedAndUnverifiedSessionsCannotWrite() = runTest {
    for (current in listOf(null, user.copy(isEmailVerified = false))) {
      auth.user = current
      val viewModel = model()
      fill(viewModel)
      viewModel.submit()
      advanceUntilIdle()
      assertEquals(AssociationSetupFailure.NotSignedIn, viewModel.uiState.value.error)
    }
    assertEquals(0, profiles.getProfileCalls)
    assertTrue(profiles.createdProfiles.isEmpty())
    assertTrue(associations.createdAssociations.isEmpty())
  }

  @Test
  fun savingAndSavedStatesIgnoreDuplicateSubmissionAndEditing() = runTest {
    associations.gate = CompletableDeferred()
    val viewModel = model()
    fill(viewModel)
    val original = viewModel.uiState.value.form
    viewModel.submit()
    runCurrent()
    viewModel.submit()
    viewModel.update(AssociationSetupField.Name, "Changed")
    viewModel.selectType(AssociationType.EPFL_UNIT)
    assertEquals(original, viewModel.uiState.value.form)
    assertEquals(1, profiles.createdProfiles.size)
    assertEquals(1, associations.createdAssociations.size)
    associations.gate!!.complete(Unit)
    advanceUntilIdle()
    viewModel.submit()
    viewModel.update(AssociationSetupField.About, "Changed")
    assertEquals(original, viewModel.uiState.value.form)
    assertEquals(1, associations.createdAssociations.size)
  }

  @Test
  fun editingClearsFailureAndTouchesTheEditedField() = runTest {
    profiles.getProfileResult = ProfileResult.NetworkError
    val viewModel = model()
    fill(viewModel)
    viewModel.submit()
    advanceUntilIdle()
    viewModel.update(AssociationSetupField.Name, "New Example Club")
    assertNull(viewModel.uiState.value.error)
    assertTrue(AssociationSetupField.Name in viewModel.uiState.value.touched)
    assertEquals("New Example Club", viewModel.uiState.value.form.name)
    viewModel.update(AssociationSetupField.Type, AssociationType.COMMISSION.name)
    assertEquals(AssociationType.COMMISSION, viewModel.uiState.value.form.type)
    viewModel.update(AssociationSetupField.Type, "unknown")
    assertNull(viewModel.uiState.value.form.type)
  }

  @Test
  fun nonCredentialInputsSurviveModelRecreationForTheSameUid() {
    val handle = SavedStateHandle()
    val first = model(handle = handle)
    fill(first)
    val restored = model(handle = handle)
    assertEquals(first.uiState.value.form, restored.uiState.value.form)
    assertFalse(restored.uiState.value.saving)
    assertFalse(restored.uiState.value.saved)
    assertTrue(handle.keys().none { it.contains("password") })
    assertEquals(user.uid, handle.get<String>("association.uid"))
  }

  @Test
  fun anotherUidOrSignedOutRecreationCannotRestoreThePreviousForm() {
    for (current in listOf(user.copy(uid = "another-test"), null)) {
      auth.user = user
      val handle = SavedStateHandle()
      fill(model(handle = handle))
      auth.user = current
      val next = model(handle = handle)
      assertEquals(AssociationSetupForm(), next.uiState.value.form)
      assertTrue(handle.keys().isEmpty())
    }
  }

  @Test
  fun malformedSavedFieldsAreTreatedAsEmptyAndUnknownTypesAsUnselected() {
    val handle =
        SavedStateHandle(
            mapOf(
                "association.uid" to user.uid,
                "association.name" to 123,
                "association.type" to "unknown",
                "association.about" to null,
                "association.website" to false,
            )
        )
    assertEquals(AssociationSetupForm(), model(handle = handle).uiState.value.form)
  }

  @Test
  fun openingAfterAccountChangeCancelsOldSaveAndResetsAllFormState() = runTest {
    profiles.gate = CompletableDeferred()
    val handle = SavedStateHandle()
    val viewModel = model(handle = handle)
    fill(viewModel)
    viewModel.submit()
    runCurrent()
    auth.user = user.copy(uid = "another-test")
    viewModel.open()
    advanceUntilIdle()
    assertEquals(AssociationSetupUiState(), viewModel.uiState.value)
    assertEquals("another-test", handle.get<String>("association.uid"))
    assertTrue(associations.createdAssociations.isEmpty())
  }

  @Test
  fun editingAfterLogoutDiscardsThePreviousForm() {
    val handle = SavedStateHandle()
    val viewModel = model(handle = handle)
    fill(viewModel)
    auth.user = null
    viewModel.update(AssociationSetupField.Name, "New session")
    assertEquals(AssociationSetupForm(name = "New session"), viewModel.uiState.value.form)
    assertTrue(handle.keys().isEmpty())
  }

  @Test
  fun logoutDuringProfileReadPreventsWrites() = runTest {
    profiles.gate = CompletableDeferred()
    val viewModel = model()
    fill(viewModel)
    viewModel.submit()
    runCurrent()
    auth.user = null
    profiles.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(AssociationSetupFailure.NotSignedIn, viewModel.uiState.value.error)
    assertTrue(profiles.createdProfiles.isEmpty())
    assertTrue(associations.createdAssociations.isEmpty())
  }

  @Test
  fun logoutDuringPrivateCreatePreventsPublicWrite() = runTest {
    profiles.createGate = CompletableDeferred()
    val viewModel = model()
    fill(viewModel)
    viewModel.submit()
    runCurrent()
    auth.user = user.copy(isEmailVerified = false)
    profiles.createGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(AssociationSetupFailure.NotSignedIn, viewModel.uiState.value.error)
    assertTrue(associations.createdAssociations.isEmpty())
  }

  @Test
  fun logoutDuringAlreadyExistsDoesNotReadOrSaveTheNextAccount() = runTest {
    profiles.createProfileResult = CreateProfileResult.AlreadyExists
    profiles.createGate = CompletableDeferred()
    val viewModel = model()
    fill(viewModel)
    viewModel.submit()
    runCurrent()
    auth.user = null
    profiles.createGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(1, profiles.getProfileCalls)
    assertEquals(AssociationSetupFailure.NotSignedIn, viewModel.uiState.value.error)
    assertTrue(associations.createdAssociations.isEmpty())
  }

  @Test
  fun replacementSessionDuringPublicCreateNeverReportsSuccess() = runTest {
    associations.gate = CompletableDeferred()
    val viewModel = model()
    fill(viewModel)
    viewModel.submit()
    runCurrent()
    auth.user = user.copy(uid = "another-test")
    associations.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(AssociationSetupFailure.NotSignedIn, viewModel.uiState.value.error)
    assertFalse(viewModel.uiState.value.saved)
    assertFalse(viewModel.uiState.value.saving)
  }

  @Test
  fun unexpectedRepositoryExceptionsBecomeRecoverableErrors() = runTest {
    val repository =
        object : UserProfileRepository by profiles {
          override suspend fun getProfile(uid: String): ProfileResult =
              throw IllegalStateException("test failure")
        }
    val viewModel = model(profileRepository = repository)
    fill(viewModel)
    val original = viewModel.uiState.value.form
    viewModel.submit()
    advanceUntilIdle()
    assertEquals(AssociationSetupFailure.Unexpected, viewModel.uiState.value.error)
    assertEquals(original, viewModel.uiState.value.form)
    assertFalse(viewModel.uiState.value.saving)
    assertTrue(associations.createdAssociations.isEmpty())
  }

  @Test
  fun repositoryCancellationIsNotConvertedIntoAnErrorAndStopsSaving() = runTest {
    val repository =
        object : UserProfileRepository by profiles {
          override suspend fun getProfile(uid: String): ProfileResult =
              throw CancellationException("test cancellation")
        }
    val viewModel = model(profileRepository = repository)
    fill(viewModel)
    viewModel.submit()
    advanceUntilIdle()
    assertFalse(viewModel.uiState.value.saving)
    assertFalse(viewModel.uiState.value.saved)
    assertNull(viewModel.uiState.value.error)
    assertTrue(associations.createdAssociations.isEmpty())
  }
}
