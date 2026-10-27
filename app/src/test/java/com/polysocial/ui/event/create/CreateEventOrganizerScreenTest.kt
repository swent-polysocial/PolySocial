// Contributors: Claude (wrote these tests, #134; additional tests from a test audit, #134; large
// font sizes and TalkBack, #134).
package com.polysocial.ui.event.create

import android.app.Application
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.withCreator
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.resources.C
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.theme.Bg
import java.time.Clock
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
 * UI tests for the organizer row of the Create Event form (#134): a verified association sees its
 * initials, "Organized by <name>" and the Verified badge (Figma "Create event · association"); a
 * student, and anyone whose association can't be read, sees "Organized by you". Every data source
 * is a fake.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CreateEventOrganizerScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val events = FakeEventRepository(currentUid = ORGANIZER_UID, now = FORM_NOW)
  private val auth =
      FakeAuthRepository(AuthUser(ORGANIZER_UID, ORGANIZER_EMAIL, isEmailVerified = true))
  private val profiles =
      FakeUserProfileRepository(ProfileResult.Found(verifiedAssociationProfile()))
  private val associations = FakeAssociationRepository(AssociationResult.Found(TEST_CLUB))

  private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()) =
      CreateEventViewModel(
          events,
          Clock.fixed(FORM_NOW, ZURICH),
          savedState,
          auth,
          profiles,
          associations,
      )

  private fun showCreateEvent(): CreateEventViewModel {
    val viewModel = viewModel()
    composeTestRule.setContent {
      CreateEventScreen(
          viewModel = viewModel,
          onClose = {},
          onViewEvent = {},
          onBackToMap = {},
          locationPicker = { _, _, _, _ -> },
      )
    }
    composeTestRule.waitForIdle()
    return viewModel
  }

  private fun showEditEvent() {
    events.seed(
        completeForm()
            .toEvent(ZURICH)!!
            .withCreator(ORGANIZER_UID, isVerifiedAssociation = true)
            .copy(id = "event-1")
    )
    val viewModel =
        viewModel(SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to "event-1")))
    composeTestRule.setContent {
      EditEventScreen(onClose = {}, viewModel = viewModel, locationPicker = { _, _, _, _ -> })
    }
    composeTestRule.waitForIdle()
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun assertMissing(tag: String) = node(tag).assertDoesNotExist()

  /** How the text of the node [tag] is laid out: its lines, and whether one was shortened. */
  private fun textLayout(tag: String): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    return layouts.single()
  }

  /**
   * The Verified badge whole and inside the screen, not squeezed or pushed out by a long name. Its
   * width is checked without its text, which Robolectric measures much narrower than a phone (see
   * CONTEXT.md, Traps): 10 dp of padding on each side, the 12 dp check and the 4 dp gap.
   */
  private fun assertWholeBadgeOnScreen() {
    val badge = node(C.Tag.create_event_verified_badge).assertWidthIsAtLeast(36.dp)
    val screen = node(C.Tag.create_event_screen).getBoundsInRoot()
    assertTrue("The badge ends past the screen", badge.getBoundsInRoot().right <= screen.right)
  }

  /** The association variant: its initials, its name and the Verified badge, all on screen. */
  private fun assertAssociationRow(name: String, initials: String) {
    node(C.Tag.create_event_organized_by).assertIsDisplayed().assertTextEquals("Organized by $name")
    node(C.Tag.create_event_organizer_avatar).assertIsDisplayed().assertTextEquals(initials)
    node(C.Tag.create_event_verified_badge).assertIsDisplayed().assertTextEquals("Verified")
    assertWholeBadgeOnScreen()
    assertEquals(1, textLayout(C.Tag.create_event_organized_by).lineCount)
    composeTestRule.onNodeWithText("Organized by you").assertDoesNotExist()
    assertMissing(C.Tag.create_event_organizer_loading)
  }

  /** The student variant: "Organized by you" alone, as before #134. */
  private fun assertStudentRow() {
    node(C.Tag.create_event_organized_by).assertIsDisplayed().assertTextEquals("Organized by you")
    assertMissing(C.Tag.create_event_organizer_avatar)
    assertMissing(C.Tag.create_event_verified_badge)
    assertMissing(C.Tag.create_event_organizer_loading)
  }

  @Test
  fun aVerifiedAssociation_seesItsInitialsNameAndVerifiedBadge() {
    showCreateEvent()

    assertAssociationRow("Test Club", "TC")
  }

  @Test
  fun aStudent_stillSeesOrganizedByYou() {
    profiles.getProfileResult = ProfileResult.Found(studentProfile())

    showCreateEvent()

    assertStudentRow()
  }

  @Test
  fun aPendingAssociation_seesOrganizedByYou() {
    profiles.getProfileResult =
        ProfileResult.Found(verifiedAssociationProfile().copy(isAssociationVerified = false))

    showCreateEvent()

    assertStudentRow()
  }

  @Test
  fun anAssociationNameThatCantBeLoaded_fallsBackToOrganizedByYou() {
    associations.getAssociationResult = AssociationResult.NetworkError

    showCreateEvent()

    assertStudentRow()
  }

  @Test
  fun aProfileThatCantBeLoaded_fallsBackToOrganizedByYou() {
    profiles.getProfileResult = ProfileResult.NetworkError

    showCreateEvent()

    assertStudentRow()
  }

  @Test
  fun withTheNameUnavailable_theFormStillCreatesTheEvent() {
    associations.getAssociationResult = AssociationResult.UnexpectedError
    val viewModel = showCreateEvent()
    val form = completeForm()
    node(C.Tag.create_event_title).performTextInput(form.title)
    // The pickers open dialogs, so their values are set through the ViewModel, like
    // CreateEventScreenTest does.
    viewModel.onCategorySelect(form.category!!)
    viewModel.onDatePick(form.date!!)
    viewModel.onStartTimePick(form.startTime!!)
    viewModel.onLocationPick(form.location!!)

    node(C.Tag.create_event_submit).performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf(form.title), events.events.map { it.title })
    assertTrue(viewModel.uiState.value.status is CreateEventStatus.Created)
  }

  @Test
  fun whileTheOrganizerLoads_theRowIsEmptyAndTheFormWorks() {
    profiles.gate = CompletableDeferred()
    showCreateEvent()

    node(C.Tag.create_event_organizer_loading).assertIsDisplayed()
    assertMissing(C.Tag.create_event_organized_by)
    assertMissing(C.Tag.create_event_organizer_avatar)
    assertMissing(C.Tag.create_event_verified_badge)
    node(C.Tag.create_event_title).performTextInput("Study together")
    node(C.Tag.create_event_title).assertTextEquals("Study together")

    profiles.gate!!.complete(Unit)
    composeTestRule.waitForIdle()

    assertAssociationRow("Test Club", "TC")
  }

  // The Figma frame's size, with native graphics, which measure and shorten text like a phone
  // with the app's font (the legacy mode never draws an ellipsis).
  @Config(qualifiers = "w360dp-h800dp")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun aLongAssociationName_isShortenedOnOneLine_andKeepsTheWholeBadgeOnScreen() {
    // The longest name the rules accept (#126): the text shortens, the badge stays whole.
    val longName = "Association " + "x".repeat(48)
    assertEquals(60, longName.length)
    associations.getAssociationResult = AssociationResult.Found(TEST_CLUB.copy(name = longName))

    showCreateEvent()

    node(C.Tag.create_event_organized_by).assertTextEquals("Organized by $longName")
    val layout = textLayout(C.Tag.create_event_organized_by)
    assertEquals(1, layout.lineCount)
    assertTrue("The long name isn't shortened", layout.isLineEllipsized(0))
    node(C.Tag.create_event_organizer_avatar).assertIsDisplayed().assertTextEquals("AX")
    assertWholeBadgeOnScreen()
    // With real text metrics, the badge keeps Figma's width (82 dp, give or take a glyph's
    // rounding).
    node(C.Tag.create_event_verified_badge).assertWidthIsAtLeast(80.dp)
  }

  /** The layout of the text [text] inside the row, as drawn (not merged into its parent). */
  private fun drawnText(text: String): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    composeTestRule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(
        SemanticsActions.GetTextLayoutResult
    ) {
      it(layouts)
    }
    return layouts.single()
  }

  /**
   * The text [text] is drawn whole: no line cut off, and inside the shape tagged [container], at
   * least [bottomMargin] above its bottom edge.
   */
  private fun assertTextInside(text: String, container: String, bottomMargin: Dp = 0.dp) {
    assertFalse("\"$text\" is cut off at the bottom", drawnText(text).didOverflowHeight)
    val inner = composeTestRule.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()
    val outer = node(container).getBoundsInRoot()
    val slack = 0.5.dp
    assertTrue(
        "\"$text\" ($inner) spills out of $container ($outer)",
        inner.left >= outer.left - slack &&
            inner.top >= outer.top - slack &&
            inner.right <= outer.right + slack &&
            inner.bottom <= outer.bottom - bottomMargin + slack,
    )
  }

  // The largest font size Android offers (2.0), with native graphics, which measure text like a
  // phone. The texts grow, so nothing may be cut off.
  @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_noTextOfTheAssociationRowIsCutOff() {
    showCreateEvent()

    // Figma keeps 4 dp of the pill below the text.
    assertTextInside("Verified", C.Tag.create_event_verified_badge, bottomMargin = 4.dp)
    // The initials only repeat the name, so they keep Figma's size inside the 24 dp circle.
    assertTextInside("TC", C.Tag.create_event_organizer_avatar)
    node(C.Tag.create_event_organizer_avatar)
        .assertWidthIsEqualTo(24.dp)
        .assertHeightIsEqualTo(24.dp)
    node(C.Tag.create_event_verified_badge).assertHeightIsAtLeast(24.dp)
    assertWholeBadgeOnScreen()
    assertEquals(1, textLayout(C.Tag.create_event_organized_by).lineCount)
  }

  @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theStudentRowAppearsWithoutMovingTheForm() {
    // The empty row while loading holds the same space as "Organized by you", at any font size.
    profiles.getProfileResult = ProfileResult.Found(studentProfile())
    profiles.gate = CompletableDeferred()
    showCreateEvent()
    val titleTopWhileLoading = titleTop()

    profiles.gate!!.complete(Unit)
    composeTestRule.waitForIdle()

    assertStudentRow()
    assertEquals(titleTopWhileLoading, titleTop())
    assertFalse(drawnText("Organized by you").didOverflowHeight)
  }

  @Test
  fun talkBack_readsTheNameAndTheBadge_butSkipsTheInitials() {
    // The initials only repeat the name, which TalkBack already reads.
    showCreateEvent()

    node(C.Tag.create_event_organizer_avatar)
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    for (tag in listOf(C.Tag.create_event_organized_by, C.Tag.create_event_verified_badge)) {
      node(tag).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
    }
  }

  @Test
  fun editingAnEvent_aVerifiedAssociationSeesItsName() {
    showEditEvent()

    assertAssociationRow("Test Club", "TC")
  }

  @Test
  fun editingAnEvent_aStudentSeesOrganizedByYou() {
    profiles.getProfileResult = ProfileResult.Found(studentProfile())

    showEditEvent()

    assertStudentRow()
  }

  /** Where the title field starts, to check that nothing above it moves. */
  private fun titleTop() = node(C.Tag.create_event_title).getBoundsInRoot().top

  // Exact heights need native graphics: the legacy mode gives every text line a fake height.
  @Config(qualifiers = "w360dp-h800dp")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun theAssociationRow_appearsWithoutMovingTheForm() {
    profiles.gate = CompletableDeferred()
    showCreateEvent()
    node(C.Tag.create_event_organizer_loading).assertHeightIsEqualTo(24.dp)
    val titleTopWhileLoading = titleTop()

    profiles.gate!!.complete(Unit)
    composeTestRule.waitForIdle()

    node(C.Tag.create_event_organizer_avatar)
        .assertWidthIsEqualTo(24.dp)
        .assertHeightIsEqualTo(24.dp)
    node(C.Tag.create_event_verified_badge).assertHeightIsEqualTo(24.dp)
    assertEquals(titleTopWhileLoading, titleTop())
    // A short name fits whole (only native graphics would shorten it).
    assertFalse(
        "A short name is shortened",
        textLayout(C.Tag.create_event_organized_by).isLineEllipsized(0),
    )
  }

  // Native graphics draw like a phone, so the capture shows what the row really draws.
  @Config(qualifiers = "w360dp-h800dp")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun whileTheOrganizerLoads_theRowDrawsNothing() {
    // The row holds "Organized by you" for its height only: an association must never see it.
    profiles.gate = CompletableDeferred()
    showCreateEvent()

    val pixels = node(C.Tag.create_event_organizer_loading).captureToImage().toPixelMap()
    assertTrue(pixels.width > 0 && pixels.height > 0)
    for (x in 0 until pixels.width) {
      for (y in 0 until pixels.height) {
        assertEquals("A pixel of the empty row at ($x, $y)", Bg, pixels[x, y])
      }
    }
  }

  @Test
  fun theStudentRow_appearsWithoutMovingTheForm() {
    profiles.getProfileResult = ProfileResult.Found(studentProfile())
    profiles.gate = CompletableDeferred()
    showCreateEvent()
    val titleTopWhileLoading = titleTop()

    profiles.gate!!.complete(Unit)
    composeTestRule.waitForIdle()

    assertStudentRow()
    assertEquals(titleTopWhileLoading, titleTop())
  }
}
