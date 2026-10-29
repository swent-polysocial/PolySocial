// Contributors: Claude (wrote these tests, #127; logging in again in the same activity, after a
// review, #127); Claude (association log in, non-EPFL accounts that are not associations, #208).
package com.polysocial.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.LogInResult
import com.polysocial.model.auth.VerificationClock
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.SessionCache
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.resources.C
import com.polysocial.ui.association.AssociationTags
import com.polysocial.ui.association.PendingAssociationApp
import com.polysocial.ui.association.PendingAssociationViewModel
import com.polysocial.ui.auth.AuthFlow
import com.polysocial.ui.auth.SignUpViewModel
import com.polysocial.ui.auth.VerifyEmailTags
import com.polysocial.ui.auth.VerifyEmailViewModel
import com.polysocial.ui.auth.WelcomeTags
import com.polysocial.ui.auth.WelcomeViewModel
import com.polysocial.ui.login.LoginScreenTestTags
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.login.UNVERIFIED_REDIRECT_DELAY_MS
import com.polysocial.ui.profile.ProfileSetupTestTags
import com.polysocial.ui.profile.ProfileSetupViewModel
import com.polysocial.ui.profile.ProfileTags
import com.polysocial.ui.profile.ProfileViewModel
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * App start for association accounts (#127): an association an admin hasn't verified yet always
 * opens its pending app, never the students' one; a verified association and a student open the
 * students' app; logging out of the pending app returns to Welcome. Associations log in through the
 * same Log in screen with any email (#208), and a non-EPFL account that is not an association never
 * reaches the student profile step or the students' app.
 */
@RunWith(AndroidJUnit4::class)
class PendingAssociationFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val user = AuthUser("club-uid", "club@example.org", isEmailVerified = true)
  private val auth = FakeAuthRepository(user)
  private val profiles = FakeUserProfileRepository()
  private val associations =
      FakeAssociationRepository(
          AssociationResult.Found(
              com.polysocial.model.association.Association(
                  "club-uid",
                  "Test Club",
                  com.polysocial.model.association.AssociationType.CLUB,
                  "A fictional club for tests.",
              )
          )
      )
  private val cache =
      object : SessionCache {
        override suspend fun clear() = CacheClearResult.Cleared
      }

  private fun profile(type: AccountType, verified: Boolean = false) =
      ProfileResult.Found(
          UserProfile(
              uid = user.uid,
              email = user.email,
              displayName = "Test Club",
              section = "",
              year = "",
              accountType = type,
              isAssociationVerified = verified,
          )
      )

  private lateinit var start: AppStartViewModel

  private fun launch() {
    start = AppStartViewModel(auth, profiles)
    val verification =
        VerifyEmailViewModel(auth, MemoryVerificationStore(), VerificationClock { 1000 })
    val signUp = SignUpViewModel(auth)
    val login = LoginViewModel(auth)
    val profileStep = ProfileSetupViewModel(auth, profiles)
    val welcome = WelcomeViewModel(auth)
    compose.setContent {
      PolySocialTheme {
        AuthFlow(
            viewModel = signUp,
            onExit = {},
            loginViewModel = login,
            startViewModel = start,
            verificationViewModel = verification,
            profileSetupViewModel = profileStep,
            welcomeViewModel = welcome,
            // The students' app needs Hilt; these tests only check which app opens.
            signedInContent = { Box(Modifier.fillMaxSize().testTag(STUDENT_APP)) },
            pendingAssociationContent = { done ->
              PendingAssociationApp(
                  onLoggedOut = done,
                  viewModel = viewModel { PendingAssociationViewModel(auth, associations) },
                  logoutViewModel = viewModel { ProfileViewModel(auth, cache) },
              )
            },
        )
      }
    }
    compose.waitForIdle()
  }

  @Test
  fun anAssociationAnAdminHasntVerified_opensItsPendingApp_neverTheStudentsOne() {
    profiles.getProfileResult = profile(AccountType.ASSOCIATION)

    launch()

    compose.onNodeWithTag(AssociationTags.SHELL).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.UNDER_REVIEW).assertIsDisplayed()
    compose.onNodeWithTag(STUDENT_APP).assertDoesNotExist()
  }

  @Test
  fun aVerifiedAssociation_opensTheVerifiedApp_withoutThePendingState() {
    profiles.getProfileResult = profile(AccountType.ASSOCIATION, verified = true)

    launch()

    compose.onNodeWithTag(STUDENT_APP).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.SHELL).assertDoesNotExist()
    // The pending app never read its details.
    assertEquals(0, associations.requestedUids.size)
  }

  @Test
  fun aStudent_opensTheStudentsApp() {
    // A student's session has an EPFL email (#208): a non-EPFL one can't open the students' app.
    auth.user = AuthUser("student-uid", "test.student@epfl.ch", isEmailVerified = true)
    profiles.getProfileResult = profile(AccountType.STUDENT)

    launch()

    compose.onNodeWithTag(STUDENT_APP).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.SHELL).assertDoesNotExist()
  }

  @Test
  fun loggingOutOfThePendingApp_returnsToWelcome() {
    profiles.getProfileResult = profile(AccountType.ASSOCIATION)
    launch()

    compose.onNodeWithTag(AssociationTags.NAV_ORGANIZATION).performClick()
    compose.onNodeWithTag(AssociationTags.LOG_OUT).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.SHELL).assertDoesNotExist()
    assertEquals(1, auth.logOutCalls)
  }

  @Test
  fun loggingInAgain_inTheSameActivity_opensTheNewAccountsPendingApp_withItsOwnDetails() {
    profiles.getProfileResult = profile(AccountType.ASSOCIATION)
    launch()
    compose.onNodeWithTag(AssociationTags.NAV_ORGANIZATION).performClick()
    compose.onNodeWithTag(AssociationTags.LOG_OUT).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()

    // Another pending association logs in without the app being restarted.
    val other = AuthUser("other-uid", "other@example.org", isEmailVerified = true)
    associations.getAssociationResult =
        AssociationResult.Found(
            com.polysocial.model.association.Association(
                "other-uid",
                "Other Club",
                com.polysocial.model.association.AssociationType.COMMISSION,
                "Another fictional club.",
            )
        )
    compose.runOnIdle {
      auth.user = other
      start.refresh()
    }
    compose.waitForIdle()

    // It stays in its pending app (no loop back to Welcome), on Events, with its own details.
    compose.onNodeWithTag(AssociationTags.SHELL).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.EVENTS_SCREEN).assertIsDisplayed()
    compose
        .onNodeWithTag(AssociationTags.HEADER_NAME, useUnmergedTree = true)
        .assertTextEquals("Other Club")
    compose.onNodeWithTag(AssociationTags.NAV_ORGANIZATION).performClick()
    compose
        .onNodeWithTag(AssociationTags.NAME, useUnmergedTree = true)
        .assertTextEquals("Other Club")
    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    assertEquals(1, auth.logOutCalls)
    assertEquals(listOf("club-uid", "other-uid"), associations.requestedUids)
  }

  private fun logInFromWelcome(email: String) {
    compose.onNodeWithTag(WelcomeTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTags.LogIn).performScrollTo().performClick()
    compose.onNodeWithTag(LoginScreenTestTags.EMAIL).performTextInput(email)
    compose.onNodeWithTag(LoginScreenTestTags.PASSWORD).performTextInput("password1")
    compose.onNodeWithTag(LoginScreenTestTags.LOG_IN).performScrollTo().performClick()
    compose.waitForIdle()
  }

  private fun signedOutLaunch(logInAs: AuthUser = user) {
    auth.user = null
    auth.logInResult = LogInResult.Success(logInAs)
    launch()
  }

  @Test
  fun aPendingAssociation_logsInWithItsOwnEmail_andOpensItsPendingApp() {
    profiles.getProfileResult = profile(AccountType.ASSOCIATION)
    signedOutLaunch()

    logInFromWelcome("club@example.org")

    compose.onNodeWithTag(AssociationTags.SHELL).assertIsDisplayed()
    compose.onNodeWithTag(STUDENT_APP).assertDoesNotExist()
    assertEquals("club@example.org", auth.lastLogInEmail)
    assertEquals(1, auth.logInCalls)
  }

  @Test
  fun anAssociationWithAnUnverifiedEmail_goesThroughVerifyEmail_withoutReadingItsProfile() {
    profiles.getProfileResult = profile(AccountType.ASSOCIATION)
    signedOutLaunch(logInAs = user.copy(isEmailVerified = false))

    logInFromWelcome("club@example.org")
    // Log in shows its "not verified" banner for a moment before handing over.
    compose.mainClock.advanceTimeBy(UNVERIFIED_REDIRECT_DELAY_MS)
    compose.waitForIdle()

    compose.onNodeWithTag(VerifyEmailTags.Screen).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.SHELL).assertDoesNotExist()
    assertEquals(0, profiles.getProfileCalls)
  }

  @Test
  fun loggingOutOfThePendingApp_thenLoggingInAgain_reopensThePendingApp() {
    profiles.getProfileResult = profile(AccountType.ASSOCIATION)
    signedOutLaunch()
    logInFromWelcome("club@example.org")
    compose.onNodeWithTag(AssociationTags.NAV_ORGANIZATION).performClick()
    compose.onNodeWithTag(AssociationTags.LOG_OUT).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitForIdle()

    logInFromWelcome("club@example.org")

    compose.onNodeWithTag(AssociationTags.SHELL).assertIsDisplayed()
    compose.onNodeWithTag(STUDENT_APP).assertDoesNotExist()
    assertEquals(1, auth.logOutCalls)
    assertEquals(2, auth.logInCalls)
  }

  @Test
  fun aNonEpflAccountWithoutAProfile_neverSeesTheStudentProfileStepOrApp() {
    profiles.getProfileResult = ProfileResult.NotFound
    signedOutLaunch()

    logInFromWelcome("club@example.org")

    compose.onNodeWithTag(C.Tag.error_retry).assertIsDisplayed()
    compose.onNodeWithTag(ProfileSetupTestTags.SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(STUDENT_APP).assertDoesNotExist()
    compose.onNodeWithTag(AssociationTags.SHELL).assertDoesNotExist()
  }

  @Test
  fun aNonEpflAccountWithAStudentProfile_neverOpensTheStudentsApp() {
    profiles.getProfileResult = profile(AccountType.STUDENT)

    launch()

    compose.onNodeWithTag(C.Tag.error_retry).assertIsDisplayed()
    compose.onNodeWithTag(STUDENT_APP).assertDoesNotExist()
  }

  private companion object {
    const val STUDENT_APP = "fake_student_app"
  }
}
