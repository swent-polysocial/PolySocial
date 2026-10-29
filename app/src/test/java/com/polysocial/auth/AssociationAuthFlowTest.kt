// Contributors: OpenAI Codex (association onboarding, recreation and session-boundary UI tests,
// #125).
package com.polysocial.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.*
import com.polysocial.model.user.*
import com.polysocial.ui.auth.*
import com.polysocial.ui.login.LoginScreenTestTags
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.profile.ProfileSetupTestTags
import com.polysocial.ui.profile.ProfileSetupViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssociationAuthFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val user = AuthUser("association-uid", "club@example.org", false)
  private val auth = FakeAuthRepository()
  private val profiles = FakeUserProfileRepository()
  private val associations = FakeAssociationRepository()
  private val signupStore =
      object : AssociationSignupStore {
        val emails = mutableSetOf<String>()

        override suspend fun remember(email: String) {
          emails += email.trim().lowercase()
        }

        override suspend fun contains(email: String) = email.trim().lowercase() in emails

        override suspend fun clear(email: String) {
          emails -= email.trim().lowercase()
        }
      }
  private val signup = SignUpViewModel(auth, signupStore)
  private val login = LoginViewModel(auth)
  private val welcome = WelcomeViewModel(auth)
  private val profileSetup = ProfileSetupViewModel(auth, profiles)
  private val verification =
      VerifyEmailViewModel(auth, MemoryVerificationStore(), VerificationClock { 1000 })
  private val restoration = StateRestorationTester(compose)
  private lateinit var start: AppStartViewModel
  private var setupCompletions = 0
  private var exits = 0

  @After
  fun disposeModels() {
    ViewModelStore().apply {
      put("welcome", welcome)
      put("signup", signup)
      put("login", login)
      put("profile", profileSetup)
      put("verification", verification)
      if (::start.isInitialized) put("start", start)
      clear()
    }
  }

  private fun associationExists(verified: Boolean = false) {
    val current = requireNotNull(auth.user)
    profiles.getProfileResult =
        ProfileResult.Found(
            UserProfile(
                current.uid,
                current.email,
                "Test Club",
                "",
                "",
                AccountType.ASSOCIATION,
                verified,
            )
        )
    associations.getAssociationResult =
        AssociationResult.Found(
            Association(current.uid, "Test Club", AssociationType.CLUB, "Campus activities")
        )
  }

  private fun launch() {
    start = AppStartViewModel(auth, profiles, signupStore, associations)
    restoration.setContent {
      PolySocialTheme {
        AuthFlow(
            viewModel = signup,
            onExit = { exits++ },
            loginViewModel = login,
            welcomeViewModel = welcome,
            startViewModel = start,
            verificationViewModel = verification,
            profileSetupViewModel = profileSetup,
            associationSetupContent = { back, saved ->
              Column(Modifier.testTag("association_setup_handoff")) {
                TextButton(back, Modifier.testTag("association_setup_back")) { Text("Back") }
                TextButton(
                    {
                      associationExists()
                      setupCompletions++
                      saved()
                    },
                    Modifier.testTag("association_setup_saved"),
                ) {
                  Text("Save setup")
                }
              }
            },
            associationContent = { verified, done ->
              Column(Modifier.testTag("association_destination")) {
                Text(
                    if (verified) "Approved association" else "Association handoff",
                    Modifier.testTag("association_destination_kind"),
                )
                TextButton(
                    {
                      auth.logOut()
                      done()
                    },
                    Modifier.testTag("association_logout"),
                ) {
                  Text("Log out")
                }
              }
            },
            pendingAssociationContent = { done ->
              Column(Modifier.testTag("association_destination")) {
                Text("Association handoff", Modifier.testTag("association_destination_kind"))
                TextButton(
                    {
                      auth.logOut()
                      done()
                    },
                    Modifier.testTag("association_logout"),
                ) {
                  Text("Log out")
                }
              }
            },
            signedInContent = { Text("Student app", Modifier.testTag("student_destination")) },
        )
      }
    }
  }

  private fun openAssociationForm() {
    compose.onNodeWithTag(WelcomeTags.Association).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.Google).assertDoesNotExist()
  }

  private fun enterAssociationCredentials() {
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).performTextInput(user.email)
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Password)).performTextInput("password1")
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.ConfirmPassword))
        .performScrollTo()
        .performTextInput("password1")
  }

  @Test
  fun welcomeToAssociationAccountVerificationSetupAndAssociationHandoffUsesSharedFlow() {
    auth.signUpResult = SignUpResult.Success(user)
    launch()
    openAssociationForm()
    enterAssociationCredentials()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.Email).assertTextContains(user.email)
    assertEquals(AccountType.ASSOCIATION, auth.lastSignUpAccountType)
    assertEquals(user.email, auth.lastSignUpEmail)
    assertEquals(0, profiles.getProfileCalls)
    compose.runOnIdle { auth.verificationResult = VerificationResult.Verified }
    compose.onNodeWithTag(VerifyEmailTags.Continue).performScrollTo().performClick()
    compose.onNodeWithTag("association_setup_handoff").assertIsDisplayed()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertDoesNotExist()
    compose.onNodeWithTag("association_setup_saved").performClick()
    compose.onNodeWithTag("association_destination").assertIsDisplayed()
    compose.onNodeWithTag("association_destination_kind").assertTextEquals("Association handoff")
    compose.onNodeWithTag("student_destination").assertDoesNotExist()
    assertEquals(1, setupCompletions)
    assertEquals(listOf(user.uid), associations.requestedUids)
    assertEquals(1, auth.signUpCalls)
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag("association_destination").assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.Screen).assertDoesNotExist()
  }

  @Test
  fun unverifiedAssociationLoginChangingAddressKeepsAssociationForm() {
    val returning = user.copy(email = "club@epfl.ch")
    signupStore.emails += returning.email
    auth.logInResult = com.polysocial.model.auth.LogInResult.Success(returning)
    launch()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(returning.email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.UNVERIFIED_BANNER).assertIsDisplayed()
    compose.mainClock.advanceTimeBy(com.polysocial.ui.login.UNVERIFIED_REDIRECT_DELAY_MS)
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertDoesNotExist()
    assertEquals(AccountType.ASSOCIATION, signup.uiState.value.form.accountType)
    assertNull(auth.user)
  }

  @Test
  fun restartedUnverifiedEpflAssociationChangingAddressKeepsAssociationForm() {
    auth.user = user.copy(email = "club@epfl.ch")
    signupStore.emails += "club@epfl.ch"
    launch()
    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    assertEquals(AccountType.STUDENT, signup.uiState.value.form.accountType)
    assertEquals(AccountType.ASSOCIATION, start.accountType.value)
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).performClick()
    compose.onNodeWithTag(SignUpTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.Google).assertDoesNotExist()
    assertEquals(AccountType.ASSOCIATION, signup.uiState.value.form.accountType)
    assertNull(auth.user)
    assertEquals(1, auth.logOutCalls)
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertDoesNotExist()
    pressBack()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertIsDisplayed()
    compose.onNodeWithTag(SignUpTags.Google).assertExists()
    assertEquals(AccountType.STUDENT, signup.uiState.value.form.accountType)
  }

  @Test
  fun recreationAndLoginBackKeepAssociationFormUntilStudentSignupIsChosen() {
    launch()
    openAssociationForm()
    enterAssociationCredentials()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).assertTextContains(user.email)
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.SCREEN).assertIsDisplayed()
    pressBack()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).assertTextContains(user.email)
    assertEquals(AccountType.ASSOCIATION, signup.uiState.value.form.accountType)
    pressBack()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertIsDisplayed()
    assertEquals("", signup.uiState.value.form.email)
    assertEquals("", signup.uiState.value.form.password)
    assertEquals(AccountType.STUDENT, signup.uiState.value.form.accountType)
    assertEquals(0, auth.signUpCalls)
  }

  @Test
  fun associationLogoutReturnsToWelcomeAndCannotRestorePreviousCredentials() {
    auth.user = user.copy(isEmailVerified = true)
    associationExists(verified = true)
    signup.reset(AccountType.ASSOCIATION)
    signup.update(SignUpField.Email, "previous@example.org")
    signup.update(SignUpField.Password, "previous1")
    login.onEmailChange("previous@example.org")
    login.onPasswordChange("previous1")
    launch()
    compose.onNodeWithTag("association_destination_kind").assertTextEquals("Approved association")
    compose.onNodeWithTag("association_logout").performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(auth.user)
    assertEquals("", signup.uiState.value.form.email)
    assertEquals("", signup.uiState.value.form.password)
    assertEquals(AccountType.STUDENT, signup.uiState.value.form.accountType)
    assertEquals("", login.uiState.value.email)
    assertEquals("", login.uiState.value.password)
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag("association_destination").assertDoesNotExist()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    pressBack()
    assertEquals(1, exits)
  }

  @Test
  fun backFromResumedAssociationSetupSignsOutAndLeavesNoStudentBackStack() {
    auth.user = user.copy(isEmailVerified = true)
    signupStore.emails += user.email
    launch()
    compose.onNodeWithTag("association_setup_handoff").assertIsDisplayed()
    pressBack()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(auth.user)
    assertEquals(1, auth.logOutCalls)
    compose.onNodeWithTag("student_destination").assertDoesNotExist()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    pressBack()
    assertEquals(1, exits)
  }

  @Test
  fun changingAddressDuringOriginalSignupKeepsAssociationInputs() {
    auth.signUpResult = SignUpResult.Success(user)
    launch()
    openAssociationForm()
    enterAssociationCredentials()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertDoesNotExist()
    compose.onNodeWithTag(SignUpTags.Google).assertDoesNotExist()
    assertEquals(AccountType.ASSOCIATION, signup.uiState.value.form.accountType)
    assertNull(auth.user)
  }

  @Test
  fun malformedAssociationEmailShowsItsInlineErrorAndPreventsCreation() {
    launch()
    openAssociationForm()
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.Email))
        .performTextInput("invalid.example.org")
    compose.onNodeWithTag(SignUpTags.error(SignUpField.Email)).assertIsDisplayed()
    compose.onNodeWithText("Enter a valid email address.").assertExists()
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().assertIsNotEnabled()
    assertEquals(0, auth.signUpCalls)
  }

  @Test
  fun reopeningTheSameAssociationPathRetainsItsForm() {
    launch()
    openAssociationForm()
    enterAssociationCredentials()
    pressBack()
    openAssociationForm()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).assertTextContains(user.email)
    assertEquals("password1", signup.uiState.value.form.password)
    assertEquals(AccountType.ASSOCIATION, signup.uiState.value.form.accountType)
  }

  @Test
  fun explicitSetupBackSignsOutAndReturnsToWelcome() {
    auth.user = user.copy(isEmailVerified = true)
    signupStore.emails += user.email
    launch()
    compose.onNodeWithTag("association_setup_back").performClick()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    assertNull(auth.user)
    assertEquals(1, auth.logOutCalls)
  }

  @Test
  fun choosingStudentSignupAfterAssociationCorrectionResetsVerificationAddressMode() {
    auth.user = user.copy(email = "club@epfl.ch")
    signupStore.emails += "club@epfl.ch"
    launch()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).performClick()
    pressBack()
    compose.onNodeWithTag(WelcomeTags.SignUp).performScrollTo().performClick()
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.FullName))
        .performTextInput("Example Student")
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Email)).performTextInput("student@epfl.ch")
    compose.onNodeWithTag(SignUpTags.input(SignUpField.Password)).performTextInput("password1")
    compose
        .onNodeWithTag(SignUpTags.input(SignUpField.ConfirmPassword))
        .performScrollTo()
        .performTextInput("password1")
    compose.runOnIdle {
      auth.signUpResult = SignUpResult.Success(AuthUser("student", "student@epfl.ch", false))
    }
    compose.onNodeWithTag(SignUpTags.Submit).performScrollTo().performClick()
    compose.onNodeWithTag(VerifyEmailTags.ChangeAddress).performClick()
    compose.onNodeWithTag(SignUpTags.input(SignUpField.FullName)).assertExists()
    compose.onNodeWithTag(SignUpTags.Google).assertExists()
    assertEquals(AccountType.STUDENT, signup.uiState.value.form.accountType)
    assertNull(auth.currentUser())
  }

  private fun pressBack() {
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
  }
}
