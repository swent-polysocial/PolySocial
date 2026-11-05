// Contributors: Claude (the interests step's tests, written test-first, #230; the save error tests
// moved here from ProfileSetupScreenTest, where #142 added them; the count announced and the
// button's role, after review, #230).
package com.polysocial.ui.profile

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.user.INTERESTS
import com.polysocial.ui.common.INTEREST_LABELS
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Step 2 of the profile step, the interests (#230), state by state as in the Figma "Interests · …"
 * frames. The flow through the real ViewModel (Continue, choosing, Finish, Back) is in
 * [ProfileSetupScreenTest].
 */
@RunWith(AndroidJUnit4::class)
class ProfileInterestsStepTest {
  @get:Rule val composeTestRule = createComposeRule()

  private var backCalls = 0
  private var finishCalls = 0
  private var logOutCalls = 0
  private val toggled = mutableListOf<String>()

  private val ready =
      ProfileSetupUiState(
          displayName = "Test Student",
          email = "a@epfl.ch",
          section = "IN",
          year = "BA3",
          page = ProfileSetupPage.Interests,
      )

  private fun string(id: Int, vararg args: Any) =
      ApplicationProvider.getApplicationContext<Application>().getString(id, *args)

  private fun selected(count: Int) =
      ApplicationProvider.getApplicationContext<Application>()
          .resources
          .getQuantityString(R.plurals.profile_interests_selected, count, count)

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  private fun shows(text: String) = hasAnyDescendant(hasText(text))

  private fun setContent(state: ProfileSetupUiState, fontScale: Float = 1f) {
    composeTestRule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        PolySocialTheme {
          ProfileInterestsContent(
              state = state,
              onBack = { backCalls++ },
              onInterestToggle = { toggled += it },
              onFinish = { finishCalls++ },
              onLogOut = { logOutCalls++ },
          )
        }
      }
    }
  }

  // ---- What it shows ----

  @Test
  fun opens_withTheTitleTheStepAndEveryInterestUnchosen() {
    setContent(ready)

    composeTestRule.onNodeWithText(string(R.string.profile_interests_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.profile_step, 2)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.profile_interests_subtitle)).assertExists()
    node(ProfileSetupTestTags.INTERESTS_COUNT).assertTextEquals("Interests · 0 selected")
    for (code in INTERESTS) {
      node(ProfileSetupTestTags.interest(code))
          .assert(shows(string(INTEREST_LABELS.getValue(code))))
          .assertIsOff()
    }
  }

  @Test
  fun theTextsAreTheFigmaOnes() {
    assertEquals("What are you into?", string(R.string.profile_interests_title))
    assertEquals(
        "Optional. We only use them to match you with people you'll get along with.",
        string(R.string.profile_interests_subtitle),
    )
    assertEquals("Finish · see events", string(R.string.profile_finish))
    assertEquals("Step 2 of 2", string(R.string.profile_step, 2))
    assertEquals("Interests · 1 selected", selected(1))
    assertEquals("Interests · 4 selected", selected(4))
  }

  @Test
  fun everyInterest_hasItsOwnLabel_inTheFixedOrder() {
    assertEquals(INTERESTS, INTEREST_LABELS.keys.toList())
    assertEquals(
        listOf(
            "Sports",
            "Outdoors",
            "Music",
            "Parties",
            "Cinema",
            "Games",
            "Photography",
            "Cooking",
            "Tech",
            "Startups",
            "Arts & theatre",
            "Volunteering",
        ),
        INTERESTS.map { string(INTEREST_LABELS.getValue(it)) },
    )
  }

  @Test
  fun chosenInterests_areOnAndCounted() {
    setContent(ready.copy(interests = setOf("music", "tech")))

    node(ProfileSetupTestTags.INTERESTS_COUNT).assertTextEquals("Interests · 2 selected")
    for (code in INTERESTS) {
      val chip = node(ProfileSetupTestTags.interest(code))
      if (code == "music" || code == "tech") chip.assertIsOn() else chip.assertIsOff()
    }
  }

  @Test
  fun aChip_isACheckboxAndTappingItReportsItsCode() {
    setContent(ready.copy(interests = setOf("music")))

    node(ProfileSetupTestTags.interest("cinema"))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        .performScrollTo()
        .performClick()
    node(ProfileSetupTestTags.interest("music")).performScrollTo().performClick()

    assertEquals(listOf("cinema", "music"), toggled)
  }

  @Test
  fun theCount_isAnnouncedWhenItChanges() {
    setContent(ready)

    node(ProfileSetupTestTags.INTERESTS_COUNT)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
  }

  @Test
  fun finish_isAButton() {
    setContent(ready)

    node(ProfileSetupTestTags.FINISH)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
  }

  @Test
  fun finish_isEnabledWithNoInterestsAndReportsFinish() {
    setContent(ready)

    node(ProfileSetupTestTags.FINISH)
        .assert(shows(string(R.string.profile_finish)))
        .assertIsEnabled()
        .performScrollTo()
        .performClick()

    assertEquals(1, finishCalls)
  }

  @Test
  fun back_callsOnBack() {
    setContent(ready)

    node(ProfileSetupTestTags.BACK).performClick()

    assertEquals(1, backCalls)
    assertEquals(0, logOutCalls)
  }

  // ---- Each save state ----

  @Test
  fun editing_showsNoErrorAndNoLogOut() {
    setContent(ready)

    node(ProfileSetupTestTags.ERROR).assertDoesNotExist()
    node(ProfileSetupTestTags.LOG_OUT).assertDoesNotExist()
  }

  @Test
  fun couldNotSave_showsTheErrorWithoutLogOut_andFinishRetries() {
    setContent(ready.copy(status = ProfileSetupStatus.CouldNotSave))

    node(ProfileSetupTestTags.ERROR)
        .assert(shows(string(R.string.profile_could_not_save)))
        .assert(hasAnyDescendant(hasText(string(R.string.profile_could_not_save_generic))).not())
    node(ProfileSetupTestTags.LOG_OUT).assertDoesNotExist()
    node(ProfileSetupTestTags.FINISH).assertIsEnabled().performScrollTo().performClick()
    assertEquals(1, finishCalls)
  }

  @Test
  fun couldNotSaveGeneric_showsTheGenericMessageAndLogOut() {
    setContent(ready.copy(status = ProfileSetupStatus.CouldNotSaveGeneric))

    node(ProfileSetupTestTags.ERROR)
        .assert(shows(string(R.string.profile_could_not_save_generic)))
        .assert(hasAnyDescendant(hasText(string(R.string.profile_could_not_save))).not())
    assertEquals(
        "Couldn't save your profile. Log out and log in again, then try once more.",
        string(R.string.profile_could_not_save_generic),
    )
    node(ProfileSetupTestTags.LOG_OUT)
        .assertTextEquals("Log out")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    node(ProfileSetupTestTags.FINISH).assertIsEnabled()
  }

  @Test
  fun couldNotSaveGeneric_logOutCallsOnlyOnLogOut() {
    setContent(ready.copy(status = ProfileSetupStatus.CouldNotSaveGeneric))

    node(ProfileSetupTestTags.LOG_OUT).performScrollTo().performClick()

    assertEquals(1, logOutCalls)
    assertEquals(0, backCalls)
    assertEquals(0, finishCalls)
  }

  @Test
  fun couldNotSaveGeneric_finishRetriesWithoutLoggingOut() {
    setContent(ready.copy(status = ProfileSetupStatus.CouldNotSaveGeneric))

    node(ProfileSetupTestTags.FINISH).performScrollTo().performClick()

    assertEquals(1, finishCalls)
    assertEquals(0, logOutCalls)
  }

  @Test
  fun couldNotSaveGeneric_logOutIsBoldTwelveSpInTheErrorRed() {
    setContent(ready.copy(status = ProfileSetupStatus.CouldNotSaveGeneric))

    val style =
        node(ProfileSetupTestTags.LOG_OUT).fetchSemanticsNode().textLayout().layoutInput.style
    assertEquals(FontWeight.Bold, style.fontWeight)
    assertEquals(12.sp, style.fontSize)
    assertEquals(AccentText, style.color)
  }

  @Test
  fun saving_showsSavingAndLocksTheChipsAndBack() {
    setContent(ready.copy(interests = setOf("music"), status = ProfileSetupStatus.Saving))

    node(ProfileSetupTestTags.FINISH)
        .assert(shows(string(R.string.profile_saving)))
        .assertIsNotEnabled()
    for (code in INTERESTS) node(ProfileSetupTestTags.interest(code)).assertIsNotEnabled()
    node(ProfileSetupTestTags.interest("music")).assertIsOn()
    node(ProfileSetupTestTags.BACK).assertIsNotEnabled().performClick()
    node(ProfileSetupTestTags.interest("tech")).performScrollTo().performClick()
    assertEquals(0, backCalls)
    assertEquals(emptyList<String>(), toggled)
  }

  @Test
  fun saved_locksEverything() {
    setContent(ready.copy(status = ProfileSetupStatus.Saved))

    node(ProfileSetupTestTags.FINISH).assertIsNotEnabled()
    node(ProfileSetupTestTags.interest("sports")).assertIsNotEnabled()
    node(ProfileSetupTestTags.BACK).assertIsNotEnabled()
  }

  @Test
  fun compactHeight_finishCanBeScrolledToAndTapped() {
    composeTestRule.setContent {
      PolySocialTheme {
        // Shorter than the screen's content, like a small phone. The legacy graphics mode measures
        // the labels narrow, so the chips take only two rows: 240 dp keeps Finish out of view.
        Box(Modifier.size(width = 360.dp, height = 240.dp)) {
          ProfileInterestsContent(
              state = ready,
              onBack = {},
              onInterestToggle = {},
              onFinish = { finishCalls++ },
              onLogOut = {},
          )
        }
      }
    }

    node(ProfileSetupTestTags.FINISH).assertIsNotDisplayed()
    node(ProfileSetupTestTags.FINISH).performScrollTo().assertIsDisplayed().performClick()
    assertEquals(1, finishCalls)
  }

  // ---- Layout, against the Figma frames ----

  private fun SemanticsNode.textLayout(): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    return layouts.single()
  }

  private fun SemanticsNode.textOverflows(): Boolean {
    val layouts = mutableListOf<TextLayoutResult>()
    config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
    return layouts.any { it.didOverflowHeight }
  }

  private fun SemanticsNode.top() = positionInRoot.y

  private fun SemanticsNode.bottom() = positionInRoot.y + size.height

  private fun tagged(tag: String) = node(tag).fetchSemanticsNode()

  private fun textNode(text: String, parentTag: String? = null): SemanticsNode {
    val matcher =
        if (parentTag == null) hasText(text) else hasText(text) and hasParent(hasTestTag(parentTag))
    return composeTestRule.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode()
  }

  /** Log out's node is its touch area: the text is drawn [LOG_OUT_TOUCH_EXTRA] inside it. */
  private fun SemanticsNode.textTop() =
      top() + LOG_OUT_TOUCH_EXTRA.value * composeTestRule.density.density

  private fun SemanticsNode.textBottom() =
      bottom() - LOG_OUT_TOUCH_EXTRA.value * composeTestRule.density.density

  @Test
  // The Figma frame size, with real text metrics.
  @Config(qualifiers = "w360dp-h800dp")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun theCountAndChipsSitWhereFigmaHasThem_andFinishAtTheBottom() {
    setContent(ready.copy(interests = setOf("outdoors", "music", "cinema", "tech")))
    val density = composeTestRule.density.density
    val screenTop = tagged(ProfileSetupTestTags.SCREEN).top() / density
    fun SemanticsNode.yDp() = top() / density - screenTop

    // Figma "Interests · choosing": the subtitle at y 107, the count at y 169.5, the chips' first
    // row drawn at y 196 (its 48 dp touch targets from y 192), the second row 48 dp lower, Finish
    // at y 720, 28 dp above the bottom. Each gap is measured from the element above it, because
    // the title renders a little taller than Figma's, which moves everything below it about 2 dp
    // lower, as on the details screen.
    val subtitle = textNode(string(R.string.profile_interests_subtitle))
    val count = tagged(ProfileSetupTestTags.INTERESTS_COUNT)
    val sports = tagged(ProfileSetupTestTags.interest("sports"))
    val parties = tagged(ProfileSetupTestTags.interest("parties"))
    val finish = tagged(ProfileSetupTestTags.FINISH)
    assertEquals(169.5f - 107f, count.yDp() - subtitle.yDp(), 1f)
    assertEquals(192f - 169.5f, sports.yDp() - count.yDp(), 1f)
    assertEquals(48f, sports.size.height / density, 0.5f)
    assertEquals(20f, sports.positionInRoot.x / density, 0.5f)
    assertEquals(48f, parties.yDp() - sports.yDp(), 0.5f)
    assertEquals(20f, parties.positionInRoot.x / density, 0.5f)
    assertEquals(720f, finish.yDp(), 1f)
    val screenBottom = tagged(ProfileSetupTestTags.SCREEN).bottom() / density
    assertEquals(28f, screenBottom - finish.bottom() / density, 1f)
    // Every chip fits inside the margins, below the count and above Finish.
    for (code in INTERESTS) {
      val chip = tagged(ProfileSetupTestTags.interest(code))
      assertTrue("$code starts left of the margin", chip.positionInRoot.x / density >= 19.5f)
      assertTrue(
          "$code ends right of the margin",
          (chip.positionInRoot.x + chip.size.width) / density <= 340.5f,
      )
      assertTrue("$code overlaps the count", chip.top() >= count.bottom() - 1)
      assertTrue("$code overlaps Finish", chip.bottom() <= finish.top())
      assertFalse(
          "$code's label is clipped",
          textNode(string(INTEREST_LABELS.getValue(code))).textOverflows(),
      )
    }
  }

  @Test
  // Real text metrics: the legacy graphics mode measures text with fixed fake sizes.
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun doubleFontSize_textsFitAndEveryChipAndFinishCanBeReached() {
    setContent(ready.copy(status = ProfileSetupStatus.CouldNotSave), fontScale = 2f)

    for (id in
        listOf(
            R.string.profile_interests_title,
            R.string.profile_interests_subtitle,
            R.string.profile_could_not_save,
        )) {
      assertFalse("${string(id)} is clipped", textNode(string(id)).textOverflows())
    }
    assertFalse(
        "the count is clipped",
        tagged(ProfileSetupTestTags.INTERESTS_COUNT).textOverflows(),
    )
    for (code in INTERESTS) {
      assertFalse(
          "$code's label is clipped",
          textNode(string(INTEREST_LABELS.getValue(code))).textOverflows(),
      )
    }
    val lastChip = tagged(ProfileSetupTestTags.interest(INTERESTS.last()))
    assertTrue(
        "the error overlaps the chips",
        tagged(ProfileSetupTestTags.ERROR).top() >= lastChip.bottom() - 1,
    )
    node(ProfileSetupTestTags.interest(INTERESTS.last())).performScrollTo().assertIsDisplayed()
    node(ProfileSetupTestTags.FINISH).performScrollTo().assertIsDisplayed().performClick()
    assertEquals(1, finishCalls)
  }

  @Test
  // The Figma frame size, with real text metrics.
  @Config(qualifiers = "w360dp-h800dp")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun genericError_matchesTheFigmaFrameAndKeepsFinishWhereTheOfflineErrorHasIt() {
    var status by mutableStateOf(ProfileSetupStatus.CouldNotSave)
    composeTestRule.setContent {
      PolySocialTheme {
        ProfileInterestsContent(
            state = ready.copy(status = status),
            onBack = {},
            onInterestToggle = {},
            onFinish = {},
            onLogOut = { logOutCalls++ },
        )
      }
    }
    val density = composeTestRule.density.density
    val screenTop = tagged(ProfileSetupTestTags.SCREEN).top() / density
    val offlineFinish = tagged(ProfileSetupTestTags.FINISH)

    status = ProfileSetupStatus.CouldNotSaveGeneric
    composeTestRule.waitForIdle()

    val error = tagged(ProfileSetupTestTags.ERROR)
    val message =
        textNode(string(R.string.profile_could_not_save_generic), ProfileSetupTestTags.ERROR)
    val logOut = tagged(ProfileSetupTestTags.LOG_OUT)
    val finish = tagged(ProfileSetupTestTags.FINISH)
    // Figma "Interests · couldn't save · generic": message at y 640 (two lines, 30 dp), Log out
    // at y 674 (4 dp under it), aligned with the message's text, and Finish at y 720.
    assertEquals(640f, error.top() / density - screenTop, 1f)
    assertEquals(30f, (error.bottom() - error.top()) / density, 1f)
    assertEquals(4f, (logOut.textTop() - error.bottom()) / density, 0.5f)
    assertEquals(674f, logOut.textTop() / density - screenTop, 1f)
    assertEquals(15f, (logOut.textBottom() - logOut.textTop()) / density, 1f)
    assertEquals(message.positionInRoot.x, logOut.positionInRoot.x, 0.5f * density)
    assertEquals(40f, logOut.positionInRoot.x / density, 0.5f)
    assertEquals(720f, finish.top() / density - screenTop, 1f)
    assertFalse("the message is clipped", message.textOverflows())
    assertFalse("Log out is clipped", logOut.textOverflows())
    // Finish doesn't move between the two error frames.
    assertEquals(offlineFinish.positionInRoot, finish.positionInRoot)
    assertEquals(offlineFinish.size, finish.size)
    // The touch area reaches Android's 48 dp minimum without covering Finish, and a tap above the
    // drawn text still logs out.
    assertTrue((logOut.bottom() - logOut.top()) / density >= 48f)
    assertTrue("Log out's touch area covers Finish", logOut.bottom() <= finish.top())
    node(ProfileSetupTestTags.LOG_OUT).performTouchInput { click(Offset(centerX, 4f)) }
    assertEquals(1, logOutCalls)
  }

  @Test
  // Real text metrics: the legacy graphics mode measures text with fixed fake sizes.
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun doubleFontSize_genericErrorFitsAndLogOutCanBeScrolledToAndTapped() {
    setContent(ready.copy(status = ProfileSetupStatus.CouldNotSaveGeneric), fontScale = 2f)

    val message =
        textNode(string(R.string.profile_could_not_save_generic), ProfileSetupTestTags.ERROR)
    val error = tagged(ProfileSetupTestTags.ERROR)
    val logOut = tagged(ProfileSetupTestTags.LOG_OUT)
    val finish = tagged(ProfileSetupTestTags.FINISH)
    assertFalse("the message is clipped", message.textOverflows())
    assertFalse("Log out is clipped", logOut.textOverflows())
    assertTrue("Log out overlaps the message", logOut.textTop() >= error.bottom() - 1)
    assertTrue("Finish overlaps Log out", finish.top() >= logOut.textBottom() - 1)

    node(ProfileSetupTestTags.LOG_OUT).performScrollTo().assertIsDisplayed().performClick()
    assertEquals(1, logOutCalls)
  }
}
