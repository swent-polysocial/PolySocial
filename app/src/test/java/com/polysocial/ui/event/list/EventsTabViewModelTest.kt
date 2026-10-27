// Contributors: Claude (wrote these tests, #132).
package com.polysocial.ui.event.list

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EventsTabViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val auth = FakeAuthRepository(AuthUser("account", "test@example.com", true))
  private val student =
      UserProfile(
          uid = "account",
          email = "test.student@epfl.ch",
          displayName = "Test Student",
          section = "IN",
          year = "BA3",
      )
  private val association =
      student.copy(email = "test@example.com", accountType = AccountType.ASSOCIATION)
  private val profiles = FakeUserProfileRepository()

  private fun stateFor(result: ProfileResult): EventsTabState {
    profiles.getProfileResult = result
    val viewModel = EventsTabViewModel(auth, profiles)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    return viewModel.state.value
  }

  @Test
  fun aVerifiedAssociation_seesItsOwnEvents() {
    assertEquals(
        EventsTabState.ASSOCIATION,
        stateFor(ProfileResult.Found(association.copy(isAssociationVerified = true))),
    )
    assertEquals("account", profiles.lastRequestedUid)
  }

  @Test
  fun aStudentOrAnUnverifiedAssociation_seesThePublicEvents() {
    assertEquals(EventsTabState.STUDENT, stateFor(ProfileResult.Found(student)))
    assertEquals(EventsTabState.STUDENT, stateFor(ProfileResult.Found(association)))
  }

  @Test
  fun withoutAProfileOrAUser_thePublicEventsAreShown() {
    assertEquals(EventsTabState.STUDENT, stateFor(ProfileResult.NotFound))

    auth.user = null
    assertEquals(EventsTabState.STUDENT, stateFor(ProfileResult.Found(association)))
  }

  @Test
  fun whileTheProfileIsRead_isLoading() {
    profiles.gate = CompletableDeferred()

    val viewModel = EventsTabViewModel(auth, profiles)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    assertEquals(EventsTabState.LOADING, viewModel.state.value)
  }

  @Test
  fun aFailedRead_isAnErrorThatRetryRecoversFrom() {
    profiles.getProfileResult = ProfileResult.NetworkError
    val viewModel = EventsTabViewModel(auth, profiles)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    assertEquals(EventsTabState.ERROR, viewModel.state.value)

    profiles.getProfileResult = ProfileResult.Found(association.copy(isAssociationVerified = true))
    viewModel.retry()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    assertEquals(EventsTabState.ASSOCIATION, viewModel.state.value)
  }

  @Test
  fun anUnexpectedError_isAnErrorAndRetryDoesNothingOnceLoaded() {
    assertEquals(EventsTabState.ERROR, stateFor(ProfileResult.UnexpectedError))

    profiles.getProfileResult = ProfileResult.Found(student)
    val viewModel = EventsTabViewModel(auth, profiles)
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    val calls = profiles.getProfileCalls
    viewModel.retry()

    assertEquals(calls, profiles.getProfileCalls)
    assertEquals(EventsTabState.STUDENT, viewModel.state.value)
  }
}
