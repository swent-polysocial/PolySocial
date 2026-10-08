// Contributors: Claude Opus 5.5 (wrote these tests; large font scale, layout at the default font
// size, one save report after rotation, picker accessibility); Claude Opus 5.5 (testing agent:
// double tap, blocked
// Continue, existing profile, error retry and picker menu behaviour).
package com.polysocial.ui.profile

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

  @Test
  fun doubleTapOnContinue_createsTheProfileOnce() {
    profiles.createGate = CompletableDeferred()
    setScreen()
    pickSectionAndYear()

    // A quick double tap while the first save is still running: the second must write nothing.
    node(ProfileSetupTestTags.CONTINUE).performTouchInput {
      click()
      click()
    }
    profiles.createGate?.complete(Unit)
    composeTestRule.waitForIdle()

    assertEquals(1, profiles.createdProfiles.size)
    assertEquals(1, savedCalls)
  }

  @Test
  fun disabledContinue_neverReachesTheRepository() {
    setScreen()

    node(ProfileSetupTestTags.CONTINUE).performClick()
    pick(ProfileSetupTestTags.SECTION, ProfileSetupTestTags.sectionOption("IN"))
    node(ProfileSetupTestTags.CONTINUE).performClick()
    pick(ProfileSetupTestTags.YEAR, ProfileSetupTestTags.yearOption("BA3"))
    node(ProfileSetupTestTags.FULL_NAME).performTextClearance()
    node(ProfileSetupTestTags.CONTINUE).assertIsNotEnabled().performClick()
    composeTestRule.waitForIdle()

    assertEquals(emptyList<Any>(), profiles.createdProfiles)
    assertEquals(0, savedCalls)
  }

  @Test
  fun existingProfile_movesOnWithoutAnError() {
    profiles.createProfileResult = CreateProfileResult.AlreadyExists
    setScreen()
    pickSectionAndYear()

    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()

    node(ProfileSetupTestTags.ERROR).assertDoesNotExist()
    assertEquals(1, profiles.createdProfiles.size)
    assertEquals(1, savedCalls)
  }

  @Test
  fun failedSave_editingHidesTheErrorAndContinueRetriesWithTheNewValue() {
    profiles.createProfileResult = CreateProfileResult.UnexpectedError
    setScreen()
    pickSectionAndYear(year = "BA3")
    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()
    node(ProfileSetupTestTags.ERROR).assertExists()
    assertEquals(0, savedCalls)

    pick(ProfileSetupTestTags.YEAR, ProfileSetupTestTags.yearOption("BA4"))
    node(ProfileSetupTestTags.ERROR).assertDoesNotExist()
    profiles.createProfileResult = CreateProfileResult.Created
    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf("BA3", "BA4"), profiles.createdProfiles.map { it.year })
    assertEquals(1, savedCalls)
  }

  @Test
  fun pickerMenu_backClosesItAndKeepsTheValue() {
    setScreen()
    pick(ProfileSetupTestTags.SECTION, ProfileSetupTestTags.sectionOption("IN"))

    node(ProfileSetupTestTags.SECTION).performClick()
    node(ProfileSetupTestTags.sectionOption("AR")).assertExists()
    Espresso.pressBack()
    composeTestRule.waitForIdle()

    node(ProfileSetupTestTags.sectionOption("AR")).assertDoesNotExist()
    node(ProfileSetupTestTags.SECTION).assert(shows("IN"))
  }

  @Test
  fun pickerMenu_opensAtTheSelectedValueAndEveryOptionIsReachable() {
    setScreen()
    pick(ProfileSetupTestTags.SECTION, ProfileSetupTestTags.sectionOption("SC"))

    node(ProfileSetupTestTags.SECTION).performClick()

    // SC is near the end of the list: the menu opens scrolled to it, past the first options.
    node(ProfileSetupTestTags.sectionOption("SC")).assertIsDisplayed()
    node(ProfileSetupTestTags.sectionOption(SECTIONS.first())).assertIsNotDisplayed()
    for (code in SECTIONS) {
      node(ProfileSetupTestTags.sectionOption(code)).performScrollTo().assertIsDisplayed()
    }
  }

  @Test
  fun pickerMenu_marksOnlyTheSelectedOption() {
    setScreen()
    pick(ProfileSetupTestTags.YEAR, ProfileSetupTestTags.yearOption("MA1"))

    node(ProfileSetupTestTags.YEAR).performClick()

    for (code in YEARS) {
      assertEquals(
          code,
          if (code == "MA1") FontWeight.Bold else FontWeight.Normal,
          optionFontWeight(ProfileSetupTestTags.yearOption(code), code),
      )
    }
  }

  /** The font weight the option tagged [tag] draws its [text] with. */
  private fun optionFontWeight(tag: String, text: String): FontWeight? {
    val textNode =
        composeTestRule
            .onNode(hasText(text) and hasParent(hasTestTag(tag)), useUnmergedTree = true)
            .fetchSemanticsNode()
    val layouts = mutableListOf<TextLayoutResult>()
    textNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    return layouts.single().layoutInput.style.fontWeight
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

  // ---- Large font size ----

  /** Whether the node's text is taller than the space it was given (so it would be clipped). */
  private fun SemanticsNode.textOverflows(): Boolean {
    val layouts = mutableListOf<TextLayoutResult>()
    config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
    return layouts.any { it.didOverflowHeight }
  }

  private fun SemanticsNodeInteraction.node() = fetchSemanticsNode()

  // Unclipped: boundsInRoot is empty for a node scrolled out of view.
  private fun SemanticsNode.top() = positionInRoot.y

  private fun SemanticsNode.bottom() = positionInRoot.y + size.height

  /** The text node showing [text], inside the node tagged [parentTag] when given. */
  private fun textNode(text: String, parentTag: String? = null): SemanticsNode {
    val matcher =
        if (parentTag == null) hasText(text) else hasText(text) and hasParent(hasTestTag(parentTag))
    return composeTestRule.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode()
  }

  /** Sets the form at [fontScale], filled in and showing the save error (the tallest state). */
  private fun setFilledForm(fontScale: Float) {
    composeTestRule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        PolySocialTheme {
          ProfileSetupContent(
              state = filled.copy(status = ProfileSetupStatus.CouldNotSave),
              onBack = {},
              onDisplayNameChange = {},
              onSectionChange = {},
              onYearChange = {},
              onContinue = {},
          )
        }
      }
    }
  }

  /** No text is clipped, and the elements are stacked top to bottom without overlapping. */
  private fun assertTextsFitAndNothingOverlaps() {
    fun text(id: Int) = textNode(string(id))
    // Every text, fetched as the text node itself: a field's tag sits on its container, which
    // has no text layout to check.
    val texts =
        mapOf(
            "title" to text(R.string.profile_title),
            "subtitle" to text(R.string.profile_subtitle),
            "full name label" to text(R.string.profile_full_name),
            "name field" to node(ProfileSetupTestTags.FULL_NAME).node(),
            "email label" to text(R.string.profile_email),
            "email value" to textNode("a@epfl.ch", ProfileSetupTestTags.EMAIL),
            "section label" to text(R.string.profile_section),
            "section value" to textNode("IN", ProfileSetupTestTags.SECTION),
            "year label" to text(R.string.profile_year),
            "year value" to textNode("BA3", ProfileSetupTestTags.YEAR),
            "privacy note" to text(R.string.profile_privacy),
            "error" to
                textNode(string(R.string.profile_could_not_save), ProfileSetupTestTags.ERROR),
            "continue" to
                textNode(string(R.string.profile_continue), ProfileSetupTestTags.CONTINUE),
        )
    for ((name, node) in texts) assertTrue("$name text is clipped", !node.textOverflows())

    // Top to bottom, as on screen (the year column sits next to the section column).
    fun tagged(tag: String) = node(tag).node()
    val column =
        listOf(
            "title" to texts.getValue("title"),
            "subtitle" to texts.getValue("subtitle"),
            "full name label" to texts.getValue("full name label"),
            "name field" to tagged(ProfileSetupTestTags.FULL_NAME),
            "email label" to texts.getValue("email label"),
            "email field" to tagged(ProfileSetupTestTags.EMAIL),
            "section label" to texts.getValue("section label"),
            "section field" to tagged(ProfileSetupTestTags.SECTION),
            "privacy note" to texts.getValue("privacy note"),
            "error" to tagged(ProfileSetupTestTags.ERROR),
            "continue" to tagged(ProfileSetupTestTags.CONTINUE),
        )
    val yearColumn =
        listOf(
            "year label" to texts.getValue("year label"),
            "year field" to tagged(ProfileSetupTestTags.YEAR),
            "privacy note" to texts.getValue("privacy note"),
        )
    for (stack in listOf(column, yearColumn)) {
      stack.zipWithNext().forEach { (above, below) ->
        assertTrue(
            "${below.first} overlaps ${above.first}",
            below.second.top() >= above.second.bottom() - 1,
        )
      }
    }
  }

  @Test
  // The Figma frame size, with real text metrics (the legacy mode measures every line 35 dp).
  @Config(qualifiers = "w360dp-h800dp")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun defaultFontSize_nothingOverlapsAndContinueSitsAtTheBottom() {
    setFilledForm(fontScale = 1f)

    assertTextsFitAndNothingOverlaps()
    // As in Figma, Continue ends 28 dp above the bottom of the screen, below the free space.
    val density = composeTestRule.density.density
    val screenBottom = node(ProfileSetupTestTags.SCREEN).node().bottom() / density
    val continueBottom = node(ProfileSetupTestTags.CONTINUE).node().bottom() / density
    assertEquals(28f, screenBottom - continueBottom, 1f)
  }

  @Test
  // Real text metrics: the legacy graphics mode measures text with fixed fake sizes.
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun doubleFontSize_textsFitAndNothingOverlaps() {
    setFilledForm(fontScale = 2f)

    assertTextsFitAndNothingOverlaps()
  }

  // ---- Rotation and accessibility ----

  @Test
  fun profileSaved_isReportedOnceEvenAfterRotation() {
    val viewModel = ProfileSetupViewModel(auth, profiles)
    val restoration = StateRestorationTester(composeTestRule)
    restoration.setContent {
      PolySocialTheme {
        ProfileSetupScreen(onBack = {}, onProfileSaved = { savedCalls++ }, viewModel = viewModel)
      }
    }
    pickSectionAndYear()
    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()
    assertEquals(1, savedCalls)

    // The ViewModel survives the rotation and is still Saved.
    restoration.emulateSavedInstanceStateRestore()
    composeTestRule.waitForIdle()

    assertEquals(1, savedCalls)
  }

  @Test
  fun pickers_announceTheirLabelValueAndRole() {
    setContent(filled.copy(section = null))

    node(ProfileSetupTestTags.SECTION)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(string(R.string.profile_section)),
            )
        )
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                string(R.string.profile_choose),
            )
        )
    node(ProfileSetupTestTags.YEAR)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "BA3"))
    // The visible label is read through the field only, not a second time on its own.
    composeTestRule
        .onNodeWithText(string(R.string.profile_section), useUnmergedTree = true)
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
  }

  @Test
  fun pickerMenu_exposesTheChosenOptionAsSelected() {
    setScreen()
    pick(ProfileSetupTestTags.SECTION, ProfileSetupTestTags.sectionOption("SC"))

    node(ProfileSetupTestTags.SECTION).performClick()

    node(ProfileSetupTestTags.sectionOption("SC")).assertIsSelected()
    node(ProfileSetupTestTags.sectionOption("IN")).assertIsNotSelected()
  }
}
