// Contributors: OpenAI Codex (cold-start, recreation and verification Back sign-out regression
// tests for #31).
package com.polysocial.auth

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.*
import com.polysocial.model.user.*
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VerificationNavigationTest {
  @get:Rule val compose = createComposeRule()
  private val user = AuthUser("test-uid", "student.test@epfl.ch", false)
  private val repository = FakeAuthRepository(user)
  private val store =
      MemoryVerificationStore().apply { timings[user.uid] = VerificationTiming(1000, 46_000) }
  private val signup = SignUpViewModel(repository)
  private val login = LoginViewModel(repository)
  private var start = AppStartViewModel(repository, FakeUserProfileRepository())
  private val models = mutableListOf<VerifyEmailViewModel>()

  private fun model() =
      VerifyEmailViewModel(repository, store, VerificationClock { 1000 }).also { models.add(it) }

  private var verification = model()

  @After
  fun dispose() {
    ViewModelStore().apply {
      put("signup", signup)
      put("login", login)
      models.forEachIndexed { index, vm -> put("verification$index", vm) }
      clear()
    }
  }

  private fun launch(): StateRestorationTester =
      StateRestorationTester(compose).apply {
        setContent { PolySocialTheme { AuthFlow(signup, {}, login, start, verification) } }
      }

  @Test
  fun backSignsOutAndColdRestartStaysAtWelcome() {
    val restoration = launch()
    compose.onNodeWithTag(VerifyEmailTags.Back).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(repository.currentUser())
    assertEquals(1, repository.logOutCalls)
    start = AppStartViewModel(repository, FakeUserProfileRepository())
    verification = model()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertDoesNotExist()
    assertEquals(0, repository.logInCalls)
    assertEquals(0, repository.sendCalls)
    assertEquals(VerificationTiming(1000, 46_000), store.timings[user.uid])
  }

  @Test
  fun recreationKeepsExplicitBackAtWelcomeWhenViewModelSurvives() {
    val restoration = launch()
    compose.onNodeWithTag(VerifyEmailTags.Back).performClick()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertDoesNotExist()
    assertEquals(1, repository.logOutCalls)
  }

  @Test
  fun recreationDoesNotReplayTheAlreadySentSnackbar() {
    val restoration = launch()
    compose.onNodeWithTag(VerifyEmailTags.Snackbar).assertIsDisplayed()
    compose.runOnIdle { assertNull(verification.uiState.value.snackbar) }
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    compose.runOnIdle { assertNull(verification.uiState.value.snackbar) }
    compose.onNodeWithTag(VerifyEmailTags.Snackbar).onChildren().assertCountEquals(0)
    assertEquals(0, repository.sendCalls)
    compose.onNodeWithTag(VerifyEmailTags.Resend).assertTextContains("0:45", substring = true)
  }

  @Test
  fun changeAddressResetsSignUpAndLoginStateBeforeShowingForm() {
    val restoration = launch()
    signup.update(SignUpField.FullName, "Test Student")
    login.onEmailChange("student.test@epfl.ch")
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    assertEquals(SignUpUiState(), signup.uiState.value)
    assertEquals("", login.uiState.value.email)
    assertNull(repository.currentUser())
    assertEquals(1, repository.logOutCalls)
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
  }
}
