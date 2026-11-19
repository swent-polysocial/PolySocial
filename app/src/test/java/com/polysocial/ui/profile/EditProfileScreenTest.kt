// Contributors: OpenAI Codex (UI tests for editing section, year and interests, including the
// route's offline retry and confirmed save, #235).
package com.polysocial.ui.profile

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UpdateProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The edit form state by state (#235); the ViewModel's rules are in [EditProfileViewModelTest]. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditProfileScreenTest {
  @get:Rule(order = 0) val main = MainDispatcherRule()
  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val profile =
      UserProfile(
          uid = "u1",
          email = "a@epfl.ch",
          displayName = "Test Student",
          section = "IN",
          year = "BA3",
      )
  private val calls = mutableListOf<String>()

  private fun string(id: Int) =
      ApplicationProvider.getApplicationContext<Application>().getString(id)

  private fun show(state: EditProfileState) {
    composeTestRule.setContent {
      PolySocialTheme {
        EditProfileScreen(
            state = state,
            onBack = { calls += "back" },
            onRetry = { calls += "retry" },
            onSection = { calls += "section:$it" },
            onYear = { calls += "year:$it" },
            onInterest = { calls += "interest:$it" },
            onSave = { calls += "save" },
        )
      }
    }
  }

  @Test
  fun unchangedForm_showsCurrentValues_andSaveIsDisabled() {
    show(EditProfileState.Editing(profile))

    composeTestRule.onNodeWithText("IN").assertIsDisplayed()
    composeTestRule.onNodeWithText("BA3").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EditProfileTags.SAVE).performScrollTo().assertIsNotEnabled()
  }

  @Test
  fun tappingAnInterestChip_reportsIt() {
    show(EditProfileState.Editing(profile))

    composeTestRule
        .onNodeWithTag(EditProfileTags.interest("music"))
        .performScrollTo()
        .performClick()

    assertEquals(listOf("interest:music"), calls)
  }

  @Test
  fun changedForm_enablesSave_andSaveIsReported() {
    show(EditProfileState.Editing(profile, interests = setOf("music")))

    composeTestRule
        .onNodeWithTag(EditProfileTags.SAVE)
        .performScrollTo()
        .assertIsEnabled()
        .performClick()

    assertEquals(listOf("save"), calls)
  }

  @Test
  fun offlineFailure_keepsTheFormAndShowsTheOfflineMessage() {
    show(
        EditProfileState.Editing(
            profile,
            interests = setOf("music"),
            failure = EditProfileState.Failure.OFFLINE,
        )
    )

    composeTestRule.onNodeWithTag(EditProfileTags.ERROR).performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.edit_profile_offline)).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(EditProfileTags.interest("music"))
        .performScrollTo()
        .assertIsDisplayed()
  }

  @Test
  fun editsReachTheRepository_andOnlyASuccessfulRetryClosesTheEditor() {
    val auth = FakeAuthRepository(AuthUser(profile.uid, profile.email, isEmailVerified = true))
    val profiles = FakeUserProfileRepository(ProfileResult.Found(profile))
    profiles.updateProfileResult = UpdateProfileResult.NetworkError
    val viewModel = EditProfileViewModel(auth, profiles)
    var closes = 0
    composeTestRule.setContent {
      PolySocialTheme { EditProfileRoute(onBack = { closes++ }, viewModel = viewModel) }
    }
    settleRoute()

    composeTestRule.onNodeWithTag(EditProfileTags.SECTION).performClick()
    composeTestRule.onNodeWithTag(EditProfileTags.section("EL")).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(EditProfileTags.YEAR).performClick()
    composeTestRule.onNodeWithTag(EditProfileTags.year("BA4")).performScrollTo().performClick()
    val interest = composeTestRule.onNodeWithTag(EditProfileTags.interest("music"))
    interest.performScrollTo().performClick()
    val save = composeTestRule.onNodeWithTag(EditProfileTags.SAVE)
    save.performScrollTo().performClick()
    settleRoute()

    val updated = profile.copy(section = "EL", year = "BA4", interests = setOf("music"))
    assertEquals(listOf(updated), profiles.updatedProfiles)
    assertEquals(0, closes)
    save.performScrollTo()
    composeTestRule.onNodeWithText(string(R.string.edit_profile_offline)).assertIsDisplayed()
    interest.performScrollTo().assertIsOn()

    profiles.updateProfileResult = UpdateProfileResult.Updated
    save.performScrollTo().performClick()
    settleRoute()

    assertEquals(listOf(updated, updated), profiles.updatedProfiles)
    assertEquals(1, closes)
  }

  private fun settleRoute() {
    repeat(2) {
      composeTestRule.waitForIdle()
      main.dispatcher.scheduler.runCurrent()
    }
    composeTestRule.waitForIdle()
  }

  @Test
  fun savingLocksTheForm() {
    show(EditProfileState.Editing(profile, interests = setOf("music"), saving = true))

    composeTestRule.onNodeWithTag(EditProfileTags.SAVE).performScrollTo().assertIsNotEnabled()
  }

  @Test
  fun couldNotLoad_offersRetry() {
    show(EditProfileState.CouldNotLoad)

    composeTestRule.onNodeWithText(string(R.string.edit_profile_load_failed)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.retry)).performClick()

    assertEquals(listOf("retry"), calls)
  }

  @Test
  fun backIsReported() {
    show(EditProfileState.Editing(profile))

    composeTestRule.onNodeWithTag(EditProfileTags.BACK).performClick()

    assertEquals(listOf("back"), calls)
  }
}
