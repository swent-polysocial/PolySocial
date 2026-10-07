// Contributors: Claude Opus 5.5 (wrote these tests).
package com.polysocial.ui.profile

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.user.CreateProfileResult
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.SECTIONS
import com.polysocial.model.user.YEARS
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileSetupScreenTest {
  @get:Rule val composeTestRule = createComposeRule()

  private val user =
      AuthUser(
          uid = "u1",
          email = "a@epfl.ch",
          isEmailVerified = true,
          displayName = "Test Student",
      )
  private val auth = FakeAuthRepository(user)
  private val profiles = FakeUserProfileRepository()

  private var backCalls = 0
  private var savedCalls = 0

  private fun string(id: Int) =
      ApplicationProvider.getApplicationContext<Application>().getString(id)

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  /** The node displays [text] in one of its children (the texts sit in child nodes). */
  private fun shows(text: String) = hasAnyDescendant(hasText(text))

  private fun setScreen() {
    val viewModel = ProfileSetupViewModel(auth, profiles)
    composeTestRule.setContent {
      PolySocialTheme {
        ProfileSetupScreen(
            onBack = { backCalls++ },
            onProfileSaved = { savedCalls++ },
            viewModel = viewModel,
        )
      }
    }
  }

  private fun setContent(state: ProfileSetupUiState) {
    composeTestRule.setContent {
      PolySocialTheme {
        ProfileSetupContent(
            state = state,
            onBack = {},
            onDisplayNameChange = {},
            onSectionChange = {},
            onYearChange = {},
            onContinue = {},
        )
      }
    }
  }

  private fun pick(fieldTag: String, optionTag: String) {
    node(fieldTag).performClick()
    node(optionTag).performScrollTo().performClick()
    composeTestRule.waitForIdle()
  }

  private fun pickSectionAndYear(section: String = "IN", year: String = "BA3") {
    pick(ProfileSetupTestTags.SECTION, ProfileSetupTestTags.sectionOption(section))
    pick(ProfileSetupTestTags.YEAR, ProfileSetupTestTags.yearOption(year))
  }

  private val filled =
      ProfileSetupUiState(
          displayName = "Test Student",
          email = "a@epfl.ch",
          section = "IN",
          year = "BA3",
      )

  // ---- Through the real ViewModel ----

  @Test
  fun opens_withTheNamePrefilledAndTheEmailShownAsVerified() {
    setScreen()

    composeTestRule.onNodeWithText(string(R.string.profile_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.profile_step)).assertIsDisplayed()
    node(ProfileSetupTestTags.FULL_NAME).assertTextEquals("Test Student")
    node(ProfileSetupTestTags.EMAIL).assert(shows("a@epfl.ch"))
    composeTestRule.onNodeWithText(string(R.string.profile_verified)).assertIsDisplayed()
    composeTestRule.onNodeWithText("TS").assertIsDisplayed()
  }

  @Test
  fun inputsAndContinue_haveTestTags() {
    setScreen()

    for (tag in
        listOf(
            ProfileSetupTestTags.SCREEN,
            ProfileSetupTestTags.BACK,
            ProfileSetupTestTags.FULL_NAME,
            ProfileSetupTestTags.EMAIL,
            ProfileSetupTestTags.SECTION,
            ProfileSetupTestTags.YEAR,
            ProfileSetupTestTags.CONTINUE,
        )) {
      node(tag).assertExists()
    }
  }

  @Test
  fun email_isReadOnly() {
    setScreen()

    node(ProfileSetupTestTags.EMAIL)
        .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
  }

  @Test
  fun continue_isDisabledUntilBothSectionAndYearArePicked() {
    setScreen()

    node(ProfileSetupTestTags.CONTINUE).assertIsNotEnabled()
    pick(ProfileSetupTestTags.SECTION, ProfileSetupTestTags.sectionOption("IN"))
    node(ProfileSetupTestTags.CONTINUE).assertIsNotEnabled()
    pick(ProfileSetupTestTags.YEAR, ProfileSetupTestTags.yearOption("BA3"))
    node(ProfileSetupTestTags.CONTINUE).assertIsEnabled()
  }

  @Test
  fun pickers_showChooseThenThePickedValue() {
    setScreen()

    node(ProfileSetupTestTags.SECTION).assert(shows(string(R.string.profile_choose)))
    node(ProfileSetupTestTags.YEAR).assert(shows(string(R.string.profile_choose)))
    pickSectionAndYear(section = "SC", year = "MA2")

    node(ProfileSetupTestTags.SECTION).assert(shows("SC"))
    node(ProfileSetupTestTags.YEAR).assert(shows("MA2"))
    node(ProfileSetupTestTags.sectionOption("SC")).assertDoesNotExist()
  }

  @Test
  fun sectionMenu_offersEveryFixedSection() {
    setScreen()
    node(ProfileSetupTestTags.SECTION).performClick()

    for (code in SECTIONS) node(ProfileSetupTestTags.sectionOption(code)).assertExists()
  }

  @Test
  fun yearMenu_offersBa1ToMa4() {
    setScreen()
    node(ProfileSetupTestTags.YEAR).performClick()

    assertEquals(
        listOf("BA1", "BA2", "BA3", "BA4", "BA5", "BA6", "MA1", "MA2", "MA3", "MA4"),
        YEARS,
    )
    for (code in YEARS) node(ProfileSetupTestTags.yearOption(code)).assertExists()
  }

  @Test
  fun continue_createsTheProfileOnceAndMovesOn() {
    setScreen()
    node(ProfileSetupTestTags.FULL_NAME).performTextClearance()
    node(ProfileSetupTestTags.FULL_NAME).performTextInput("New Name")
    pickSectionAndYear(section = "MA", year = "BA1")

    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()

    val created = profiles.createdProfiles.single()
    assertEquals("u1", created.uid)
    assertEquals("New Name", created.displayName)
    assertEquals("MA", created.section)
    assertEquals("BA1", created.year)
    assertEquals(1, savedCalls)
  }

  @Test
  fun saving_showsSavingAndLocksTheForm() {
    profiles.createGate = CompletableDeferred()
    setScreen()
    pickSectionAndYear()

    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()

    node(ProfileSetupTestTags.CONTINUE)
        .assert(shows(string(R.string.profile_saving)))
        .assertIsNotEnabled()
    node(ProfileSetupTestTags.FULL_NAME).assertIsNotEnabled()
    node(ProfileSetupTestTags.SECTION).assertIsNotEnabled()
    assertEquals(0, savedCalls)

    profiles.createGate?.complete(Unit)
    composeTestRule.waitForIdle()
    assertEquals(1, savedCalls)
  }

  @Test
  fun offline_showsTheErrorAndContinueRetries() {
    profiles.createProfileResult = CreateProfileResult.NetworkError
    setScreen()
    pickSectionAndYear()

    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()

    node(ProfileSetupTestTags.ERROR).assert(shows(string(R.string.profile_could_not_save)))
    node(ProfileSetupTestTags.CONTINUE).assertIsEnabled()
    assertEquals(0, savedCalls)

    profiles.createProfileResult = CreateProfileResult.Created
    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()

    assertEquals(2, profiles.createdProfiles.size)
    assertEquals(1, savedCalls)
  }

  @Test
  fun back_callsOnBack() {
    setScreen()

    node(ProfileSetupTestTags.BACK).performClick()

    assertEquals(1, backCalls)
    assertEquals(0, savedCalls)
  }

  // ---- Each Figma state ----

  @Test
  fun editing_showsNoErrorAndContinue() {
    setContent(filled)

    node(ProfileSetupTestTags.ERROR).assertDoesNotExist()
    node(ProfileSetupTestTags.CONTINUE)
        .assert(shows(string(R.string.profile_continue)))
        .assertIsEnabled()
    node(ProfileSetupTestTags.FULL_NAME).assertIsEnabled()
  }

  @Test
  fun couldNotSave_showsTheErrorMessage() {
    setContent(filled.copy(status = ProfileSetupStatus.CouldNotSave))

    node(ProfileSetupTestTags.ERROR).assert(shows(string(R.string.profile_could_not_save)))
  }

  @Test
  fun saved_locksTheForm() {
    setContent(filled.copy(status = ProfileSetupStatus.Saved))

    node(ProfileSetupTestTags.CONTINUE).assertIsNotEnabled()
    node(ProfileSetupTestTags.FULL_NAME).assertIsNotEnabled()
    node(ProfileSetupTestTags.YEAR).assertIsNotEnabled()
  }

  @Test
  fun compactHeight_continueCanBeScrolledToAndTapped() {
    var continueCalls = 0
    composeTestRule.setContent {
      PolySocialTheme {
        // Shorter than the form, like a small phone with the keyboard open.
        Box(Modifier.size(width = 360.dp, height = 320.dp)) {
          ProfileSetupContent(
              state = filled,
              onBack = {},
              onDisplayNameChange = {},
              onSectionChange = {},
              onYearChange = {},
              onContinue = { continueCalls++ },
          )
        }
      }
    }

    node(ProfileSetupTestTags.CONTINUE).assertIsNotDisplayed()
    node(ProfileSetupTestTags.CONTINUE).performScrollTo().assertIsDisplayed().performClick()
    assertEquals(1, continueCalls)
  }
}
