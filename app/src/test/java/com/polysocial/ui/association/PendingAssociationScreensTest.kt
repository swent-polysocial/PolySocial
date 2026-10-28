// Contributors: Claude (testing agent: the Organization tab's states, TalkBack, long names and
// large font sizes, #127; the loading spinner, after a review, #127).
package com.polysocial.ui.association

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * The pending association's tabs (#127), shown on their own: the Organization tab in each state,
 * what TalkBack reads, a very long name and the largest font size. Fictional data only.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PendingAssociationScreensTest {
  @get:Rule val compose = createComposeRule()

  private var edits = 0
  private var retries = 0
  private var logOuts = 0

  private fun show(content: @Composable () -> Unit) {
    compose.setContent { PolySocialTheme { content() } }
    compose.waitForIdle()
  }

  private fun showOrganization(
      state: PendingAssociationUiState,
      onEdit: (() -> Unit)? = { edits++ },
  ) = show {
    PendingOrganizationScreen(
        state = state,
        onEdit = onEdit,
        onRetry = { retries++ },
        onLogOut = { logOuts++ },
    )
  }

  private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

  // --- The Organization tab's states ---

  @Test
  fun loaded_showsThePencil_whichOpensEditDetails() {
    showOrganization(PendingAssociationUiState.Loaded(testClub))

    compose.onNodeWithTag(AssociationTags.EDIT).assertIsDisplayed().performClick()

    assertEquals(1, edits)
  }

  @Test
  fun withoutOnEdit_thereIsNoPencil_evenOnceLoaded() {
    showOrganization(PendingAssociationUiState.Loaded(testClub), onEdit = null)

    compose.onNodeWithTag(AssociationTags.EDIT).assertDoesNotExist()
    node(AssociationTags.NAME).assertTextEquals("Test Club")
  }

  @Test
  fun whileLoading_thereIsNoPencil_butLogOutIsThere() {
    showOrganization(PendingAssociationUiState.Loading)

    compose.onNodeWithTag(AssociationTags.EDIT).assertDoesNotExist()
    node(AssociationTags.NAME).assertDoesNotExist()
    // A visible loading state while the details are read.
    compose.onNodeWithTag(AssociationTags.LOADING).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.LOG_OUT).assertIsDisplayed().performClick()
    assertEquals(1, logOuts)
  }

  @Test
  fun failed_offersRetry_andLogOut_withoutThePencil() {
    showOrganization(PendingAssociationUiState.Failed)

    compose.onNodeWithTag(AssociationTags.EDIT).assertDoesNotExist()
    compose.onNodeWithTag(AssociationTags.LOADING).assertDoesNotExist()
    compose.onNodeWithTag(AssociationTags.LOAD_ERROR).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.LOG_OUT).assertIsDisplayed().performClick()
    compose.onNode(hasText("Retry")).performClick()

    assertEquals(1, logOuts)
    assertEquals(1, retries)
  }

  @Test
  fun aMissingWebsite_stillShowsEveryOtherDetail() {
    showOrganization(PendingAssociationUiState.Loaded(testClub.copy(website = null)))

    node(AssociationTags.NAME).assertTextEquals("Test Club")
    node(AssociationTags.TYPE).assertTextEquals("Club · EPFL")
    node(AssociationTags.ABOUT).assertTextEquals("A fictional club for tests.")
  }

  // --- TalkBack ---

  @Test
  fun talkBack_skipsTheInitials_whichOnlyRepeatTheName() {
    show { PendingEventsScreen(testClub, onNotAvailable = {}) }

    node(AssociationTags.HEADER_INITIALS)
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    // The name itself is read.
    node(AssociationTags.HEADER_NAME)
        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
  }

  @Test
  fun talkBack_skipsTheOrganizationTabsInitialsToo() {
    showOrganization(PendingAssociationUiState.Loaded(testClub))

    node(AssociationTags.ORGANIZATION_INITIALS)
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
  }

  @Test
  fun theEventsTab_hasTheTabTitleAndTheCardTitleAsHeadings() {
    show { PendingEventsScreen(testClub, onNotAvailable = {}) }

    compose.onNode(isHeading() and hasText("Events")).assertIsDisplayed()
    compose.onNode(isHeading() and hasText("You can't publish events yet.")).assertIsDisplayed()
  }

  @Test
  fun theMapTab_hasTheTabTitleAndItsMessageTitleAsHeadings() {
    show { PendingMapScreen(testClub) }

    compose.onNode(isHeading() and hasText("Map")).assertIsDisplayed()
    compose
        .onNode(isHeading() and hasText("The map opens once you're verified"))
        .assertIsDisplayed()
  }

  @Test
  fun theOrganizationTab_hasItsTitleAsAHeading_andAPencilNamedEditDetails() {
    showOrganization(PendingAssociationUiState.Loaded(testClub))

    compose.onNode(isHeading() and hasText("Organization")).assertIsDisplayed()
    compose
        .onNodeWithTag(AssociationTags.EDIT)
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Edit details"),
            )
        )
  }

  // --- A very long name, with native graphics (Robolectric's legacy mode never shortens text) ---

  private fun layoutOf(node: SemanticsNode): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
    return layouts.single()
  }

  private fun layoutOf(tag: String) = layoutOf(node(tag).fetchSemanticsNode())

  /** The longest name the Security Rules accept (#126), with a long word in it. */
  private val longName = "Association des étudiants en microtechnique et robotiqueXX"

  @Config(qualifiers = "w360dp-h800dp")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun aVeryLongName_isShortenedOnOneLineInTheHeader_andKeepsTheTabTitle() {
    assertEquals(58, longName.length)
    show { PendingEventsScreen(testClub.copy(name = longName), onNotAvailable = {}) }

    node(AssociationTags.HEADER_NAME).assertTextEquals(longName)
    val layout = layoutOf(AssociationTags.HEADER_NAME)
    assertEquals(1, layout.lineCount)
    assertTrue("The long name isn't shortened", layout.isLineEllipsized(0))
    val screen = compose.onRoot().getBoundsInRoot()
    val name = node(AssociationTags.HEADER_NAME).getBoundsInRoot()
    assertTrue("The name ($name) runs into the margin", name.right <= screen.right - 20.dp)
    compose.onNode(isHeading() and hasText("Events")).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.HEADER_INITIALS).assertTextEquals("AR")
  }

  @Config(qualifiers = "w360dp-h800dp")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun aVeryLongName_wrapsWholeOnTheOrganizationTab_besideTheTile() {
    showOrganization(PendingAssociationUiState.Loaded(testClub.copy(name = longName)))

    val layout = layoutOf(AssociationTags.NAME)
    assertTrue("The long name should wrap", layout.lineCount > 1)
    for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
    assertFalse(layout.didOverflowHeight)
    val screen = compose.onRoot().getBoundsInRoot()
    val name = node(AssociationTags.NAME).getBoundsInRoot()
    assertTrue("The name ($name) runs into the margin", name.right <= screen.right - 20.dp)
    compose.onNodeWithTag(AssociationTags.EDIT).assertIsDisplayed()
    assertNothingCutOff(AssociationTags.ORGANIZATION_SCREEN)
  }

  // --- The largest font size Android offers ---

  /**
   * Every text under [container] is drawn whole: no line shortened, nothing overflowing its box,
   * and inside the screen's width.
   */
  private fun assertNothingCutOff(container: String) {
    val screenWidth = compose.onRoot().fetchSemanticsNode().size.width
    val texts =
        compose
            .onAllNodes(
                hasAnyAncestor(hasTestTag(container)) and
                    SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                useUnmergedTree = true,
            )
            .fetchSemanticsNodes()
    assertTrue("No text under $container", texts.isNotEmpty())
    for (text in texts) {
      val layout = layoutOf(text)
      val content = layout.layoutInput.text.text
      // Only the height: the width a Row first measures text with is reported as overflow.
      assertFalse("\"$content\" is cut off at the bottom", layout.didOverflowHeight)
      for (line in 0 until layout.lineCount) {
        assertFalse("\"$content\" is shortened", layout.isLineEllipsized(line))
      }
      val bounds = text.boundsInRoot
      assertTrue(
          "\"$content\" ($bounds) leaves the screen",
          bounds.left >= 0f && bounds.right <= screenWidth,
      )
    }
  }

  /** The text [text] lies wholly inside the node tagged [container] (a button, the badge). */
  private fun assertTextInside(text: String, container: String) {
    val inner =
        compose
            .onNode(hasText(text) and hasAnyAncestor(hasTestTag(container)), useUnmergedTree = true)
            .getBoundsInRoot()
    val outer = node(container).getBoundsInRoot()
    val slack = 0.5.dp
    assertTrue(
        "\"$text\" ($inner) spills out of $container ($outer)",
        inner.left >= outer.left - slack &&
            inner.top >= outer.top - slack &&
            inner.right <= outer.right + slack &&
            inner.bottom <= outer.bottom + slack,
    )
  }

  @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theEventsTabKeepsItsButtonsAndStepsWhole() {
    show { PendingEventsScreen(testClub, onNotAvailable = {}) }

    assertNothingCutOff(AssociationTags.EVENTS_SCREEN)
    assertTextInside("New draft", AssociationTags.NEW_DRAFT)
    assertTextInside("Invite members", AssociationTags.INVITE_MEMBERS)
    assertTextInside("Drafts", AssociationTags.DRAFTS)
    assertTextInside("PolySocial team checks it", AssociationTags.UNDER_REVIEW)
    assertTextInside("TC", AssociationTags.HEADER_INITIALS)
  }

  @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theOrganizationTabKeepsTheBadgeAndLogOutWhole() {
    showOrganization(PendingAssociationUiState.Loaded(testClub))

    assertNothingCutOff(AssociationTags.ORGANIZATION_SCREEN)
    assertTextInside("Under review", AssociationTags.BADGE)
    assertTextInside("Log out", AssociationTags.LOG_OUT)
    assertTextInside("TC", AssociationTags.ORGANIZATION_INITIALS)
  }

  @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theMapTabsMessageIsWhole() {
    show { PendingMapScreen(testClub) }

    assertNothingCutOff(AssociationTags.MAP_SCREEN)
  }
}
