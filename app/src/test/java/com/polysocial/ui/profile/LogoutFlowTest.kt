// Contributors: OpenAI Codex (fake-backed logout navigation and account-switch regression tests,
// #109).
package com.polysocial.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.*
import com.polysocial.model.firebase.*
import com.polysocial.model.user.*
import com.polysocial.resources.C
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.*
import com.polysocial.ui.navigation.*
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogoutFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val restoration = StateRestorationTester(compose)
  private val first = AuthUser("student-one", "student.one@epfl.ch", true)
  private val auth = FakeAuthRepository(first)
  private val profiles =
      FakeUserProfileRepository().apply {
        getProfileResult =
            ProfileResult.Found(UserProfile(first.uid, first.email, "Test Student", "IN", "BA1"))
      }
  private val signup = SignUpViewModel(auth)
  private val login = LoginViewModel(auth)
  private var clears = 0
  private var result = CacheClearResult.Cleared
  private var gate: CompletableDeferred<Unit>? = null
  private val cache =
      object : SessionCache {
        override suspend fun clear(): CacheClearResult {
          clears++
          gate?.await()
          return result
        }
      }
  private var exits = 0

  private fun launch() {
    val start = AppStartViewModel(auth, profiles)
    restoration.setContent {
      PolySocialTheme {
        AuthFlow(signup, { exits++ }, login, start) { done ->
          AppShell(
              eventsContent = { TabRootScreen(Tab.EVENTS) },
              mapContent = { TabRootScreen(Tab.MAP) },
              onLoggedOut = done,
              profileContent = { callback ->
                val vm: ProfileViewModel = viewModel { ProfileViewModel(auth, cache) }
                ProfileRoute(callback, vm)
              },
          )
        }
      }
    }
    compose.onNodeWithTag(C.Tag.nav_item_profile).performClick()
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    compose.onNodeWithTag(ProfileTags.Dialog).assertIsDisplayed()
  }

  private fun back() {
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
  }

  @Test
  fun cancellingKeepsProfileAndAccountWithoutCallingLogout() {
    launch()
    compose.onNodeWithTag(ProfileTags.Cancel).performClick()
    compose.onNodeWithTag(C.Tag.screen_profile).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    assertEquals(first, auth.user)
    assertEquals(0, auth.logOutCalls)
    assertEquals(0, clears)
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    back()
    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    assertEquals(first, auth.user)
  }

  @Test
  fun confirmationShowsWelcomeAndBackExitsEvenAfterRestoration() {
    launch()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    assertNull(auth.user)
    assertEquals(1, auth.logOutCalls)
    assertEquals(1, clears)
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    back()
    assertEquals(1, exits)
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
  }

  @Test
  fun cleanupFailureBlocksAccountSwitchAndRetryCompletes() {
    result = CacheClearResult.UnexpectedError
    launch()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(ProfileTags.Error).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTags.Cancel).assertDoesNotExist()
    back()
    compose.onNodeWithTag(ProfileTags.Error).assertIsDisplayed()
    assertEquals(first, auth.user)
    assertEquals(0, auth.logOutCalls)
    result = CacheClearResult.Cleared
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(1, auth.logOutCalls)
    assertEquals(2, clears)
  }

  @Test
  fun cleanupLoadingBlocksActionsUntilComplete() {
    gate = CompletableDeferred()
    launch()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(ProfileTags.Loading).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTags.Confirm).assertIsNotEnabled()
    back()
    assertEquals(first, auth.user)
    compose.runOnIdle { gate!!.complete(Unit) }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
  }

  @Test
  fun sameOrDifferentAccountStartsWithEmptyFormsAndFreshTabs() {
    signup.update(SignUpField.FullName, "Previous Student")
    login.onEmailChange(first.email)
    login.onPasswordChange("password1")
    launch()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose
        .onNodeWithTag(LoginScreenTestTags.EMAIL)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    compose
        .onNodeWithTag(LoginScreenTestTags.PASSWORD)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    compose.onNodeWithTag(LoginScreenTestTags.BACK).performClick()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.FullName))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    compose.onNodeWithTag(SignUpTags.Back).performClick()
    listOf(first, first.copy(uid = "student-two", email = "student.two@epfl.ch")).forEach { next ->
      auth.logInResult = LogInResult.Success(next)
      profiles.getProfileResult =
          ProfileResult.Found(UserProfile(next.uid, next.email, "Test Student", "IN", "BA1"))
      compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
      compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(next.email)
      compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
      compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
      compose.onNodeWithTag(C.Tag.screen_events).assertIsDisplayed()
      assertEquals(next.uid, profiles.lastRequestedUid)
      compose.onNodeWithTag(C.Tag.nav_item_profile).performClick()
      compose.onNodeWithTag(ProfileTags.Logout).performClick()
      compose.onNodeWithTag(ProfileTags.Confirm).performClick()
      compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    }
    assertEquals(3, auth.logOutCalls)
  }
}
