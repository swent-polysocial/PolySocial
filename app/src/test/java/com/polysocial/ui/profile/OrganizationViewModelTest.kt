// Contributors: OpenAI Codex (own-identity caching, required dependencies and session-isolation
// tests, #140).
package com.polysocial.ui.profile

import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.SessionCache
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrganizationViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val user = AuthUser("club", "club@example.org", true)
  private val identity =
      Association("club", "Example Club", AssociationType.CLUB, "Campus activities")
  private val auth = FakeAuthRepository(user)
  private val repository = FakeAssociationRepository(AssociationResult.Found(identity))
  private var cacheResult = CacheClearResult.Cleared
  private val cache =
      object : SessionCache {
        override suspend fun clear() = cacheResult
      }

  @Test
  fun entryLoadsOnlyItsOwnIdentityAndRetryReplacesAnError() = runTest {
    val model = ProfileViewModel(auth, cache, repository)
    assertTrue(repository.requestedUids.isEmpty())
    assertEquals(OrganizationUiState.Loading, model.organization.value)
    for (failure in
        listOf(
            AssociationResult.NotFound,
            AssociationResult.NetworkError,
            AssociationResult.UnexpectedError,
        )) {
      repository.getAssociationResult = failure
      model.loadOrganization()
      advanceUntilIdle()
      assertEquals(OrganizationUiState.Error, model.organization.value)
    }
    repository.getAssociationResult = AssociationResult.Found(identity)
    model.loadOrganization()
    advanceUntilIdle()
    assertEquals(OrganizationUiState.Ready(identity), model.organization.value)
    assertEquals(List(4) { user.uid }, repository.requestedUids)
  }

  @Test
  fun absentOrUnverifiedSessionsNeverQuery() = runTest {
    val model = ProfileViewModel(auth, cache, repository)
    for (session in listOf(null, user.copy(isEmailVerified = false))) {
      auth.user = session
      model.loadOrganization()
      advanceUntilIdle()
      assertEquals(OrganizationUiState.Error, model.organization.value)
    }
    assertTrue(repository.requestedUids.isEmpty())
  }

  @Test
  fun repeatedEntryDoesNotDuplicateAnInflightRead() = runTest {
    repository.gate = CompletableDeferred()
    val model = ProfileViewModel(auth, cache, repository)
    model.loadOrganization()
    runCurrent()
    model.loadOrganization()
    assertEquals(OrganizationUiState.Loading, model.organization.value)
    assertEquals(listOf(user.uid), repository.requestedUids)
    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(OrganizationUiState.Ready(identity), model.organization.value)
  }

  @Test
  fun repeatedEntryRetainsReadyIdentityWithoutAnotherReadOrLoadingState() = runTest {
    val model = ProfileViewModel(auth, cache, repository)
    model.loadOrganization()
    advanceUntilIdle()
    assertEquals(OrganizationUiState.Ready(identity), model.organization.value)
    repository.gate = CompletableDeferred()
    model.loadOrganization()
    assertEquals(OrganizationUiState.Ready(identity), model.organization.value)
    runCurrent()
    assertEquals(OrganizationUiState.Ready(identity), model.organization.value)
    assertEquals(listOf(user.uid), repository.requestedUids)
  }

  @Test
  fun cachedIdentityIsDiscardedWhenTheSessionIsAbsentOrLosesVerification() = runTest {
    for (session in listOf(null, user.copy(isEmailVerified = false))) {
      auth.user = user
      val model = ProfileViewModel(auth, cache, repository)
      model.loadOrganization()
      advanceUntilIdle()
      assertEquals(OrganizationUiState.Ready(identity), model.organization.value)
      val reads = repository.requestedUids.toList()
      auth.user = session
      model.loadOrganization()
      advanceUntilIdle()
      assertEquals(OrganizationUiState.Error, model.organization.value)
      assertEquals(reads, repository.requestedUids)
    }
  }

  @Test
  fun sessionChangeOrLostVerificationCannotDisplayThePreviousResponse() = runTest {
    for (session in listOf(null, user.copy(uid = "other"), user.copy(isEmailVerified = false))) {
      auth.user = user
      repository.gate = CompletableDeferred()
      val model = ProfileViewModel(auth, cache, repository)
      model.loadOrganization()
      runCurrent()
      auth.user = session
      repository.gate!!.complete(Unit)
      advanceUntilIdle()
      assertEquals(OrganizationUiState.Error, model.organization.value)
    }
  }

  @Test
  fun aNewAccountCancelsThePreviousReadAndLoadsItsOwnIdentity() = runTest {
    repository.gate = CompletableDeferred()
    val model = ProfileViewModel(auth, cache, repository)
    model.loadOrganization()
    runCurrent()
    auth.user = user.copy(uid = "new-club")
    model.loadOrganization()
    runCurrent()
    repository.getAssociationResult =
        AssociationResult.Found(identity.copy(uid = "new-club", name = "Other Club"))
    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf("club", "new-club"), repository.requestedUids)
    assertEquals(
        "Other Club",
        (model.organization.value as OrganizationUiState.Ready).association.name,
    )
  }

  @Test
  fun unexpectedAndCancelledReadsShowAnErrorAndAllowRetry() = runTest {
    for (failure in
        listOf(
            IllegalStateException("fixture failure"),
            CancellationException("fixture cancellation"),
        )) {
      val broken =
          object : AssociationRepository by repository {
            override suspend fun getAssociation(uid: String): AssociationResult = throw failure
          }
      val model = ProfileViewModel(auth, cache, broken)
      model.loadOrganization()
      advanceUntilIdle()
      assertEquals(OrganizationUiState.Error, model.organization.value)
      assertNotEquals(LogoutState.LoggedOut, model.uiState.value)
    }
  }

  @Test
  fun successfulLogoutCancelsAnInflightFirstReadAndBlocksLateResponses() = runTest {
    repository.gate = CompletableDeferred()
    val model = ProfileViewModel(auth, cache, repository)
    model.loadOrganization()
    runCurrent()
    assertEquals(OrganizationUiState.Loading, model.organization.value)
    assertEquals(listOf(user.uid), repository.requestedUids)
    model.requestLogout()
    model.confirmLogout()
    advanceUntilIdle()
    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertNull(auth.currentUser())
    assertEquals(LogoutState.LoggedOut, model.uiState.value)
    assertEquals(OrganizationUiState.Loading, model.organization.value)
  }

  @Test
  fun cleanupFailurePreservesTheSessionAndMetadataUntilRetrySucceeds() = runTest {
    val model = ProfileViewModel(auth, cache, repository)
    model.loadOrganization()
    advanceUntilIdle()
    cacheResult = CacheClearResult.UnexpectedError
    model.requestLogout()
    model.confirmLogout()
    advanceUntilIdle()
    model.cancelLogout()
    assertEquals(LogoutState.Error, model.uiState.value)
    assertEquals(user, auth.currentUser())
    assertEquals(OrganizationUiState.Ready(identity), model.organization.value)
    cacheResult = CacheClearResult.Cleared
    model.confirmLogout()
    advanceUntilIdle()
    assertNull(auth.currentUser())
    assertEquals(OrganizationUiState.Loading, model.organization.value)
  }
}
