// Contributors: Claude (wrote these tests, #127; one session after another, after a review,
// #127).
package com.polysocial.ui.association

import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Fictional association used by the pending association tests (#127). */
internal val testClub =
    Association(
        uid = "club-uid",
        name = "Test Club",
        type = AssociationType.CLUB,
        about = "A fictional club for tests.",
        website = "https://example.org",
    )

internal val clubUser = AuthUser("club-uid", "club@example.org", isEmailVerified = true)

class PendingAssociationViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val auth = FakeAuthRepository(clubUser)
  private val associations = FakeAssociationRepository(AssociationResult.Found(testClub))

  @Test
  fun readsTheSignedInAssociationOnce() = runTest {
    val viewModel = PendingAssociationViewModel(auth, associations)
    assertEquals(PendingAssociationUiState.Loading, viewModel.uiState.value)

    advanceUntilIdle()

    assertEquals(PendingAssociationUiState.Loaded(testClub), viewModel.uiState.value)
    assertEquals(listOf("club-uid"), associations.requestedUids)
  }

  @Test
  fun everyFailedRead_fails_andRetryReadsAgain() = runTest {
    for (failure in
        listOf(
            AssociationResult.NotFound,
            AssociationResult.NetworkError,
            AssociationResult.UnexpectedError,
        )) {
      associations.getAssociationResult = failure
      associations.requestedUids.clear()
      val viewModel = PendingAssociationViewModel(auth, associations)
      advanceUntilIdle()
      assertEquals("After $failure", PendingAssociationUiState.Failed, viewModel.uiState.value)

      associations.getAssociationResult = AssociationResult.Found(testClub)
      viewModel.retry()
      assertEquals(PendingAssociationUiState.Loading, viewModel.uiState.value)
      advanceUntilIdle()

      assertEquals(PendingAssociationUiState.Loaded(testClub), viewModel.uiState.value)
      assertEquals(listOf("club-uid", "club-uid"), associations.requestedUids)
    }
  }

  @Test
  fun retry_isIgnoredWhileLoadingAndOnceLoaded() = runTest {
    val gate = CompletableDeferred<Unit>()
    associations.gate = gate
    val viewModel = PendingAssociationViewModel(auth, associations)
    advanceUntilIdle()

    viewModel.retry()
    gate.complete(Unit)
    advanceUntilIdle()
    viewModel.retry()
    advanceUntilIdle()

    assertEquals(listOf("club-uid"), associations.requestedUids)
    assertEquals(PendingAssociationUiState.Loaded(testClub), viewModel.uiState.value)
  }

  @Test
  fun signedOut_failsWithoutReading() = runTest {
    auth.user = null

    val viewModel = PendingAssociationViewModel(auth, associations)
    advanceUntilIdle()

    assertEquals(PendingAssociationUiState.Failed, viewModel.uiState.value)
    assertEquals(emptyList<String>(), associations.requestedUids)
  }

  // --- One session after another (the ViewModel outlives a session) ---

  @Test
  fun open_forTheSameAccount_readsNothingAgain() = runTest {
    val viewModel = PendingAssociationViewModel(auth, associations)
    advanceUntilIdle()

    viewModel.open()
    advanceUntilIdle()

    assertEquals(listOf("club-uid"), associations.requestedUids)
  }

  @Test
  fun open_forAnotherAccount_readsItsOwnDetails() = runTest {
    val viewModel = PendingAssociationViewModel(auth, associations)
    advanceUntilIdle()
    val other = testClub.copy(uid = "other-uid", name = "Other Club")
    auth.user = clubUser.copy(uid = "other-uid")
    associations.getAssociationResult = AssociationResult.Found(other)

    viewModel.open()
    assertEquals(PendingAssociationUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()

    assertEquals(PendingAssociationUiState.Loaded(other), viewModel.uiState.value)
    assertEquals(listOf("club-uid", "other-uid"), associations.requestedUids)
  }

  @Test
  fun close_forgetsTheDetails_andTheNextOpenReadsThemAgain() = runTest {
    val viewModel = PendingAssociationViewModel(auth, associations)
    advanceUntilIdle()

    viewModel.close()
    assertEquals(PendingAssociationUiState.Loading, viewModel.uiState.value)
    viewModel.open()
    advanceUntilIdle()

    assertEquals(PendingAssociationUiState.Loaded(testClub), viewModel.uiState.value)
    assertEquals(2, associations.requestedUids.size)
  }

  @Test
  fun aReadStillRunningAtLogOut_cantShowItsDetailsAfterwards() = runTest {
    val gate = CompletableDeferred<Unit>()
    associations.gate = gate
    val viewModel = PendingAssociationViewModel(auth, associations)
    advanceUntilIdle()

    viewModel.close()
    gate.complete(Unit)
    advanceUntilIdle()

    assertEquals(PendingAssociationUiState.Loading, viewModel.uiState.value)
  }

  @Test
  fun anOldAccountsReadFinishingLate_cantReplaceTheNewAccountsDetails() = runTest {
    // Each account's read waits for its own gate, so the old one can finish last.
    val other = testClub.copy(uid = "other-uid", name = "Other Club")
    val gates =
        mapOf("club-uid" to CompletableDeferred<Unit>(), "other-uid" to CompletableDeferred())
    val perAccount =
        object : AssociationRepository by associations {
          override suspend fun getAssociation(uid: String): AssociationResult {
            gates.getValue(uid).await()
            return AssociationResult.Found(if (uid == "other-uid") other else testClub)
          }
        }
    val viewModel = PendingAssociationViewModel(auth, perAccount)
    advanceUntilIdle()
    auth.user = clubUser.copy(uid = "other-uid")
    viewModel.open()
    advanceUntilIdle()

    gates.getValue("other-uid").complete(Unit)
    advanceUntilIdle()
    gates.getValue("club-uid").complete(Unit)
    advanceUntilIdle()

    assertEquals(PendingAssociationUiState.Loaded(other), viewModel.uiState.value)
  }
}
