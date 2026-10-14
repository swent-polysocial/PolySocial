// Contributors: OpenAI Codex (logout dismissal, callback replacement and restoration tests, #109).
package com.polysocial.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.*
import com.polysocial.model.firebase.*
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRouteTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val auth = FakeAuthRepository(AuthUser("fictional-student", "fictional@epfl.ch", true))
  private val gate = CompletableDeferred<Unit>()
  private var clears = 0
  private val vm =
      ProfileViewModel(
          auth,
          object : SessionCache {
            override suspend fun clear(): CacheClearResult {
              clears++
              gate.await()
              return CacheClearResult.Cleared
            }
          },
      )

  private fun back() {
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
  }

  @Test
  fun replacingRouteViewModelDoesNotSendLogoutActionsToPreviousAccount() {
    val nextAuth = FakeAuthRepository(AuthUser("next-student", "next.fictional@epfl.ch", true))
    var nextClears = 0
    val next =
        ProfileViewModel(
            nextAuth,
            object : SessionCache {
              override suspend fun clear(): CacheClearResult {
                nextClears++
                return CacheClearResult.Cleared
              }
            },
        )
    val current = mutableStateOf(vm)
    var completed = 0
    compose.setContent { PolySocialTheme { ProfileRoute({ completed++ }, current.value) } }
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    compose.onNodeWithTag(ProfileTags.Dialog).assertIsDisplayed()
    compose.runOnIdle { current.value = next }
    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTags.Logout).assertIsEnabled().performClick()
    compose.onNodeWithTag(ProfileTags.Cancel).performClick()
    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    assertEquals(LogoutState.Confirming, vm.uiState.value)
    assertEquals(LogoutState.Idle, next.uiState.value)
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    assertEquals(1, completed)
    assertEquals(1, nextClears)
    assertEquals(1, nextAuth.logOutCalls)
    assertNull(nextAuth.user)
    assertNotNull(auth.user)
    assertEquals(0, auth.logOutCalls)
    assertEquals(0, clears)
  }

  @Test
  fun restoredConfirmationStillCancelsWithoutClearingTheSession() {
    val restoration = StateRestorationTester(compose)
    restoration.setContent { PolySocialTheme { ProfileRoute({}, vm) } }
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(ProfileTags.Dialog).assertIsDisplayed()
    back()
    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTags.Logout).assertIsEnabled()
    assertNotNull(auth.user)
    assertEquals(0, auth.logOutCalls)
    assertEquals(0, clears)
  }

  @Test
  fun logoutUsesLatestCallbackOnceAfterCleanupEvenWhenRouteRecomposes() {
    var oldCalls = 0
    var latestCalls = 0
    val callback = mutableStateOf<() -> Unit>({ oldCalls++ })
    compose.setContent { PolySocialTheme { ProfileRoute(callback.value, vm) } }
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(ProfileTags.Loading).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTags.Logout).assertIsNotEnabled()
    compose.runOnIdle { callback.value = { latestCalls++ } }
    back()
    compose.onNodeWithTag(ProfileTags.Loading).assertIsDisplayed()
    assertEquals(0, oldCalls)
    assertEquals(0, latestCalls)
    compose.runOnIdle { gate.complete(Unit) }
    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(0, oldCalls)
      assertEquals(1, latestCalls)
      callback.value = { latestCalls += 10 }
    }
    compose.waitForIdle()
    assertEquals(1, latestCalls)
    assertEquals(1, auth.logOutCalls)
    assertNull(auth.user)
  }
}
