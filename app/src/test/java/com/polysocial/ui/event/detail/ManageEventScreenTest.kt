// Contributors: Claude (wrote these tests of the association's manage event, #131).
package com.polysocial.ui.event.detail

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.event.withCreator
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.AttendeeCountResult
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class ManageEventScreenTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun string(id: Int) = context.getString(id)

  private val noCount by lazy { string(R.string.manage_event_no_count) }

  private var backs = 0
  private var edits = 0
  private var cancelTaps = 0
  private var goingRetries = 0

  /** A verified association's public party, organized by the signed-in association. */
  private val party =
      validEvent(title = "Balélec warm-up")
          .copy(id = "party", category = EventCategory.PARTY, locationName = "Satellite")
          .withCreator("association", isVerifiedAssociation = true)

  /** Friday 2 October 2026, 20:00 to 01:00 the next day. */
  private fun managed(
      going: GoingCount = GoingCount.Count(86),
      isLive: Boolean = false,
      isOffline: Boolean = false,
      isPrivate: Boolean = false,
      locationName: String = "Satellite",
  ) =
      EventDetailUiState.Loaded(
          EventDetails(
              event = party.copy(isPrivate = isPrivate, locationName = locationName),
              date = LocalDate.of(2026, 10, 2),
              startTime = LocalTime.of(20, 0),
              endTime = LocalTime.of(1, 0),
              endDayOffset = 1,
          ),
          isOffline = isOffline,
          isOrganizer = true,
          going = going,
          isLive = isLive,
      )

  private fun show(state: EventDetailUiState.Loaded) {
    composeTestRule.setContent {
      PolySocialTheme {
        EventDetailScreen(
            state,
            onBack = { backs++ },
            onRetry = {},
            organizer =
                EventOrganizerActions(onEdit = { edits++ }, onCancelEvent = { cancelTaps++ }),
            onRetryGoing = { goingRetries++ },
        )
      }
    }
  }

  /** Lets the screen and the ViewModel finish, like EventDetailScreenTest's settle. */
  private fun settle() {
    repeat(2) {
      composeTestRule.waitForIdle()
      mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }
    composeTestRule.waitForIdle()
  }

  /** The number on the Going tile, which the tile merges into itself while it is tappable. */
  private fun goingCount() =
      composeTestRule.onNodeWithTag(ManageEventTags.GOING_COUNT, useUnmergedTree = true)

  private fun assertAbsent(tag: String) =
      composeTestRule.onAllNodesWithTag(tag).assertCountEquals(0)

  @Test
  fun anAssociationsOwnEvent_showsTheManageScreen_notTheStudentsDetail() {
    show(managed())

    composeTestRule.onNodeWithTag(ManageEventTags.SCREEN).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.manage_event_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(ManageEventTags.TITLE).assertTextEquals("Balélec warm-up")
    composeTestRule
        .onNodeWithTag(ManageEventTags.WHEN_WHERE)
        .assertTextEquals("Fri 2 Oct · 20:00 – 01:00 (+1 day) · Satellite")
    composeTestRule
        .onNodeWithTag(ManageEventTags.VISIBILITY)
        .assertTextEquals(string(R.string.association_events_public))
    goingCount().assertTextEquals("86")
    composeTestRule.onNodeWithText(string(R.string.manage_event_counts_only)).assertIsDisplayed()
    assertAbsent(ManageEventTags.LIVE)
    assertAbsent(ManageEventTags.OFFLINE_BANNER)
    // None of the students' detail: no Find a group, no "Organized by you".
    assertAbsent(EventDetailTags.FIND_GROUP)
    assertAbsent(EventDetailTags.ORGANIZED_BY_YOU)
  }

  @Test
  fun aRunningPrivateEventWithoutAPlace_showsLiveNowPrivateAndOnlyItsTimes() {
    show(managed(isLive = true, isPrivate = true, locationName = ""))

    composeTestRule
        .onNodeWithTag(ManageEventTags.LIVE)
        .assertTextEquals(string(R.string.manage_event_live))
    composeTestRule
        .onNodeWithTag(ManageEventTags.VISIBILITY)
        .assertTextEquals(string(R.string.association_events_private))
    composeTestRule
        .onNodeWithTag(ManageEventTags.WHEN_WHERE)
        .assertTextEquals("Fri 2 Oct · 20:00 – 01:00 (+1 day)")
  }

  @Test
  fun backEditAndCancelEvent_callTheirActions() {
    show(managed())

    composeTestRule.onNodeWithTag(ManageEventTags.BACK).performClick()
    composeTestRule.onNodeWithTag(ManageEventTags.EDIT).performClick()
    composeTestRule.onNodeWithTag(ManageEventTags.CANCEL_EVENT).performClick()

    assertEquals(1, backs)
    assertEquals(1, edits)
    assertEquals(1, cancelTaps)
  }

  @Test
  fun theCountsNotBuiltYet_theQrAndTheAlbum_sayTheyArentAvailable() {
    show(managed())
    val notAvailable = string(R.string.manage_event_not_available)

    listOf(
            ManageEventTags.LOOKING_FOR_GROUP,
            ManageEventTags.GROUPS_FORMED,
            ManageEventTags.CHECKED_IN,
            ManageEventTags.SHOW_FULL_SCREEN,
            ManageEventTags.DOWNLOAD_PDF,
            ManageEventTags.ALBUM,
        )
        .forEach { tag ->
          composeTestRule.onNodeWithTag(tag).performClick()
          composeTestRule.waitForIdle()
          // One message at a time: a new tap replaces the previous one.
          composeTestRule.onAllNodesWithText(notAvailable).assertCountEquals(1)
        }
    // The three counts that aren't built show no number.
    composeTestRule.onAllNodesWithText(noCount).assertCountEquals(3)
  }

  @Test
  fun aCountStillLoading_showsNoNumber_andTappingItDoesNothing() {
    show(managed(going = GoingCount.Loading))

    goingCount().assertTextEquals(noCount)
    composeTestRule.onNodeWithTag(ManageEventTags.GOING).performClick()

    assertEquals(0, goingRetries)
  }

  @Test
  fun aCountThatCouldntBeRead_saysSo_andTappingItCountsAgain() {
    show(managed(going = GoingCount.Unavailable))

    composeTestRule
        .onNode(hasContentDescription(string(R.string.manage_event_count_unavailable)))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(ManageEventTags.GOING).performClick()

    assertEquals(1, goingRetries)
  }

  @Test
  fun offline_showsTheOfflineBannerAboveCancelEvent() {
    show(managed(isOffline = true))

    composeTestRule.onNodeWithTag(ManageEventTags.OFFLINE_BANNER).assertIsDisplayed()
    composeTestRule.onNodeWithTag(ManageEventTags.CANCEL_EVENT).assertIsDisplayed()
  }

  // The largest font size Android offers, with native graphics, which measure text like a phone.
  @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_theChipsCountsAndQrButtonsKeepTheirTextWhole() {
    show(managed(isLive = true))

    assertNothingCutOff(ManageEventTags.SCREEN)
    assertTextInside(string(R.string.manage_event_live), ManageEventTags.LIVE)
    assertTextInside(string(R.string.association_events_public), ManageEventTags.VISIBILITY)
    assertTextInside(string(R.string.manage_event_going), ManageEventTags.GOING)
    assertTextInside(
        string(R.string.manage_event_looking_for_group),
        ManageEventTags.LOOKING_FOR_GROUP,
    )
    assertTextInside(string(R.string.manage_event_groups_formed), ManageEventTags.GROUPS_FORMED)
    assertTextInside(string(R.string.manage_event_checked_in), ManageEventTags.CHECKED_IN)
    assertTextInside(
        string(R.string.manage_event_show_full_screen),
        ManageEventTags.SHOW_FULL_SCREEN,
    )
    assertTextInside(string(R.string.manage_event_download_pdf), ManageEventTags.DOWNLOAD_PDF)
    assertTextInside(string(R.string.manage_event_album), ManageEventTags.ALBUM)
    assertTextInside(string(R.string.event_detail_edit), ManageEventTags.EDIT)
  }

  /**
   * Every text under [container] is drawn whole: no line shortened, nothing overflowing its box,
   * and inside the screen's width (like PendingAssociationScreensTest's).
   */
  private fun assertNothingCutOff(container: String) {
    val screenWidth = composeTestRule.onRoot().fetchSemanticsNode().size.width
    val texts =
        composeTestRule
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

  /** The text [text] lies wholly inside the node tagged [container] (a tile, a chip, a button). */
  private fun assertTextInside(text: String, container: String) {
    val inner =
        composeTestRule
            .onNode(hasText(text) and hasAnyAncestor(hasTestTag(container)), useUnmergedTree = true)
            .getBoundsInRoot()
    val outer = composeTestRule.onNodeWithTag(container, useUnmergedTree = true).getBoundsInRoot()
    val slack = 0.5.dp
    assertTrue(
        "\"$text\" ($inner) spills out of $container ($outer)",
        inner.left >= outer.left - slack &&
            inner.top >= outer.top - slack &&
            inner.right <= outer.right + slack &&
            inner.bottom <= outer.bottom + slack,
    )
  }

  private fun layoutOf(node: SemanticsNode): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
    return results.single()
  }

  // Through the route and the ViewModel

  private val events = FakeEventRepository()
  private val registrations = FakeRegistrationRepository()

  /** The route for [party], signed in as its association, against the fake repositories. */
  private fun showRoute(onCancelled: () -> Unit = {}) {
    events.currentUid = "association"
    events.seed(party)
    val viewModel =
        EventDetailViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to "party")),
            events,
            object : NetworkMonitor {
              override fun isOnline() = true
            },
            FakeAuthRepository(AuthUser("association", "association@example.com", true)),
            Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneId.of("Europe/Zurich")),
            FakeAssociationNames(),
            registrations,
        )
    composeTestRule.setContent {
      PolySocialTheme {
        EventDetailRoute(onBack = {}, onCancelled = onCancelled, viewModel = viewModel)
      }
    }
    settle()
  }

  @Test
  fun theGoingCount_comesFromTheRegistrations() {
    listOf("s1", "s2").forEach { registrations.seedRegistration("party", it) }

    showRoute()

    goingCount().assertTextEquals("2")
  }

  @Test
  fun aFailedCount_canBeTriedAgainByTappingIt() {
    registrations.seedRegistration("party", "s1")
    registrations.countFailure = AttendeeCountResult.NetworkError
    showRoute()
    goingCount().assertTextEquals(noCount)

    registrations.countFailure = null
    composeTestRule.onNodeWithTag(ManageEventTags.GOING).performClick()
    settle()

    goingCount().assertTextEquals("1")
  }

  @Test
  fun confirmingCancelEvent_deletesTheEventAndLeaves() {
    var cancelled = 0
    showRoute(onCancelled = { cancelled++ })

    composeTestRule.onNodeWithTag(ManageEventTags.CANCEL_EVENT).performClick()
    settle()
    composeTestRule.onNodeWithTag(EventDetailTags.CANCEL_SHEET).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventDetailTags.CONFIRM_CANCEL).performClick()
    settle()

    assertEquals(listOf("party"), events.deleteRequests)
    assertEquals(1, cancelled)
  }
}
