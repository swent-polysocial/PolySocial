// Contributors: OpenAI Codex (inject Google entry state after #107 rebase).
// Contributors: OpenAI Codex (session loss during router replacement and Profile placeholder in
// shell tests; logout uses replacement verification/profile state and startup router, #109).
// Contributors: OpenAI Codex (verification rerouting regressions from app and loading, #109).
// Contributors: OpenAI Codex (Google ViewModel injection for #107).
// Contributors: OpenAI Codex (tested startup/session routing, profile failure recovery and system
// Back handoffs and verification/profile integration); Claude (signed-in shell without Hilt, #105;
// signed-out back stack, profile step, sign-out and Figma start states, #74; sign-out from
// anywhere and from Verify Email, form resets and rotation cases after review); Claude Opus 5.5
// (testing agent: generic save error tests, #142); Claude (the full name's limit, written
// test-first, #143).
package com.polysocial.auth

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.*
import com.polysocial.model.user.*
import com.polysocial.resources.C
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginScreenTestTags
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.navigation.AppShell
import com.polysocial.ui.navigation.Tab
import com.polysocial.ui.navigation.TabRootScreen
import com.polysocial.ui.profile.ProfileSetupTestTags
import com.polysocial.ui.profile.ProfileSetupUiState
import com.polysocial.ui.profile.ProfileSetupViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.start.StartDestination
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val user = AuthUser("test-uid", "test.student@epfl.ch", true, "Test Student")
  private val auth = FakeAuthRepository()
  private val profiles = FakeUserProfileRepository()
  private val signUp = SignUpViewModel(auth)
  private val login = LoginViewModel(auth)
  private val profileSetup = ProfileSetupViewModel(auth, profiles)
  private val welcome = com.polysocial.ui.auth.WelcomeViewModel(auth)
  private val restoration = StateRestorationTester(compose)

  private var exits = 0
  private val verificationModels = mutableListOf<VerifyEmailViewModel>()

  @After
  fun clearVerification() {
    ViewModelStore().apply {
      verificationModels.forEachIndexed { index, model -> put("verification$index", model) }
      clear()
    }
  }

  private fun verificationModel() =
      VerifyEmailViewModel(auth, MemoryVerificationStore(), VerificationClock { 1000 }).also {
        verificationModels.add(it)
      }

  private lateinit var start: AppStartViewModel

  private fun launch() {
    start = AppStartViewModel(auth, profiles)
    val verification =
        VerifyEmailViewModel(auth, MemoryVerificationStore(), VerificationClock { 1000 }).also {
          verificationModels.add(it)
        }
    // Like the app's, the step's ViewModel is created with the flow and outlives its screen.
    val profileStep = ProfileSetupViewModel(auth, profiles)
    // The real Events tab needs Hilt; these tests only check that the shell opens.
    restoration.setContent {
      PolySocialTheme {
        AuthFlow(
            signUp,
            { exits++ },
            login,
            start,
            verification,
            profileStep,
            welcomeViewModel = welcome,
        ) {
          AppShell(
              eventsContent = { TabRootScreen(Tab.EVENTS) },
              profileContent = { TabRootScreen(Tab.PROFILE) },
          )
        }
      }
    }
  }

  @Test
  fun unverifiedSessionReplacesTheOpenAppAndBackSignsOut() {
    auth.user = user
    profiles.getProfileResult =
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    launch()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    compose.runOnIdle {
      auth.user = user.copy(isEmailVerified = false)
      start.refresh()
    }
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    assertEquals(1, profiles.getProfileCalls)
    pressSystemBack()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(auth.currentUser())
    assertEquals(1, auth.logOutCalls)
    pressSystemBack()
    assertEquals(1, exits)
  }

  @Test
  fun unverifiedReplacementDuringProfileLoadingIgnoresTheLateProfileResult() {
    auth.user = user
    val pendingProfile = CompletableDeferred<Unit>()
    profiles.getProfileResult =
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    val currentStart = mutableStateOf(AppStartViewModel(auth, profiles))
    val verification = verificationModel()
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            signUp,
            { exits++ },
            login,
            currentStart.value,
            verification,
            profileSetup,
            welcomeViewModel = welcome,
        ) {
          Text("Signed in", Modifier.testTag("signed_in"))
        }
      }
    }
    compose.onNodeWithTag("signed_in").assertIsDisplayed()
    compose.runOnIdle {
      profiles.gate = pendingProfile
      currentStart.value.refresh()
    }
    compose.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    compose.runOnIdle {
      auth.user = user.copy(isEmailVerified = false)
      currentStart.value = AppStartViewModel(auth, profiles)
    }
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.loading_state).assertDoesNotExist()
    compose.runOnIdle { pendingProfile.complete(Unit) }
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag("signed_in").assertDoesNotExist()
    assertEquals(2, profiles.getProfileCalls)
    assertEquals(0, auth.logOutCalls)
    assertEquals(false, auth.currentUser()?.isEmailVerified)
  }

  @Test
  fun logoutClearsReplacementVerificationAndProfileState() {
    auth.user = user
    profiles.getProfileResult =
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    val start = AppStartViewModel(auth, profiles)
    val currentVerification = mutableStateOf(verificationModel())
    val currentProfile = mutableStateOf(profileSetup)
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            signUp,
            { exits++ },
            login,
            start,
            currentVerification.value,
            currentProfile.value,
            welcomeViewModel = welcome,
        ) { done ->
          TextButton(
              {
                auth.logOut()
                done()
              },
              Modifier.testTag("logout"),
          ) {
            Text("Log out")
          }
        }
      }
    }
    compose.onNodeWithTag("logout").assertIsDisplayed()
    val replacementVerification = verificationModel()
    val replacementProfile = ProfileSetupViewModel(auth, profiles)
    compose.runOnIdle {
      replacementVerification.enter()
      replacementProfile.onDisplayNameChange("Previous Student")
      replacementProfile.onSectionChange("IN")
      replacementProfile.onYearChange("BA3")
      currentVerification.value = replacementVerification
      currentProfile.value = replacementProfile
    }
    compose.runOnIdle {
      assertEquals(user.email, replacementVerification.uiState.value.email)
      assertEquals("Previous Student", replacementProfile.uiState.value.displayName)
      assertEquals("BA3", replacementProfile.uiState.value.year)
    }
    compose.onNodeWithTag("logout").performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(VerifyEmailUiState(), replacementVerification.uiState.value)
      assertEquals(ProfileSetupUiState(), replacementProfile.uiState.value)
      assertEquals(1, auth.logOutCalls)
    }
    pressSystemBack()
    assertEquals(1, exits)
  }

  @Test
  fun logoutRefreshesReplacementRouterInsteadOfThePreviousOne() {
    auth.user = user
    profiles.getProfileResult =
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    val previous = AppStartViewModel(auth, profiles)
    val current = mutableStateOf(previous)
    val verification = verificationModel()
    restoration.setContent {
      PolySocialTheme {
        AuthFlow(
            signUp,
            { exits++ },
            login,
            current.value,
            verification,
            profileSetup,
            welcomeViewModel = welcome,
        ) { done ->
          TextButton(
              {
                auth.logOut()
                done()
              },
              Modifier.testTag("logout"),
          ) {
            Text("Log out")
          }
        }
      }
    }
    compose.onNodeWithTag("logout").assertIsDisplayed()
    val replacement = AppStartViewModel(auth, profiles)
    compose.runOnIdle { current.value = replacement }
    compose.onNodeWithTag("logout").assertIsDisplayed()
    compose.runOnIdle { assertEquals(StartDestination.Main, replacement.destination.value) }
    compose.onNodeWithTag("logout").performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(StartDestination.Login, replacement.destination.value)
      assertEquals(StartDestination.Main, previous.destination.value)
      assertNull(auth.currentUser())
      assertEquals(1, auth.logOutCalls)
    }
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag("logout").assertDoesNotExist()
    pressSystemBack()
    assertEquals(1, exits)
  }

  @Test
  fun logoutResetsCurrentFormViewModelsAfterTheyAreReplaced() {
    auth.user = user
    profiles.getProfileResult =
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    val start = AppStartViewModel(auth, profiles)
    val verification = verificationModel()
    val currentSignUp = mutableStateOf(signUp)
    val currentLogin = mutableStateOf(login)
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            currentSignUp.value,
            { exits++ },
            currentLogin.value,
            start,
            verification,
            profileSetup,
            welcomeViewModel = welcome,
        ) { done ->
          TextButton(
              {
                auth.logOut()
                done()
              },
              Modifier.testTag("logout_current_forms"),
          ) {
            Text("Log out")
          }
        }
      }
    }
    compose.onNodeWithTag("logout_current_forms").assertIsDisplayed()
    val replacementSignUp = SignUpViewModel(auth)
    val replacementLogin = LoginViewModel(auth)
    replacementSignUp.update(SignUpField.FullName, "Previous Student")
    replacementLogin.onEmailChange(user.email)
    replacementLogin.onPasswordChange("password1")
    compose.runOnIdle {
      currentSignUp.value = replacementSignUp
      currentLogin.value = replacementLogin
    }
    compose.onNodeWithTag("logout_current_forms").performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.FullName))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    compose.onNodeWithTag(SignUpTags.Back).performClick()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose
        .onNodeWithTag(LoginScreenTestTags.EMAIL)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    compose
        .onNodeWithTag(LoginScreenTestTags.PASSWORD)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    assertEquals(1, auth.logOutCalls)
  }

  @Test
  fun losingSessionDuringRouterReplacementLeavesLoadingAndIgnoresTheOldProfileRead() {
    auth.user = user
    profiles.getProfileResult =
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    val start = AppStartViewModel(auth, profiles)
    val verification = verificationModel()
    val currentStart = mutableStateOf(start)
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            signUp,
            { exits++ },
            login,
            currentStart.value,
            verification,
            profileSetup,
            welcomeViewModel = welcome,
        ) {
          Text("Signed in", Modifier.testTag("signed_in"))
        }
      }
    }
    compose.onNodeWithTag("signed_in").assertIsDisplayed()
    val pendingProfile = CompletableDeferred<Unit>()
    compose.runOnIdle {
      profiles.gate = pendingProfile
      start.refresh()
    }
    compose.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    compose.runOnIdle {
      auth.user = null
      currentStart.value = AppStartViewModel(auth, profiles)
    }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.loading_state).assertDoesNotExist()
    compose.runOnIdle { pendingProfile.complete(Unit) }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag("signed_in").assertDoesNotExist()
    pressSystemBack()
    assertEquals(1, exits)
    assertEquals(2, profiles.getProfileCalls)
    assertEquals(null, auth.user)
  }

  @Test
  fun losingSessionWhileShellIsOpenReturnsToWelcomeAndBackExits() {
    auth.user = user
    profiles.getProfileResult =
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    val start = AppStartViewModel(auth, profiles)
    val verification = verificationModel()
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            signUp,
            { exits++ },
            login,
            start,
            verification,
            profileSetup,
            welcomeViewModel = welcome,
        ) {
          AppShell(
              eventsContent = { TabRootScreen(Tab.EVENTS) },
              profileContent = { TabRootScreen(Tab.PROFILE) },
          )
        }
      }
    }
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    compose.runOnIdle {
      auth.user = null
      start.refresh()
    }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    pressSystemBack()
    assertEquals(1, exits)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun refreshDuringSignedOutSignUpPreservesTheActiveForm() {
    val start = AppStartViewModel(auth, profiles)
    val verification = verificationModel()
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            signUp,
            { exits++ },
            login,
            start,
            verification,
            profileSetup,
            welcomeViewModel = welcome,
        ) {}
      }
    }
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performTextInput("Test Student")
    compose.runOnIdle { start.refresh() }
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertTextContains("Test Student")
    assertEquals(0, profiles.getProfileCalls)
    assertEquals(0, auth.signUpCalls)
  }

  @Test
  fun signedOutStartsAtWelcomeAndRestoresTheActiveForm() {
    launch()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performTextInput("Test Student")
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertTextContains("Test Student")
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun signedOutStartNeverShowsTheProfileLoadingState() {
    // The clock is paused, so only the first frame is drawn: a session that needs no profile read
    // must not flash "Loading your profile…" before its screen.
    compose.mainClock.autoAdvance = false
    try {
      launch()
      compose.onNodeWithText(string(R.string.app_start_loading)).assertDoesNotExist()
      compose.onNodeWithTag(WelcomeTags.Screen).assertExists()
    } finally {
      compose.mainClock.autoAdvance = true
    }
  }

  @Test
  fun unverifiedStartNeverShowsTheProfileLoadingState() {
    auth.user = user.copy(isEmailVerified = false)
    compose.mainClock.autoAdvance = false
    try {
      launch()
      compose.onNodeWithText(string(R.string.app_start_loading)).assertDoesNotExist()
      compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertExists()
    } finally {
      compose.mainClock.autoAdvance = true
    }
  }

  @Test
  fun unverifiedSessionStartsAtVerificationWithoutReadingProfile() {
    auth.user = user.copy(isEmailVerified = false)
    launch()
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.Screen).assertDoesNotExist()
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun verifiedSessionWithoutProfileStartsAtProfileSetup() {
    auth.user = user
    launch()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    assertEquals(user.uid, profiles.lastRequestedUid)
  }

  @Test
  fun verifiedSessionWithProfileStartsAtAppShell() {
    auth.user = user
    profiles.getProfileResult =
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    launch()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun pendingProfileShowsLoadingThenErrorAndRetryResolvesAgain() {
    auth.user = user
    profiles.gate = CompletableDeferred()
    profiles.getProfileResult = ProfileResult.NetworkError
    launch()
    compose.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    compose.runOnIdle { profiles.gate!!.complete(Unit) }
    compose.onNodeWithTag(C.Tag.error_state).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    profiles.getProfileResult = ProfileResult.NotFound
    compose.onNodeWithTag(C.Tag.error_retry).performClick()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    assertEquals(2, profiles.getProfileCalls)
  }

  @Test
  fun verifiedLoginRechecksProfileInsteadOfBypassingSetup() {
    auth.logInResult = LogInResult.Success(user)
    launch()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(user.email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    assertEquals(1, auth.logInCalls)
    assertEquals(1, profiles.getProfileCalls)
  }

  private fun completeVerification() {
    auth.verificationResult = VerificationResult.Verified
    compose.onNodeWithTag(VerifyEmailTags.Continue).performScrollTo().performClick()
  }

  @Test
  fun verificationWithExistingProfileOpensTheAppWithoutLoggingIn() {
    auth.user = user.copy(isEmailVerified = false)
    profiles.getProfileResult =
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    launch()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    completeVerification()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertDoesNotExist()
    assertEquals(1, profiles.getProfileCalls)
    assertEquals(user.uid, profiles.lastRequestedUid)
    assertEquals(0, auth.logInCalls)
  }

  @Test
  fun verificationWithoutProfileOpensSetupWithoutLoggingIn() {
    auth.user = user.copy(isEmailVerified = false)
    launch()
    completeVerification()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    assertEquals(1, profiles.getProfileCalls)
    assertEquals(0, auth.logInCalls)
  }

  @Test
  fun verificationWaitsForProfileAndFailedReadCanRetry() {
    auth.user = user.copy(isEmailVerified = false)
    profiles.gate = CompletableDeferred()
    profiles.getProfileResult = ProfileResult.NetworkError
    launch()
    completeVerification()
    compose.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    compose.runOnIdle { profiles.gate!!.complete(Unit) }
    compose.onNodeWithTag(C.Tag.error_state).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    profiles.getProfileResult =
        ProfileResult.Found(UserProfile(user.uid, user.email, "Test Student", "IN", "BA1"))
    compose.onNodeWithTag(C.Tag.error_retry).performClick()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    assertEquals(2, profiles.getProfileCalls)
    assertEquals(0, auth.logInCalls)
  }

  private fun pressSystemBack() {
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
  }

  private fun openSignUpAndSubmit() {
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performTextInput("Test Student")
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).performTextInput(user.email)
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Password)).performTextInput("password1")
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.ConfirmPassword))
        .performTextInput("password1")
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
  }

  private fun openLoginAndSubmit() {
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(user.email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
  }

  @Test
  fun systemBackDuringSignUpPreservesVerificationHandoff() {
    auth.signUpGate = CompletableDeferred()
    auth.signUpResult = SignUpResult.Success(user.copy(isEmailVerified = false))
    launch()
    openSignUpAndSubmit()
    compose.onNodeWithTag(SignUpTags.LoadingSpinner, useUnmergedTree = true).assertIsDisplayed()
    pressSystemBack()
    compose.runOnIdle { auth.signUpGate!!.complete(Unit) }
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.Screen).assertDoesNotExist()
    assertEquals(1, auth.signUpCalls)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun systemBackDuringLoginPreservesProfileHandoff() {
    auth.logInGate = CompletableDeferred()
    auth.logInResult = LogInResult.Success(user)
    launch()
    openLoginAndSubmit()
    compose.onNodeWithTag(LoginScreenTestTags.BACK).assertIsNotEnabled()
    compose.onNodeWithTag(LoginScreenTestTags.CREATE_ACCOUNT).performScrollTo().assertIsNotEnabled()
    pressSystemBack()
    compose.runOnIdle { auth.logInGate!!.complete(Unit) }
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.Screen).assertDoesNotExist()
    assertEquals(1, auth.logInCalls)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun signUpFailureUnlocksBackAndKeepsTheFormForRetry() {
    auth.signUpGate = CompletableDeferred()
    auth.signUpResult = SignUpResult.NetworkError
    launch()
    openSignUpAndSubmit()
    pressSystemBack()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.runOnIdle { auth.signUpGate!!.complete(Unit) }
    compose.onNodeWithTag(SignUpTags.BackendError).performScrollTo().assertIsDisplayed()
    pressSystemBack()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertTextContains("Test Student")
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsEnabled()
    assertEquals(1, auth.signUpCalls)
  }

  @Test
  fun loginFailureUnlocksBackToTheOriginalSignUpForm() {
    auth.logInGate = CompletableDeferred()
    auth.logInResult = LogInResult.WrongCredentials
    launch()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performTextInput("Test Student")
    compose.onNodeWithTag(SignUpTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(user.email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
    pressSystemBack()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.runOnIdle { auth.logInGate!!.complete(Unit) }
    compose.onNodeWithTag(LoginScreenTestTags.ERROR).performScrollTo().assertIsDisplayed()
    pressSystemBack()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertTextContains("Test Student")
    assertEquals(1, auth.logInCalls)
  }

  @Test
  fun unverifiedLoginCannotLeaveDuringDelayedVerificationHandoff() {
    auth.logInGate = CompletableDeferred()
    auth.logInResult = LogInResult.Success(user.copy(isEmailVerified = false))
    launch()
    openLoginAndSubmit()
    compose.mainClock.autoAdvance = false
    try {
      compose.runOnIdle { auth.logInGate!!.complete(Unit) }
      compose.mainClock.advanceTimeBy(100)
      compose.onNodeWithTag(LoginScreenTestTags.UNVERIFIED_BANNER).assertIsDisplayed()
      compose.onNodeWithTag(LoginScreenTestTags.BACK).assertIsNotEnabled()
      compose
          .onNodeWithTag(LoginScreenTestTags.CREATE_ACCOUNT)
          .performScrollTo()
          .assertIsNotEnabled()
      pressSystemBack()
      compose.mainClock.advanceTimeBy(com.polysocial.ui.login.UNVERIFIED_REDIRECT_DELAY_MS)
      compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    } finally {
      compose.mainClock.autoAdvance = true
    }
    assertEquals(1, auth.logInCalls)
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun systemBackFromProfileSetupSignsOutToWelcome() {
    auth.user = user
    launch()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    pressSystemBack()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(0, exits)
    assertEquals(1, auth.logOutCalls)
    assertEquals(null, auth.currentUser())
  }

  @Test
  fun systemBackFromProfileErrorExitsWithoutRetrying() {
    auth.user = user
    profiles.getProfileResult = ProfileResult.NetworkError
    launch()
    compose.onNodeWithTag(C.Tag.error_state).assertIsDisplayed()
    pressSystemBack()
    assertEquals(1, exits)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun systemBackDuringProfileLoadingExitsWithoutStartingAnotherRead() {
    auth.user = user
    profiles.gate = CompletableDeferred()
    launch()
    compose.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    pressSystemBack()
    assertEquals(1, exits)
    assertEquals(1, profiles.getProfileCalls)
    compose.runOnIdle { profiles.gate!!.complete(Unit) }
  }

  @Test
  fun backBetweenSignUpCompletionAndItsEffectStillReachesVerification() {
    auth.signUpGate = CompletableDeferred()
    auth.signUpResult = SignUpResult.Success(user.copy(isEmailVerified = false))
    launch()
    openSignUpAndSubmit()
    compose.mainClock.autoAdvance = false
    try {
      compose.runOnIdle { auth.signUpGate!!.complete(Unit) }
      compose.waitUntil { signUp.uiState.value.status is SignUpStatus.SignedUp }
      pressSystemBack()
      compose.mainClock.advanceTimeBy(100)
      compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    } finally {
      compose.mainClock.autoAdvance = true
    }
    assertEquals(1, auth.signUpCalls)
  }

  // ---- #74: the profile step, sign-out, the signed-out back stack and the start states ----

  private val profile = UserProfile(user.uid, user.email, "Test Student", "IN", "BA3")

  private fun string(id: Int) = compose.activity.getString(id)

  /** The profile step's texts sit in child nodes, so its tags are found in the unmerged tree. */
  private fun profileNode(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

  private fun pick(fieldTag: String, optionTag: String) {
    profileNode(fieldTag).performClick()
    profileNode(optionTag).performScrollTo().performClick()
    compose.waitForIdle()
  }

  private fun completeProfileStep() {
    pick(ProfileSetupTestTags.SECTION, ProfileSetupTestTags.sectionOption("IN"))
    pick(ProfileSetupTestTags.YEAR, ProfileSetupTestTags.yearOption("BA3"))
    profileNode(ProfileSetupTestTags.CONTINUE).performScrollTo().performClick()
  }

  // ---- The full name's limit (#143), through the whole entry flow ----

  @Test
  fun aFullNameOverTheLimitOnSignUp_neverCreatesTheAccount() {
    launch()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.FullName))
        .performTextInput("a".repeat(MAX_DISPLAY_NAME_LENGTH + 1))
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).performTextInput(user.email)
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Password)).performTextInput("password1")
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.ConfirmPassword))
        .performTextInput("password1")

    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()

    compose
        .onNodeWithTag(SignUpTags.error(SignUpField.FullName))
        .performScrollTo()
        .assertTextEquals("Use at most 50 characters")
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertDoesNotExist()
    assertEquals(0, auth.signUpCalls)
  }

  @Test
  fun aPrefilledFullNameOverTheLimit_mustBeShortenedBeforeTheProfileIsSaved() {
    // A verified account whose name (from sign-up before #143, or from Google) is too long.
    auth.user = user.copy(displayName = "b".repeat(MAX_DISPLAY_NAME_LENGTH + 10))
    launch()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()

    pick(ProfileSetupTestTags.SECTION, ProfileSetupTestTags.sectionOption("IN"))
    pick(ProfileSetupTestTags.YEAR, ProfileSetupTestTags.yearOption("BA3"))
    profileNode(ProfileSetupTestTags.NAME_ERROR).assertExists()
    profileNode(ProfileSetupTestTags.CONTINUE).performScrollTo().assertIsNotEnabled()
    assertTrue(profiles.createdProfiles.isEmpty())

    val shortened = "b".repeat(MAX_DISPLAY_NAME_LENGTH)
    profileNode(ProfileSetupTestTags.FULL_NAME).performTextReplacement(shortened)
    profileNode(ProfileSetupTestTags.NAME_ERROR).assertDoesNotExist()
    profileNode(ProfileSetupTestTags.CONTINUE).performScrollTo().performClick()

    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    assertEquals(shortened, profiles.createdProfiles.single().displayName)
  }

  @Test
  fun startStatesShowTheFigmaTextsAndRetry() {
    auth.user = user
    profiles.gate = CompletableDeferred()
    profiles.getProfileResult = ProfileResult.NetworkError
    launch()
    compose.onNodeWithText(string(R.string.app_start_loading)).assertIsDisplayed()
    compose.runOnIdle { profiles.gate!!.complete(Unit) }
    compose.onNodeWithText(string(R.string.app_start_error_title)).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.app_start_error_message)).assertIsDisplayed()
    profiles.getProfileResult = ProfileResult.Found(profile)
    compose.onNodeWithTag(C.Tag.error_retry).assertTextEquals(string(R.string.retry)).performClick()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    assertEquals(2, profiles.getProfileCalls)
  }

  @Test
  fun profileStepContinueOpensTheAppShell() {
    auth.user = user
    launch()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    completeProfileStep()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertDoesNotExist()
    assertEquals(listOf(profile), profiles.createdProfiles)
    // The save proves the profile exists: no second read.
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun profileStepBackArrowSignsOutAndTheNextAccountGetsAFreshStep() {
    auth.user = user
    launch()
    profileNode(ProfileSetupTestTags.FULL_NAME).performTextReplacement("Edited Name")
    profileNode(ProfileSetupTestTags.BACK).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(1, auth.logOutCalls)
    assertNull(auth.currentUser())

    val other = AuthUser("other-uid", "other.student@epfl.ch", true, "Other Student")
    auth.logInResult = LogInResult.Success(other)
    openLoginAndSubmit()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    // A new step for the new account: its name, not the previous account's edit.
    profileNode(ProfileSetupTestTags.FULL_NAME).assertTextEquals("Other Student")
    assertEquals(other.uid, profiles.lastRequestedUid)
  }

  @Test
  fun backIsIgnoredWhileTheProfileIsSaving() {
    auth.user = user
    profiles.createGate = CompletableDeferred()
    launch()
    profiles.getProfileResult = ProfileResult.Found(profile)
    completeProfileStep()
    profileNode(ProfileSetupTestTags.BACK).assertIsNotEnabled()
    pressSystemBack()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    assertEquals(0, auth.logOutCalls)
    compose.runOnIdle { profiles.createGate!!.complete(Unit) }
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    assertEquals(0, exits)
  }

  @Test
  fun createAccountFromLogInReturnsToLogInThenWelcome() {
    launch()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.CREATE_ACCOUNT).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.Back).performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.BACK).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(0, exits)
  }

  @Test
  fun signUpAndLogInLinksDoNotStackUp() {
    launch()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.CREATE_ACCOUNT).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    // Back to Sign up's own previous screen, not to the Log in it just left.
    pressSystemBack()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(0, exits)
  }

  @Test
  fun theSignedOutBackStackSurvivesRecreation() {
    launch()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.CREATE_ACCOUNT).performScrollTo().performClick()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    pressSystemBack()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    pressSystemBack()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
  }

  @Test
  fun backFromVerifyEmailAfterAnUnverifiedLogInDoesNotLoop() {
    auth.logInGate = CompletableDeferred()
    auth.logInResult = LogInResult.Success(user.copy(isEmailVerified = false))
    launch()
    openLoginAndSubmit()
    // Log in shows its "verify your email" banner, whose spinner never lets the clock settle,
    // before redirecting: complete the login and move time on by hand.
    compose.mainClock.autoAdvance = false
    try {
      compose.runOnIdle { auth.logInGate!!.complete(Unit) }
      compose.mainClock.advanceTimeBy(com.polysocial.ui.login.UNVERIFIED_REDIRECT_DELAY_MS + 100)
      compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    } finally {
      compose.mainClock.autoAdvance = true
    }
    pressSystemBack()
    // Back on Verify Email signs out to Welcome (#31). Log in left the stack, so Back from there
    // leaves the app instead of reopening Log in, which would redirect to Verify Email again.
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
    assertNull(auth.currentUser())
    pressSystemBack()
    compose.runOnIdle { assertEquals(1, exits) }
  }

  @Test
  fun backFromTheAppAfterAVerifiedLogInDoesNotReturnToLogIn() {
    auth.logInResult = LogInResult.Success(user)
    profiles.getProfileResult = ProfileResult.Found(profile)
    launch()
    openLoginAndSubmit()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    pressSystemBack()
    compose.runOnIdle { assertTrue(compose.activity.isFinishing) }
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
    assertEquals(0, exits)
  }

  // ---- Found in review: sign-out from anywhere, form resets and rotation ----

  /** An empty text field (its placeholder is not part of its EditableText). */
  private fun hasNoInput() =
      SemanticsMatcher.expectValue(
          androidx.compose.ui.semantics.SemanticsProperties.EditableText,
          androidx.compose.ui.text.AnnotatedString(""),
      )

  @Test
  fun signOutFromTheAppReturnsToWelcomeWithAFreshLogIn() {
    auth.logInResult = LogInResult.Success(user)
    profiles.getProfileResult = ProfileResult.Found(profile)
    launch()
    openLoginAndSubmit()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()

    // What Log out on the Profile tab (#109) will do: only the start routing's logOut().
    compose.runOnIdle { start.logOut() }

    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    // A kept LoggedIn status would have left Log in at once.
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).assert(hasNoInput())
    compose.onNodeWithTag(LoginScreenTestTags.BACK).assertIsEnabled()
    assertEquals(1, auth.logInCalls)
  }

  @Test
  fun signOutResetsTheLogInAndSignUpForms() {
    auth.logInResult = LogInResult.Success(user)
    launch()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).performTextInput("Test Student")
    compose.onNodeWithTag(SignUpTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(user.email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    profileNode(ProfileSetupTestTags.BACK).performClick()

    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).assert(hasNoInput())
    compose.onNodeWithTag(LoginScreenTestTags.BACK).assertIsEnabled()
    compose.onNodeWithTag(LoginScreenTestTags.CREATE_ACCOUNT).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assert(hasNoInput())
    assertEquals(1, auth.logInCalls)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun signOutLeavesAnEmptyBackStack() {
    auth.logInResult = LogInResult.Success(user)
    launch()
    openLoginAndSubmit()
    profileNode(ProfileSetupTestTags.BACK).performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    pressSystemBack()
    // Welcome now uses the host exit callback (MainActivity.finish) for every logout path.
    compose.runOnIdle { assertEquals(1, exits) }
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertDoesNotExist()
  }

  @Test
  fun signOutDuringTheProfileReadShowsWelcomeAndIgnoresTheLateResult() {
    auth.user = user
    profiles.gate = CompletableDeferred()
    profiles.getProfileResult = ProfileResult.Found(profile)
    launch()
    compose.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    compose.runOnIdle { start.logOut() }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.runOnIdle { profiles.gate!!.complete(Unit) }
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.app_shell).assertDoesNotExist()
  }

  @Test
  fun recreationOnVerifyEmailDoesNotLookLikeASignOut() {
    auth.user = user.copy(isEmailVerified = false)
    launch()
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.Screen).assertDoesNotExist()
    assertEquals(0, auth.logOutCalls)
  }

  @Test
  fun signOutFromVerifyEmailAfterASignUpReturnsToAFreshWelcome() {
    auth.signUpResult = SignUpResult.Success(user.copy(isEmailVerified = false))
    launch()
    openSignUpAndSubmit()
    compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()

    // What Back on the real Verify Email screen (#99) does: only the start routing's logOut().
    compose.runOnIdle { start.logOut() }

    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    // A kept SignedUp status would have redirected straight to Verify Email.
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assert(hasNoInput())
    assertEquals(1, auth.signUpCalls)
  }

  @Test
  fun signOutFromVerifyEmailAfterAnUnverifiedLogInReturnsToAFreshLogIn() {
    auth.logInGate = CompletableDeferred()
    auth.logInResult = LogInResult.Success(user.copy(isEmailVerified = false))
    launch()
    openLoginAndSubmit()
    // As in backFromVerifyEmailAfterAnUnverifiedLogInDoesNotLoop: the banner's spinner never lets
    // the clock settle, so time moves on by hand.
    compose.mainClock.autoAdvance = false
    try {
      compose.runOnIdle { auth.logInGate!!.complete(Unit) }
      compose.mainClock.advanceTimeBy(com.polysocial.ui.login.UNVERIFIED_REDIRECT_DELAY_MS + 100)
      compose.onNodeWithTag(SignUpTags.VerifyEmailDestination).assertIsDisplayed()
    } finally {
      compose.mainClock.autoAdvance = true
    }

    compose.runOnIdle { start.logOut() }

    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).assert(hasNoInput())
    assertEquals(1, auth.logInCalls)
  }

  @Test
  fun backAfterAFailedProfileSaveSignsOut() {
    auth.user = user
    profiles.createProfileResult = CreateProfileResult.NetworkError
    launch()
    completeProfileStep()
    profileNode(ProfileSetupTestTags.BACK).assertIsEnabled()
    pressSystemBack()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertEquals(1, auth.logOutCalls)
    assertEquals(0, exits)
  }

  @Test
  fun genericSaveErrorLogOutSignsOutToWelcome() {
    auth.user = user
    profiles.createProfileResult = CreateProfileResult.UnexpectedError
    launch()
    completeProfileStep()
    profileNode(ProfileSetupTestTags.ERROR)
        .assert(hasAnyDescendant(hasText(string(R.string.profile_could_not_save_generic))))
    profileNode(ProfileSetupTestTags.LOG_OUT).performScrollTo().performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertDoesNotExist()
    assertEquals(1, auth.logOutCalls)
    assertNull(auth.currentUser())
    assertEquals(0, exits)
    assertEquals(1, profiles.createdProfiles.size)
  }

  @Test
  fun offlineSaveErrorShowsTheConnectionMessageWithoutLogOut() {
    auth.user = user
    profiles.createProfileResult = CreateProfileResult.NetworkError
    launch()
    completeProfileStep()
    profileNode(ProfileSetupTestTags.ERROR)
        .assert(hasAnyDescendant(hasText(string(R.string.profile_could_not_save))))
    profileNode(ProfileSetupTestTags.LOG_OUT).assertDoesNotExist()
    assertEquals(0, auth.logOutCalls)
  }

  @Test
  fun aSavedProfileOpensTheAppEvenIfAReadWouldNotFindItYet() {
    auth.user = user
    launch()
    // A read right after the save would still find nothing (a stale cache, for example). The app
    // must not depend on it: before, the step came back saved and locked.
    profiles.getProfileResult = ProfileResult.NotFound
    completeProfileStep()
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertDoesNotExist()
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun rotationDuringALogInNavigatesOnce() {
    auth.logInGate = CompletableDeferred()
    auth.logInResult = LogInResult.Success(user)
    launch()
    openLoginAndSubmit()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    compose.runOnIdle { auth.logInGate!!.complete(Unit) }
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertIsDisplayed()
    assertEquals(1, auth.logInCalls)
    assertEquals(1, profiles.getProfileCalls)
  }

  @Test
  fun rotationWhileTheProfileSavesOpensTheAppOnce() {
    auth.user = user
    profiles.createGate = CompletableDeferred()
    launch()
    profileNode(ProfileSetupTestTags.FULL_NAME).performTextReplacement("Edited Name")
    completeProfileStep()
    restoration.emulateSavedInstanceStateRestore()
    profileNode(ProfileSetupTestTags.BACK).assertIsNotEnabled()
    compose.runOnIdle { profiles.createGate!!.complete(Unit) }
    compose.onNodeWithTag(C.Tag.app_shell).assertIsDisplayed()
    assertEquals(1, profiles.getProfileCalls)
    assertEquals("Edited Name", profiles.createdProfiles.single().displayName)
  }

  @Test
  fun aDoubleTapOnSignUpStacksItOnce() {
    launch()
    val signUp = compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().fetchSemanticsNode()
    // A fast double tap: both taps reach Welcome before it is replaced by Sign up.
    compose.runOnIdle {
      repeat(2) {
        signUp.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke()
      }
    }
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    pressSystemBack()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
  }
}
