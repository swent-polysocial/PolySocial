// Contributors: Claude (wrote these tests, #127; logging in again in the same activity, after a
// review, #127).
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.VerificationClock
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.SessionCache
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.ui.association.AssociationTags
import com.polysocial.ui.association.EditAssociationViewModel
import com.polysocial.ui.association.PendingAssociationApp
import com.polysocial.ui.association.PendingAssociationViewModel
import com.polysocial.ui.auth.AuthFlow
import com.polysocial.ui.auth.SignUpViewModel
import com.polysocial.ui.auth.VerifyEmailViewModel
import com.polysocial.ui.auth.WelcomeTags
import com.polysocial.ui.auth.WelcomeViewModel
import com.polysocial.ui.login.LoginViewModel
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
 * students' app; logging out of the pending app returns to Welcome.
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
                  editViewModel = { viewModel { EditAssociationViewModel(associations) } },
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

  private companion object {
    const val STUDENT_APP = "fake_student_app"
  }
}
