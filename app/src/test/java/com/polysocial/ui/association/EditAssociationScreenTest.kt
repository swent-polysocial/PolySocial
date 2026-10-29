// Contributors: Claude (testing agent: Edit details while saving, after recreation, for TalkBack
// and at the largest font size, #127; field labels and errors for TalkBack, after a review, #127).
package com.polysocial.ui.association

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.association.UpdateAssociationResult
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Edit details (#127) on its own, over a fake repository: what is blocked while saving, what
 * survives a recreation, what TalkBack reads and the largest font size. Fictional data only.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class EditAssociationScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val associations = FakeAssociationRepository()
  private val viewModel = EditAssociationViewModel(associations)
  private var backs = 0
  private val saved = mutableListOf<Association>()

  private fun show(restoration: StateRestorationTester? = null) {
    val content: @Composable () -> Unit = {
      PolySocialTheme {
        EditAssociationRoute(
            association = testClub,
            viewModel = viewModel,
            onBack = { backs++ },
            onSaved = { saved += it },
        )
      }
    }
    if (restoration != null) restoration.setContent(content) else compose.setContent(content)
    compose.waitForIdle()
  }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  private fun pressSystemBack() {
    compose.activity.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitForIdle()
  }

  // --- While saving ---

  @Test
  fun whileSaving_nothingCanBeChanged_andBackIsBlocked_untilItIsSaved() {
    val gate = CompletableDeferred<Unit>()
    associations.gate = gate
    show()
    node(EditAssociationTags.NAME).performTextReplacement("Renamed Club")

    node(EditAssociationTags.SAVE).performClick()
    compose.waitForIdle()

    node(EditAssociationTags.SAVE).assertTextEquals("Saving…").assertIsNotEnabled()
    node(EditAssociationTags.BACK).assertIsNotEnabled()
    node(EditAssociationTags.NAME).assertIsNotEnabled()
    node(EditAssociationTags.ABOUT).assertIsNotEnabled()
    node(EditAssociationTags.WEBSITE).assertIsNotEnabled()
    for (type in AssociationType.entries) {
      node(EditAssociationTags.type(type)).assertIsNotEnabled()
    }
    // The system Back doesn't leave either, so the result of the save isn't lost.
    val callbacks = compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()
    assertTrue("Back isn't held while saving", callbacks)
    pressSystemBack()
    assertEquals(0, backs)

    gate.complete(Unit)
    compose.waitForIdle()

    assertEquals(listOf(testClub.copy(name = "Renamed Club")), saved)
  }

  @Test
  fun whileEditing_backAndTheFieldsAreEnabled_andSystemBackIsLeftToTheCaller() {
    show()

    node(EditAssociationTags.BACK).assertIsEnabled().performClick()
    node(EditAssociationTags.NAME).assertIsEnabled()
    node(EditAssociationTags.SAVE).assertTextEquals("Save changes").assertIsEnabled()

    assertEquals(1, backs)
    assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
  }

  @Test
  fun aFailedSave_unblocksBack_andTheFields() {
    associations.updateAssociationResult = UpdateAssociationResult.NetworkError
    show()

    node(EditAssociationTags.SAVE).performClick()
    compose.waitForIdle()

    node(EditAssociationTags.BACK).assertIsEnabled()
    node(EditAssociationTags.NAME).assertIsEnabled()
    node(EditAssociationTags.SAVE).assertTextEquals("Try again").assertIsEnabled()
    assertEquals(emptyList<Association>(), saved)
  }

  // --- Recreation ---

  @Test
  fun theEditsAndTheirErrors_surviveARecreation() {
    val restoration = StateRestorationTester(compose)
    show(restoration)
    node(EditAssociationTags.NAME).performTextReplacement("")
    node(EditAssociationTags.type(AssociationType.COMMISSION)).performClick()
    node(EditAssociationTags.SAVE).performClick()
    compose.waitForIdle()

    restoration.emulateSavedInstanceStateRestore()

    // The field is empty again, so its placeholder shows.
    node(EditAssociationTags.NAME).assertTextEquals("Your association's name", "")
    node(EditAssociationTags.type(AssociationType.COMMISSION)).assertIsSelected()
    node(EditAssociationTags.error(AssociationFormError.NAME_MISSING)).assertIsDisplayed()
    node(EditAssociationTags.SAVE).assertIsNotEnabled()
    assertEquals(0, associations.updatedAssociations.size)
  }

  // --- TalkBack ---

  @Test
  fun theTitleIsAHeading_andBackIsNamed() {
    show()

    compose.onNode(isHeading() and hasText("Edit details")).assertIsDisplayed()
    node(EditAssociationTags.BACK)
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Back"),
            )
        )
  }

  @Test
  fun eachFieldIsNamedByItsLabel_andCarriesItsError_andTheTypesAreOneGroup() {
    show()
    for ((tag, label) in
        listOf(
            EditAssociationTags.NAME to "Name",
            EditAssociationTags.ABOUT to "About",
            EditAssociationTags.WEBSITE to "Website or EPFL page · optional",
        )) {
      node(tag)
          .assert(
              SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label))
          )
          .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }
    node(EditAssociationTags.type(AssociationType.CLUB))
        .onParent()
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))

    node(EditAssociationTags.NAME).performTextReplacement("")
    node(EditAssociationTags.WEBSITE).performTextReplacement("example.org")
    node(EditAssociationTags.SAVE).performClick()
    compose.waitForIdle()

    node(EditAssociationTags.NAME)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Add a name"))
    node(EditAssociationTags.WEBSITE)
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.Error,
                "Use a link starting with https://",
            )
        )
    node(EditAssociationTags.ABOUT)
        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
  }

  // --- The largest font size Android offers, with native graphics ---

  private fun assertTextWholeInside(text: String, container: String) {
    val matcher = hasText(text) and hasAnyAncestor(hasTestTag(container))
    val textNode = compose.onNode(matcher, useUnmergedTree = true)
    val layouts = mutableListOf<TextLayoutResult>()
    textNode
        .fetchSemanticsNode()
        .config[SemanticsActions.GetTextLayoutResult]
        .action!!
        .invoke(layouts)
    val layout = layouts.single()
    // Only the height: the width a Row first measures text with is reported as overflow.
    assertFalse("\"$text\" is cut off at the bottom", layout.didOverflowHeight)
    for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
    val inner = textNode.getBoundsInRoot()
    val outer = node(container).getBoundsInRoot()
    val screen = compose.onRoot().getBoundsInRoot()
    val slack = 0.5.dp
    assertTrue(
        "\"$text\" ($inner) spills out of $container ($outer)",
        inner.left >= outer.left - slack &&
            inner.top >= outer.top - slack &&
            inner.right <= outer.right + slack &&
            inner.bottom <= outer.bottom + slack &&
            outer.right <= screen.right + slack,
    )
  }

  @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theChipsSaveAndErrorsAreWhole() {
    show()
    node(EditAssociationTags.WEBSITE).performTextReplacement("example.org")
    node(EditAssociationTags.SAVE).performClick()
    compose.waitForIdle()

    assertTextWholeInside("Save changes", EditAssociationTags.SAVE)
    assertTextWholeInside("Club", EditAssociationTags.type(AssociationType.CLUB))
    assertTextWholeInside("EPFL unit", EditAssociationTags.type(AssociationType.EPFL_UNIT))
    assertTextWholeInside(
        "Use a link starting with https://",
        EditAssociationTags.error(AssociationFormError.WEBSITE_INVALID),
    )
  }

  @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theOfflineBannerAndTryAgainAreWhole() {
    associations.updateAssociationResult = UpdateAssociationResult.NetworkError
    show()

    node(EditAssociationTags.SAVE).performClick()
    compose.waitForIdle()

    assertTextWholeInside("Couldn't save your details", EditAssociationTags.FAILURE)
    assertTextWholeInside(
        "You're offline. Your changes are kept, so try again once you're back online.",
        EditAssociationTags.FAILURE,
    )
    assertTextWholeInside("Try again", EditAssociationTags.SAVE)
  }
}
