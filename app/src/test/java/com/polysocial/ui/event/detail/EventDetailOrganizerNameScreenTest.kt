// Contributors: Claude Fable 5.2 (wrote these tests of a student organizer's name on the event
// detail screen, #281).
// Contributors: Claude Fable 5.2 (testing pass: the long name's lines, and no name anywhere on the
// screen of a student who isn't going, #281).
package com.polysocial.ui.event.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.Event
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.ui.event.CLUB_NAME
import com.polysocial.ui.event.STUDENT_UID
import com.polysocial.ui.event.associationEvent
import com.polysocial.ui.event.studentEvent
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A student's event shows "by <name>" under its title to the students going and to its organizers,
 * like an association's event without the Verified badge (#281; Figma has no frame for it): first
 * the screen alone, then with its ViewModel and fake repositories. Every name is fictional.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class EventDetailOrganizerNameScreenTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val zone = ZoneId.of("Europe/Zurich")
  /** The day before the event starts. */
  private val clock = Clock.fixed(TEST_NOW, zone)
  private val study = studentEvent(validEvent(title = "Study")).copy(id = EVENT_ID)
  private val clubEvent = associationEvent(validEvent(title = "Study")).copy(id = EVENT_ID)

  private fun loaded(
      event: Event,
      organizerName: String?,
      isOrganizer: Boolean = false,
      associationName: String? = null,
  ) =
      EventDetailUiState.Loaded(
          eventDetails(event, zone),
          isOffline = false,
          isOrganizer = isOrganizer,
          associationName = associationName,
          organizerName = organizerName,
          attendance = if (isOrganizer) Attendance.NOT_GOING else Attendance.GOING,
      )

  private fun show(state: EventDetailUiState) {
    composeTestRule.setContent {
      PolySocialTheme { EventDetailScreen(state, onBack = {}, onRetry = {}) }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  @Test
  fun aStudentsEvent_withItsOrganizersName_showsByTheName_withoutTheVerifiedBadge() {
    show(loaded(study, ORGANIZER_NAME))

    node(EventDetailTags.ORGANIZER_NAME).assertIsDisplayed().assertTextEquals("by $ORGANIZER_NAME")
    node(EventDetailTags.VERIFIED_BADGE).assertDoesNotExist()
    node(EventDetailTags.ASSOCIATION_NAME).assertDoesNotExist()
    node(EventDetailTags.ORGANIZED_BY_YOU).assertDoesNotExist()
  }

  @Test
  fun aStudentsEvent_withoutTheName_showsNoOrganizer() {
    // A student who isn't going, or a profile that is missing or couldn't be read.
    show(loaded(study, organizerName = null))

    node(EventDetailTags.TITLE).assertIsDisplayed()
    node(EventDetailTags.ORGANIZER_NAME).assertDoesNotExist()
    node(EventDetailTags.VERIFIED_BADGE).assertDoesNotExist()
  }

  @Test
  fun anOrganizer_keepsOrganizedByYou_andSeesTheNameToo() {
    show(loaded(study, ORGANIZER_NAME, isOrganizer = true))

    node(EventDetailTags.ORGANIZED_BY_YOU).assertIsDisplayed()
    node(EventDetailTags.ORGANIZER_NAME).assertIsDisplayed().assertTextEquals("by $ORGANIZER_NAME")
  }

  @Test
  fun anAssociationsEvent_showsItsAssociation_neverAStudentsName() {
    // Even with an organizer's name in the state, an association's event is unchanged.
    show(loaded(clubEvent, ORGANIZER_NAME, associationName = CLUB_NAME))

    node(EventDetailTags.ASSOCIATION_NAME).assertIsDisplayed().assertTextEquals("by $CLUB_NAME")
    composeTestRule.onNodeWithTag(EventDetailTags.VERIFIED_BADGE).assertTextEquals("Verified")
    node(EventDetailTags.ORGANIZER_NAME).assertDoesNotExist()
  }

  // Native graphics measure text like a phone, at the Figma frame's size.
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun theName_sitsWhereAnAssociationsNameDoes_andLeavesTheRowsBelowInPlace() {
    var state by
        mutableStateOf(loaded(clubEvent, organizerName = null, associationName = CLUB_NAME))
    composeTestRule.setContent {
      PolySocialTheme { EventDetailScreen(state, onBack = {}, onRetry = {}) }
    }
    val association = node(EventDetailTags.ASSOCIATION_NAME).getBoundsInRoot()
    val dateBelowAssociation = node(EventDetailTags.DATE).getBoundsInRoot()

    state = loaded(study, CLUB_NAME)
    composeTestRule.waitForIdle()

    val organizer = node(EventDetailTags.ORGANIZER_NAME).getBoundsInRoot()
    assertEquals(association.left.value, organizer.left.value, HALF_A_DP)
    assertEquals(association.top.value, organizer.top.value, HALF_A_DP)
    assertEquals(association.bottom.value, organizer.bottom.value, HALF_A_DP)
    val dateBelowOrganizer = node(EventDetailTags.DATE).getBoundsInRoot()
    assertEquals(dateBelowAssociation.top.value, dateBelowOrganizer.top.value, HALF_A_DP)
  }

  // The largest font size Android offers, with native graphics, which measure text like a phone.
  @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theLongestNameWraps_insideTheScreen() {
    // The longest display name the rules accept (#143).
    val longName = "Organizer " + "x".repeat(40)
    show(loaded(study, longName))

    node(EventDetailTags.ORGANIZER_NAME).assertIsDisplayed().assertTextEquals("by $longName")
    val screen = composeTestRule.onNodeWithTag(EventDetailTags.SCREEN).getBoundsInRoot()
    val name = node(EventDetailTags.ORGANIZER_NAME).getBoundsInRoot()
    assertTrue("The name ends past the screen", name.right <= screen.right)
    val date = node(EventDetailTags.DATE).getBoundsInRoot()
    assertTrue("The name runs into the date", name.bottom <= date.top)
  }

  // Wrapping, not one line cut short: the whole name stays readable.
  @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theLongestNameTakesSeveralLines_insteadOfBeingCut() {
    var state by mutableStateOf(loaded(study, "Olivia"))
    composeTestRule.setContent {
      PolySocialTheme { EventDetailScreen(state, onBack = {}, onRetry = {}) }
    }
    val oneLine = node(EventDetailTags.ORGANIZER_NAME).getBoundsInRoot()

    state = loaded(study, "Organizer " + "x".repeat(40))
    composeTestRule.waitForIdle()

    val wrapped = node(EventDetailTags.ORGANIZER_NAME).getBoundsInRoot()
    assertTrue("The longest name is cut to one line", wrapped.height >= oneLine.height * 2)
  }

  /** The screen with its real ViewModel, for the student [VIEWER], who isn't an organizer. */
  private fun showRoute(
      registrations: FakeRegistrationRepository,
      profiles: FakePublicProfileRepository = organizerProfile(),
  ) {
    val viewModel =
        EventDetailViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT_ID)),
            FakeEventRepository().apply { seed(study) },
            FakeNetworkMonitor(),
            FakeAuthRepository(AuthUser(VIEWER, "fictional.student@epfl.ch", true)),
            clock,
            FakeAssociationNames(),
            registrations,
            FakeGroupRepository(),
            profiles,
            FakeReminderScheduler(clock),
        )
    composeTestRule.setContent {
      PolySocialTheme { EventDetailRoute(onBack = {}, viewModel = viewModel) }
    }
    settle()
  }

  /** Runs the ViewModel's coroutines, which wait on the test's main dispatcher. */
  private fun settle() {
    repeat(2) {
      composeTestRule.waitForIdle()
      mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }
    composeTestRule.waitForIdle()
  }

  private fun registrations() =
      FakeRegistrationRepository(currentUid = VIEWER).apply { readableEventIds += EVENT_ID }

  private fun organizerProfile() =
      FakePublicProfileRepository(
          mutableMapOf(STUDENT_UID to PublicProfile(STUDENT_UID, ORGANIZER_NAME, "IN", "BA3"))
      )

  @Test
  fun route_registeringShowsTheName_andCancellingRemovesIt() {
    val registrations = registrations()
    showRoute(registrations)
    node(EventDetailTags.TITLE).assertIsDisplayed()
    node(EventDetailTags.ORGANIZER_NAME).assertDoesNotExist()

    node(EventDetailTags.IM_GOING).performClick()
    settle()

    node(EventDetailTags.GOING).assertIsDisplayed()
    node(EventDetailTags.ORGANIZER_NAME).assertIsDisplayed().assertTextEquals("by $ORGANIZER_NAME")

    node(EventDetailTags.CANCEL_REGISTRATION).performClick()
    settle()

    node(EventDetailTags.IM_GOING).assertIsDisplayed()
    node(EventDetailTags.ORGANIZER_NAME).assertDoesNotExist()
  }

  @Test
  fun route_aStudentWhoIsntGoing_findsTheOrganizersNameNowhere_andNoProfileIsRead() {
    val profiles = organizerProfile()
    showRoute(registrations(), profiles)

    node(EventDetailTags.IM_GOING).assertIsDisplayed()
    // Neither as a text nor as a description a screen reader would say.
    composeTestRule
        .onAllNodes(hasText(ORGANIZER_NAME, substring = true), useUnmergedTree = true)
        .assertCountEquals(0)
    composeTestRule
        .onAllNodes(hasContentDescription(ORGANIZER_NAME, substring = true), useUnmergedTree = true)
        .assertCountEquals(0)
    assertTrue(profiles.requestedUids.isEmpty())
  }

  @Test
  fun route_aProfileThatCantBeRead_showsTheEventWithoutAName() {
    val registrations = registrations().apply { seedRegistration(EVENT_ID, VIEWER) }
    val profiles =
        organizerProfile().apply { failure = FakePublicProfileRepository.Failure.Unexpected }
    showRoute(registrations, profiles)

    node(EventDetailTags.TITLE).assertIsDisplayed().assertTextEquals("Study")
    node(EventDetailTags.GOING).assertIsDisplayed()
    node(EventDetailTags.ORGANIZER_NAME).assertDoesNotExist()
  }

  private companion object {
    const val EVENT_ID = "study"
    const val VIEWER = "viewer-uid"
    const val ORGANIZER_NAME = "Olivia Organizer"
    const val HALF_A_DP = 0.5f
  }
}
