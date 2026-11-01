// Contributors: OpenAI Codex (cold-start, recreation and verification Back sign-out regression
// tests for #31).
// Contributors: OpenAI Codex (preserved offline ViewModel injection after PR #168 rebase).
// Contributors: Claude (Opus 5.5, Anthropic) passed a fake session guard to app start, #198;
// Mohamed Khellaf (reviewed and revised).
// Contributors: Claude (Opus 5.5, Anthropic) covered leaving through the account deletion, #225;
// Mohamed Khellaf (reviewed and revised).
package com.polysocial.auth

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.account.AccountDeletionResult
import com.polysocial.model.account.FakeAccountDeletionRepository
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.SignUpField
import com.polysocial.model.auth.SignUpForm
import com.polysocial.model.auth.VerificationClock
import com.polysocial.model.auth.VerificationTiming
import com.polysocial.model.firebase.FakeSessionGuard
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.ui.auth.AuthFlow
import com.polysocial.ui.auth.SignUpTags
import com.polysocial.ui.auth.SignUpUiState
import com.polysocial.ui.auth.SignUpViewModel
import com.polysocial.ui.auth.VerifyEmailTags
import com.polysocial.ui.auth.VerifyEmailViewModel
import com.polysocial.ui.auth.WelcomeTags
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.profile.ProfileSetupViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
  private val signup = SignUpViewModel(repository, EmptyAssociationSignupStore)
  private val login = LoginViewModel(repository)
  private var start =
      AppStartViewModel(
          repository,
          FakeUserProfileRepository(),
          associationSignup = EmptyAssociationSignupStore,
          associations = FakeAssociationRepository(),
          sessions = FakeSessionGuard(),
      )
  private val welcome = com.polysocial.ui.auth.WelcomeViewModel(repository)
  private val profile =
      com.polysocial.ui.profile.ProfileSetupViewModel(repository, FakeUserProfileRepository())
  private val models = mutableListOf<VerifyEmailViewModel>()
  private val deletion = FakeAccountDeletionRepository()
  private val network = FakeNetworkMonitor()

  private fun model() =
      VerifyEmailViewModel(repository, store, VerificationClock { 1000 }, deletion, network).also {
        models.add(it)
      }

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

  private fun launch(): StateRestorationTester {
    // Built outside the composition, like the app's: it outlives its screen.
    val profileStep = ProfileSetupViewModel(repository, FakeUserProfileRepository())
    return StateRestorationTester(compose).apply {
      setContent {
        PolySocialTheme {
          AuthFlow(
              signup,
              {},
              login,
              start,
              verification,
              profileStep,
              welcomeViewModel = welcome,
          )
        }
      }
    }
  }

  @Test
  fun backSignsOutAndColdRestartStaysAtWelcome() {
    val restoration = launch()
    compose.onNodeWithTag(VerifyEmailTags.Back).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(repository.currentUser())
    assertEquals(1, repository.logOutCalls)
    start =
        AppStartViewModel(
            repository,
            FakeUserProfileRepository(),
            associationSignup = EmptyAssociationSignupStore,
            associations = FakeAssociationRepository(),
            sessions = FakeSessionGuard(),
        )
    verification = model()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertDoesNotExist()
    assertEquals(0, repository.logInCalls)
    assertEquals(0, repository.sendCalls)
    // The account was deleted before signing out (#225), so its own timing is gone.
    assertEquals(1, deletion.calls)
    assertNull(store.timings[user.uid])
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
    // Only the address of the deleted account comes back, to fix a typo in place (#225).
    assertEquals(SignUpUiState(form = SignUpForm(email = user.email)), signup.uiState.value)
    assertEquals("", login.uiState.value.email)
    assertNull(repository.currentUser())
    assertEquals(1, repository.logOutCalls)
    assertEquals(1, deletion.calls)
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
  }

  @Test
  fun changeAddressStaysOnVerifyEmailWhileTheDeletionRuns() {
    deletion.gate = CompletableDeferred()
    launch()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).performClick()
    compose.onNodeWithTag(VerifyEmailTags.Leaving).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Back).assertIsNotEnabled()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).assertIsNotEnabled()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    assertEquals(user, repository.currentUser())
    assertEquals(0, repository.logOutCalls)

    deletion.gate!!.complete(Unit)
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    assertEquals(1, deletion.calls)
    assertEquals(1, repository.logOutCalls)
  }

  @Test
  fun offlineChangeAddressKeepsTheAccountUntilRetryOnline() {
    network.online = false
    launch()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).performClick()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    compose
        .onNodeWithTag(VerifyEmailTags.Banner)
        .assertTextContains("Can't connect", substring = true)
    assertEquals(0, deletion.calls)
    assertEquals(user, repository.currentUser())

    network.online = true
    compose.onNodeWithTag(VerifyEmailTags.Retry).performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    assertEquals(user.email, signup.uiState.value.form.email)
    assertEquals(1, deletion.calls)
  }

  @Test
  fun failedDeletionOnBackKeepsTheUserOnVerifyEmailWithRetry() {
    deletion.result = AccountDeletionResult.NetworkError
    launch()
    compose.onNodeWithTag(VerifyEmailTags.Back).performClick()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    compose
        .onNodeWithTag(VerifyEmailTags.Banner)
        .assertTextContains("Couldn't remove this address", substring = true)
    assertEquals(0, repository.logOutCalls)
    assertEquals(VerificationTiming(1000, 46_000), store.timings[user.uid])

    deletion.result = AccountDeletionResult.Deleted
    compose.onNodeWithTag(VerifyEmailTags.Retry).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(2, deletion.calls)
    assertNull(repository.currentUser())
  }

  @Test
  fun recentLoginRequiredOnBackOnlySignsOutAndKeepsTheCooldown() {
    deletion.result = AccountDeletionResult.RecentLoginRequired
    launch()
    compose.onNodeWithTag(VerifyEmailTags.Back).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(repository.currentUser())
    assertEquals(1, repository.logOutCalls)
    assertEquals(VerificationTiming(1000, 46_000), store.timings[user.uid])
  }
}
