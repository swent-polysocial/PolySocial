// Contributors: OpenAI Codex (logout dismissal, callback replacement, restoration and full-row
// interaction tests, #109).
package com.polysocial.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.FakeAssociationRepository
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
          FakeAssociationRepository(),
      )

  private fun back() {
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
  }

  @Test
  fun logoutRowShowsIconAndConfirmsFromItsTrailingEdge() {
    compose.setContent { PolySocialTheme { Box(Modifier.width(360.dp)) { ProfileRoute({}, vm) } } }
    val row = compose.onNodeWithTag(ProfileTags.Logout)
    row.assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(50.dp)
    row.assertTextEquals("Log out").assertHasClickAction()
    compose
        .onNodeWithTag(ProfileTags.LogoutIcon, useUnmergedTree = true)
        .assertIsDisplayed()
        .assertWidthIsEqualTo(20.dp)
        .assertHeightIsEqualTo(20.dp)
    // A tap far beyond the text must still open confirmation, without logging out.
    row.performTouchInput { click(Offset(width - 1f, centerY)) }
    compose.onNodeWithTag(ProfileTags.Dialog).assertIsDisplayed()
    assertEquals(0, clears)
    assertEquals(0, auth.logOutCalls)
    compose.onNodeWithTag(ProfileTags.Cancel).performClick()
    row.assertIsEnabled()
    assertNotNull(auth.user)
  }

  @Test
  fun rememberedRouteKeepsConfirmationDuringParentRecompositionAndLogsOutOnce() {
    val revision = mutableStateOf(0)
    var clears = 0
    var completed = 0
    compose.setContent {
      PolySocialTheme {
        val retained = remember {
          ProfileViewModel(
              auth,
              object : SessionCache {
                override suspend fun clear(): CacheClearResult {
                  clears++
                  return CacheClearResult.Cleared
                }
              },
              FakeAssociationRepository(),
          )
        }
        androidx.compose.material3.Text(
            "Parent revision ${revision.value}",
            androidx.compose.ui.Modifier.testTag("parent_revision"),
        )
        ProfileRoute({ completed++ }, retained)
      }
    }
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    compose.runOnIdle { revision.value++ }
    compose.onNodeWithTag("parent_revision").assertTextEquals("Parent revision 1")
    compose.onNodeWithTag(ProfileTags.Dialog).assertIsDisplayed()
    assertEquals(0, clears)
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    compose.runOnIdle { revision.value++ }
    compose.onNodeWithTag("parent_revision").assertTextEquals("Parent revision 2")
    assertEquals(1, completed)
    assertEquals(1, clears)
    assertEquals(1, auth.logOutCalls)
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
            FakeAssociationRepository(),
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
