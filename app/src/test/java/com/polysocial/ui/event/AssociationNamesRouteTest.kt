// Contributors: Claude (wrote these tests, #128).
package com.polysocial.ui.event

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.ui.event.detail.EventDetailRoute
import com.polysocial.ui.event.detail.EventDetailTags
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.event.list.EventsRoute
import com.polysocial.ui.event.list.EventsTags
import com.polysocial.ui.event.list.EventsViewModel
import com.polysocial.ui.theme.PolySocialTheme
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Events tab and event detail, each with its ViewModel over fake repositories (#128): an
 * association's event shows its name and badge, a student's event neither, and a name that can't be
 * read leaves the badge alone.
 */
@RunWith(AndroidJUnit4::class)
class AssociationNamesRouteTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val zone = ZoneId.of("Europe/Zurich")
  private val clock = Clock.fixed(TEST_NOW, zone)
  private val online =
      object : NetworkMonitor {
        override fun isOnline() = true
      }
  private val events = FakeEventRepository(now = TEST_NOW)

  private val party =
      associationEvent(validEvent(title = "Party", startTime = TEST_NOW.plusSeconds(3600)))
          .copy(id = "party", category = EventCategory.PARTY)
  private val study =
      studentEvent(validEvent(title = "Study", startTime = TEST_NOW.plus(Duration.ofHours(2))))
          .copy(id = "study", category = EventCategory.STUDY)

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  private fun pill(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun showEventsTab(fixture: NamesFixture) {
    events.seed(party, study)
    val viewModel =
        EventsViewModel(
            events,
            online,
            FakeLocationService(permissionGranted = true),
            clock,
            SavedStateHandle(),
            fixture.names,
        )
    composeTestRule.setContent {
      PolySocialTheme { EventsRoute(onEventClick = {}, viewModel = viewModel) }
    }
    composeTestRule.waitForIdle()
  }

  private fun scrollTo(eventId: String) {
    composeTestRule
        .onNodeWithTag(EventsTags.LIST)
        .performScrollToNode(hasTestTag(EventsTags.card(eventId)))
  }

  private fun showDetail(eventId: String, fixture: NamesFixture) {
    events.seed(party, study)
    val viewModel =
        EventDetailViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to eventId)),
            events,
            online,
            fixture.auth,
            clock,
            fixture.names,
        )
    composeTestRule.setContent {
      PolySocialTheme { EventDetailRoute(onBack = {}, viewModel = viewModel) }
    }
    composeTestRule.waitForIdle()
  }

  @Test
  fun eventsTab_anAssociationsCardShowsItsNameAndBadge_aStudentsShowsNeither() {
    val fixture = NamesFixture()
    showEventsTab(fixture)

    scrollTo("party")
    pill(EventsTags.verified("party")).assertIsDisplayed().assertTextEquals("Verified")
    node(EventsTags.details("party")).assertTextEquals("Party · 14:00 – 16:00 · by $CLUB_NAME")
    scrollTo("study")
    pill(EventsTags.verified("study")).assertDoesNotExist()
    node(EventsTags.details("study")).assertTextEquals("Study · 15:00 – 17:00")
    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
  }

  @Test
  fun eventsTab_aNameThatCantBeRead_leavesTheBadgeAlone() {
    showEventsTab(NamesFixture(AssociationResult.NetworkError))

    scrollTo("party")
    pill(EventsTags.verified("party")).assertIsDisplayed()
    node(EventsTags.details("party")).assertTextEquals("Party · 14:00 – 16:00")
  }

  @Test
  fun detail_anAssociationsEventShowsByItsNameAndTheBadge() {
    showDetail("party", NamesFixture())

    node(EventDetailTags.ASSOCIATION_NAME).assertIsDisplayed().assertTextEquals("by $CLUB_NAME")
    pill(EventDetailTags.VERIFIED_BADGE).assertTextEquals("Verified")
  }

  @Test
  fun detail_aNameThatCantBeRead_showsVerifiedAssociation() {
    showDetail("party", NamesFixture(AssociationResult.NetworkError))

    node(EventDetailTags.ASSOCIATION_NAME).assertDoesNotExist()
    pill(EventDetailTags.VERIFIED_BADGE).assertTextEquals("Verified association")
  }

  @Test
  fun detail_aStudentsEventShowsNeither() {
    showDetail("study", NamesFixture())

    node(EventDetailTags.ASSOCIATION_NAME).assertDoesNotExist()
    pill(EventDetailTags.VERIFIED_BADGE).assertDoesNotExist()
  }
}
