// Contributors: Claude Opus 5.5 (drafted the My friend code ViewModel tests, #339); Konstantinos
// Perdikeas (directed, reviewed and owns them).
package com.polysocial.ui.profile

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.qr.QrCode
import com.polysocial.model.qr.encodeQrCode
import com.polysocial.model.qr.friendQrPayload
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FriendCodeViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val uid = "fictionalStudentUid01"
  private val auth = FakeAuthRepository(AuthUser(uid, "fictional@epfl.ch", true, "Fictional Auth"))
  private val profiles =
      FakeUserProfileRepository(ProfileResult.Found(profile(displayName = "Léa Martin")))

  private fun profile(
      displayName: String = "Léa Martin",
      section: String = "IN",
      year: String = "BA3",
      accountType: AccountType = AccountType.STUDENT,
  ) = UserProfile(uid, "fictional@epfl.ch", displayName, section, year, accountType)

  private fun shown(vm: FriendCodeViewModel): FriendCodeUiState.Shown {
    val state = vm.uiState.value
    assertTrue("Expected the code, got $state", state is FriendCodeUiState.Shown)
    return state as FriendCodeUiState.Shown
  }

  private fun assertSameModules(expected: QrCode, actual: QrCode) {
    assertEquals(expected.size, actual.size)
    for (y in 0 until expected.size) for (x in 0 until expected.size) {
      assertEquals("module ($x, $y)", expected.isDark(x, y), actual.isDark(x, y))
    }
  }

  @Test
  fun showsTheSignedInStudentsOwnCodeWithTheirNameSectionAndYear() = runTest {
    val vm = FriendCodeViewModel(auth, profiles)
    advanceUntilIdle()

    val state = shown(vm)
    assertSameModules(encodeQrCode(friendQrPayload(uid)!!)!!, state.code)
    assertEquals("Léa Martin", state.name)
    assertEquals("IN · BA3", state.details)
    assertEquals(uid, profiles.lastRequestedUid)
  }

  @Test
  fun theCodeShowsAtOnceWithTheAccountNameWhileTheProfileLoads() = runTest {
    val gate = CompletableDeferred<Unit>()
    profiles.gate = gate
    val vm = FriendCodeViewModel(auth, profiles)
    advanceUntilIdle()

    val loading = shown(vm)
    assertSameModules(encodeQrCode(friendQrPayload(uid)!!)!!, loading.code)
    assertEquals("Fictional Auth", loading.name)
    assertEquals("", loading.details)

    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals("Léa Martin", shown(vm).name)
    assertEquals("IN · BA3", shown(vm).details)
  }

  @Test
  fun aProfileThatCantBeReadOfflineKeepsTheCodeAndTheAccountName() = runTest {
    profiles.getProfileResult = ProfileResult.NetworkError
    val vm = FriendCodeViewModel(auth, profiles)
    advanceUntilIdle()

    val state = shown(vm)
    assertSameModules(encodeQrCode(friendQrPayload(uid)!!)!!, state.code)
    assertEquals("Fictional Auth", state.name)
    assertEquals("", state.details)
  }

  @Test
  fun aMissingProfileOrAnUnexpectedErrorAlsoKeepsTheCode() = runTest {
    for (result in listOf(ProfileResult.NotFound, ProfileResult.UnexpectedError)) {
      profiles.getProfileResult = result
      val vm = FriendCodeViewModel(auth, profiles)
      advanceUntilIdle()
      val state = shown(vm)
      assertSameModules(encodeQrCode(friendQrPayload(uid)!!)!!, state.code)
      assertEquals("Fictional Auth", state.name)
      assertEquals("", state.details)
    }
  }

  @Test
  fun aProfileWithABlankNameKeepsTheAccountName() = runTest {
    profiles.getProfileResult = ProfileResult.Found(profile(displayName = " "))
    val vm = FriendCodeViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals("Fictional Auth", shown(vm).name)
    assertEquals("IN · BA3", shown(vm).details)
  }

  @Test
  fun anAccountWithoutANameShowsAnEmptyNameUntilTheProfileArrives() = runTest {
    auth.user = AuthUser(uid, "fictional@epfl.ch", true, displayName = null)
    profiles.getProfileResult = ProfileResult.NetworkError
    val vm = FriendCodeViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals("", shown(vm).name)
  }

  @Test
  fun aSectionOrYearAloneIsShownWithoutTheSeparator() = runTest {
    profiles.getProfileResult = ProfileResult.Found(profile(section = "IN", year = " "))
    val sectionOnly = FriendCodeViewModel(auth, profiles)
    advanceUntilIdle()
    assertEquals("IN", shown(sectionOnly).details)

    profiles.getProfileResult = ProfileResult.Found(profile(section = "", year = "MA1"))
    val yearOnly = FriendCodeViewModel(auth, profiles)
    advanceUntilIdle()
    assertEquals("MA1", shown(yearOnly).details)
  }

  @Test
  fun noSignedInAccountShowsTheErrorAndReadsNothing() = runTest {
    auth.user = null
    val vm = FriendCodeViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(FriendCodeUiState.CouldNotShow, vm.uiState.value)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun aUidThePayloadRefusesShowsTheErrorInsteadOfACode() = runTest {
    auth.user = AuthUser("not/a-valid uid", "fictional@epfl.ch", true, "Fictional Auth")
    val vm = FriendCodeViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(FriendCodeUiState.CouldNotShow, vm.uiState.value)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun anAssociationNeverGetsAFriendCode() = runTest {
    profiles.getProfileResult =
        ProfileResult.Found(
            profile(displayName = "Fictional Club", accountType = AccountType.ASSOCIATION)
        )
    val vm = FriendCodeViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(FriendCodeUiState.CouldNotShow, vm.uiState.value)
  }
}
