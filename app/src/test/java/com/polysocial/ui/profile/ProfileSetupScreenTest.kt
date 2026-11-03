// Contributors: Claude Opus 5.5 (wrote these tests; large font scale, layout at the default font
// size, one save report after rotation, picker accessibility); Claude Opus 5.5 (testing agent:
// double tap, blocked Continue, existing profile, error retry and picker menu behaviour); Claude
// (Back locked while saving, #74); Claude Opus 5.5 (testing agent: generic save error tests,
// #142); Claude (Log out touch area and test tidying after review, #142; the full name's limit,
// written test-first, #143; Continue then Finish, and the save tests moved to the interests step,
// #230).
package com.polysocial.ui.profile

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
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
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import com.polysocial.model.user.MAX_DISPLAY_NAME_LENGTH
import com.polysocial.model.user.SECTIONS
import com.polysocial.model.user.YEARS
import com.polysocial.ui.theme.PolySocialTheme
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  // What ProfileSetupContent reports when shown with setContent.
  private var contentBackCalls = 0
  private var contentContinueCalls = 0

  private fun string(id: Int, vararg args: Any) =
      ApplicationProvider.getApplicationContext<Application>().getString(id, *args)

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
            onBack = { contentBackCalls++ },
            onDisplayNameChange = {},
            onSectionChange = {},
            onYearChange = {},
            onContinue = { contentContinueCalls++ },
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

  /** Continue to the interests (#230), then Finish, which saves. */
  private fun continueAndFinish() {
    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()
    node(ProfileSetupTestTags.FINISH).performScrollTo().performClick()
    composeTestRule.waitForIdle()
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
    composeTestRule.onNodeWithText(string(R.string.profile_step, 1)).assertIsDisplayed()
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
  fun continue_opensTheInterestsStepAndSavesNothing() {
    setScreen()
    pickSectionAndYear()

    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()

    node(ProfileSetupTestTags.INTERESTS).assertExists()
    node(ProfileSetupTestTags.DETAILS).assertDoesNotExist()
    composeTestRule.onNodeWithText(string(R.string.profile_step, 2)).assertIsDisplayed()
    assertEquals(emptyList<Any>(), profiles.createdProfiles)
    assertEquals(0, savedCalls)
  }

  // The acceptance criterion's UI test (#230): the chosen interests are saved with the profile.
  @Test
  fun choosingInterestsAndFinishing_createsTheProfileWithThemOnceAndMovesOn() {
    setScreen()
    node(ProfileSetupTestTags.FULL_NAME).performTextClearance()
    node(ProfileSetupTestTags.FULL_NAME).performTextInput("New Name")
    pickSectionAndYear(section = "MA", year = "BA1")
    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()

    node(ProfileSetupTestTags.interest("tech")).performScrollTo().performClick()
    node(ProfileSetupTestTags.interest("music")).performScrollTo().performClick()
    node(ProfileSetupTestTags.interest("sports")).performScrollTo().performClick()
    node(ProfileSetupTestTags.interest("sports")).performScrollTo().performClick()
    node(ProfileSetupTestTags.FINISH).performScrollTo().performClick()
    composeTestRule.waitForIdle()

    val created = profiles.createdProfiles.single()
    assertEquals("u1", created.uid)
    assertEquals("New Name", created.displayName)
    assertEquals("MA", created.section)
    assertEquals("BA1", created.year)
    assertEquals(setOf("music", "tech"), created.interests)
    assertEquals(1, savedCalls)
  }

  @Test
  fun finishingWithNoInterests_createsTheProfileWithNone() {
    setScreen()
    pickSectionAndYear()

    continueAndFinish()

    assertEquals(emptySet<String>(), profiles.createdProfiles.single().interests)
    assertEquals(1, savedCalls)
  }

  @Test
  fun backFromTheInterests_returnsToTheDetailsKeepingEverything() {
    setScreen()
    pickSectionAndYear(section = "SC", year = "MA2")
    node(ProfileSetupTestTags.CONTINUE).performClick()
    node(ProfileSetupTestTags.interest("cinema")).performScrollTo().performClick()

    node(ProfileSetupTestTags.BACK).performClick()
    composeTestRule.waitForIdle()

    node(ProfileSetupTestTags.DETAILS).assertExists()
    node(ProfileSetupTestTags.SECTION).assert(shows("SC"))
    node(ProfileSetupTestTags.YEAR).assert(shows("MA2"))
    // Back from the interests isn't the step's Back: nobody is signed out.
    assertEquals(0, backCalls)
    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()
    node(ProfileSetupTestTags.interest("cinema")).assertIsOn()
  }

  @Test
  fun systemBackFromTheInterests_returnsToTheDetails() {
    setScreen()
    pickSectionAndYear()
    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()

    Espresso.pressBack()
    composeTestRule.waitForIdle()

    node(ProfileSetupTestTags.DETAILS).assertExists()
    assertEquals(0, backCalls)
  }

  @Test
  fun saving_showsSavingAndLocksTheInterests() {
    profiles.createGate = CompletableDeferred()
    setScreen()
    pickSectionAndYear()

    continueAndFinish()

    node(ProfileSetupTestTags.FINISH)
        .assert(shows(string(R.string.profile_saving)))
        .assertIsNotEnabled()
    node(ProfileSetupTestTags.interest("music")).assertIsNotEnabled()
    node(ProfileSetupTestTags.BACK).assertIsNotEnabled()
    assertEquals(0, savedCalls)

    profiles.createGate?.complete(Unit)
    composeTestRule.waitForIdle()
    assertEquals(1, savedCalls)
  }

  @Test
  fun offline_showsTheErrorAndFinishRetries() {
    profiles.createProfileResult = CreateProfileResult.NetworkError
    setScreen()
    pickSectionAndYear()

    continueAndFinish()

    node(ProfileSetupTestTags.ERROR).assert(shows(string(R.string.profile_could_not_save)))
    node(ProfileSetupTestTags.FINISH).assertIsEnabled()
    assertEquals(0, savedCalls)

    profiles.createProfileResult = CreateProfileResult.Created
    node(ProfileSetupTestTags.FINISH).performClick()
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
  fun back_isLockedWhileSavingAndUnlockedAfterAFailure() {
    profiles.createGate = CompletableDeferred()
    profiles.createProfileResult = CreateProfileResult.NetworkError
    setScreen()
    pickSectionAndYear()

    continueAndFinish()
    // Leaving now would drop the save's result before the app has used it.
    node(ProfileSetupTestTags.BACK).assertIsNotEnabled()
    Espresso.pressBack()
    composeTestRule.waitForIdle()
    node(ProfileSetupTestTags.INTERESTS).assertExists()

    profiles.createGate?.complete(Unit)
    composeTestRule.waitForIdle()
    node(ProfileSetupTestTags.BACK).assertIsEnabled().performClick()
    composeTestRule.waitForIdle()
    node(ProfileSetupTestTags.DETAILS).assertExists()
    node(ProfileSetupTestTags.ERROR).assertDoesNotExist()
    assertEquals(0, backCalls)
  }

  @Test
  fun doubleTapOnFinish_createsTheProfileOnce() {
    profiles.createGate = CompletableDeferred()
    setScreen()
    pickSectionAndYear()
    node(ProfileSetupTestTags.CONTINUE).performClick()
    composeTestRule.waitForIdle()

    // A quick double tap while the first save is still running: the second must write nothing.
    node(ProfileSetupTestTags.FINISH).performTouchInput {
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

    node(ProfileSetupTestTags.INTERESTS).assertDoesNotExist()
    assertEquals(emptyList<Any>(), profiles.createdProfiles)
    assertEquals(0, savedCalls)
  }

  @Test
  fun existingProfile_movesOnWithoutAnError() {
    profiles.createProfileResult = CreateProfileResult.AlreadyExists
    setScreen()
    pickSectionAndYear()

    continueAndFinish()

    node(ProfileSetupTestTags.ERROR).assertDoesNotExist()
    assertEquals(1, profiles.createdProfiles.size)
    assertEquals(1, savedCalls)
  }

  @Test
  fun failedSave_choosingHidesTheErrorAndFinishRetriesWithTheNewChoice() {
    profiles.createProfileResult = CreateProfileResult.UnexpectedError
    setScreen()
    pickSectionAndYear()
    continueAndFinish()
    node(ProfileSetupTestTags.ERROR).assertExists()
    assertEquals(0, savedCalls)

    node(ProfileSetupTestTags.interest("games")).performScrollTo().performClick()
    node(ProfileSetupTestTags.ERROR).assertDoesNotExist()
    profiles.createProfileResult = CreateProfileResult.Created
    node(ProfileSetupTestTags.FINISH).performScrollTo().performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf(emptySet(), setOf("games")), profiles.createdProfiles.map { it.interests })
    assertEquals(1, savedCalls)
  }

  @Test
  fun failedSave_goingBackToFixTheYear_savesTheNewYear() {
    profiles.createProfileResult = CreateProfileResult.NetworkError
    setScreen()
    pickSectionAndYear(year = "BA3")
    continueAndFinish()

    node(ProfileSetupTestTags.BACK).performClick()
    composeTestRule.waitForIdle()
    pick(ProfileSetupTestTags.YEAR, ProfileSetupTestTags.yearOption("BA4"))
    profiles.createProfileResult = CreateProfileResult.Created
    continueAndFinish()

    assertEquals(listOf("BA3", "BA4"), profiles.createdProfiles.map { it.year })
    assertEquals(1, savedCalls)
  }

  @Test
  fun unexpectedError_showsTheGenericErrorAndLogOutCallsOnBack() {
    profiles.createProfileResult = CreateProfileResult.UnexpectedError
    setScreen()
    pickSectionAndYear()

    continueAndFinish()

    node(ProfileSetupTestTags.ERROR).assert(shows(string(R.string.profile_could_not_save_generic)))
    node(ProfileSetupTestTags.FINISH).assertIsEnabled()
    // Logging out is the step's Back: AuthFlow signs out and returns to Welcome.
    node(ProfileSetupTestTags.LOG_OUT).performScrollTo().performClick()
    composeTestRule.waitForIdle()

    assertEquals(1, backCalls)
    assertEquals(0, savedCalls)
    assertEquals(1, profiles.createdProfiles.size)
  }

  @Test
  fun genericError_choosingHidesTheErrorAndLogOut() {
    profiles.createProfileResult = CreateProfileResult.UnexpectedError
    setScreen()
    pickSectionAndYear()
    continueAndFinish()
    node(ProfileSetupTestTags.LOG_OUT).assertExists()

    node(ProfileSetupTestTags.interest("music")).performScrollTo().performClick()

    node(ProfileSetupTestTags.ERROR).assertDoesNotExist()
    node(ProfileSetupTestTags.LOG_OUT).assertDoesNotExist()
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
  fun editing_showsNoLogOut() {
    setContent(filled)

    node(ProfileSetupTestTags.LOG_OUT).assertDoesNotExist()
    composeTestRule.onNodeWithText(string(R.string.profile_log_out)).assertDoesNotExist()
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

  /** Sets the form at [fontScale], filled in. */
  private fun setFilledForm(fontScale: Float) {
    composeTestRule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        PolySocialTheme {
          ProfileSetupContent(
              state = filled,
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
    continueAndFinish()
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

  // ---- The full name's limit (#143), Figma "Profile · name too long" ----

  private val tooLong = "a".repeat(MAX_DISPLAY_NAME_LENGTH + 1)

  @Test
  fun aNameOverTheLimit_showsTheErrorUnderTheNameAndDisablesContinue() {
    setContent(filled.copy(displayName = tooLong))

    node(ProfileSetupTestTags.NAME_ERROR).assert(shows("Use at most 50 characters"))
    node(ProfileSetupTestTags.FULL_NAME)
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    node(ProfileSetupTestTags.CONTINUE).assertIsNotEnabled()
  }

  @Test
  fun aNameAtTheLimit_showsNoError() {
    setContent(filled.copy(displayName = "a".repeat(MAX_DISPLAY_NAME_LENGTH)))

    node(ProfileSetupTestTags.NAME_ERROR).assertDoesNotExist()
    node(ProfileSetupTestTags.FULL_NAME)
        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    node(ProfileSetupTestTags.CONTINUE).assertIsEnabled()
  }

  @Test
  fun typingPastTheLimit_showsTheError_andShorteningHidesIt() {
    setScreen()
    pickSectionAndYear()

    node(ProfileSetupTestTags.FULL_NAME).performTextReplacement(tooLong)
    node(ProfileSetupTestTags.NAME_ERROR).assertExists()
    node(ProfileSetupTestTags.CONTINUE).assertIsNotEnabled()

    node(ProfileSetupTestTags.FULL_NAME).performTextReplacement("a".repeat(MAX_DISPLAY_NAME_LENGTH))
    node(ProfileSetupTestTags.NAME_ERROR).assertDoesNotExist()
    node(ProfileSetupTestTags.CONTINUE).assertIsEnabled()
  }

  @Test
  @Config(qualifiers = "w360dp-h800dp")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun aNameOverTheLimit_matchesTheFigmaFrame() {
    setContent(filled.copy(displayName = tooLong))
    val density = composeTestRule.density.density
    val screenTop = node(ProfileSetupTestTags.SCREEN).node().top() / density
    fun SemanticsNode.yDp() = top() / density - screenTop

    val field = node(ProfileSetupTestTags.FULL_NAME).node()
    val error = node(ProfileSetupTestTags.NAME_ERROR).node()
    val message = textNode("Use at most 50 characters", ProfileSetupTestTags.NAME_ERROR)
    val emailLabel = textNode(string(R.string.profile_email))
    // Figma: the error line 7 dp under the name field (icon at x 20, text at x 40), the next
    // label 35.5 dp under the field instead of 13.5 (22 dp lower), and Continue still at y 720.
    // Measured from the field, because the whole form already sits 3 dp lower than Figma.
    val fieldBottom = field.bottom() / density - screenTop
    assertEquals(7f, error.yDp() - fieldBottom, 1f)
    assertEquals(20f, error.positionInRoot.x / density, 0.5f)
    assertEquals(40f, message.positionInRoot.x / density, 0.5f)
    assertEquals(35.5f, emailLabel.yDp() - fieldBottom, 1f)
    assertEquals(720f, node(ProfileSetupTestTags.CONTINUE).node().yDp(), 1f)
    assertFalse("the message is clipped", message.textOverflows())
  }

  @Test
  @Config(qualifiers = "w360dp-h800dp-xhdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun aNameOverTheLimit_drawsTheFieldBorderInTheErrorRedTwoDpWide() {
    setContent(filled.copy(displayName = tooLong))
    val image = composeTestRule.onRoot().captureToImage().toPixelMap()
    val density = composeTestRule.density.density
    val field = node(ProfileSetupTestTags.FULL_NAME).node()
    val y = ((field.top() + field.bottom()) / 2).toInt()
    val left = field.positionInRoot.x.toInt()
    fun isAccent(x: Int) =
        image[x, y].let {
          abs(it.red - 0xD6 / 255f) < 0.06f &&
              abs(it.green - 0x30 / 255f) < 0.06f &&
              abs(it.blue - 0x3A / 255f) < 0.06f
        }

    // Figma: a 2 dp border in Accent (#D6303A), where "Profile · ready" has a 1 dp grey one.
    assertTrue(
        "1st dp of the border isn't the error red",
        isAccent(left + (0.5f * density).toInt()),
    )
    assertTrue(
        "2nd dp of the border isn't the error red",
        isAccent(left + (1.5f * density).toInt()),
    )
    assertFalse("the border is wider than 2 dp", isAccent(left + (3f * density).toInt()))
  }

  @Test
  @Config(qualifiers = "w360dp-h800dp-xhdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun aNameAtTheLimit_keepsTheGreyBorder() {
    setContent(filled.copy(displayName = "a".repeat(MAX_DISPLAY_NAME_LENGTH)))
    val image = composeTestRule.onRoot().captureToImage().toPixelMap()
    val density = composeTestRule.density.density
    val field = node(ProfileSetupTestTags.FULL_NAME).node()
    val color =
        image[
            field.positionInRoot.x.toInt() + (0.5f * density).toInt(),
            ((field.top() + field.bottom()) / 2).toInt()]

    assertTrue("the border is red without an error", color.red - color.green < 0.1f)
  }
}
