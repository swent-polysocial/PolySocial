// Contributors: Claude (wrote these tests of opening an event's detail from a tab, #112; double
// taps during the transition, after review; Edit event above the detail, #131; Edit event closing
// during its enter transition, #131 review).
package com.polysocial.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.validEvent
import com.polysocial.resources.C
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.event.list.EventDaySection
import com.polysocial.ui.event.list.EventsListState
import com.polysocial.ui.event.list.EventsScreen
import com.polysocial.ui.event.list.EventsTags
import com.polysocial.ui.event.list.EventsUiState
import com.polysocial.ui.theme.PolySocialTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opening an event's detail inside a tab's graph: the real Events list opens it, the detail hides
 * the app shell's bars, and Back returns to the tab it was opened from. The detail itself is a
 * stand-in that shows the event ID it received, since the real one needs Hilt.
 */
@RunWith(AndroidJUnit4::class)
class EventDetailNavigationTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var navController: NavHostController

  /** Whether the fake Edit event closes as soon as it opens, like one whose event can't be read. */
  private var editClosesAtOnce = false

  private val zone = ZoneId.of("Europe/Zurich")

  /**
   * An ID with a space and a slash, which only reach the screen intact if the route encodes them.
   */
  private val trickyId = "party 1/2"

  private val events =
      EventsListState.Events(
          listOf(
              EventDaySection(
                  TODAY,
                  listOf(
                      validEvent(title = "Party")
                          .copy(id = trickyId, category = EventCategory.PARTY),
                      validEvent(title = "Climbing")
                          .copy(id = "climbing", category = EventCategory.SPORTS),
                  ),
              )
          ),
          today = TODAY,
          isOffline = false,
      )

  @Before
  fun setUp() {
    composeTestRule.setContent {
      navController = rememberNavController()
      PolySocialTheme {
        AppShell(
            navController = navController,
            eventsContent = { onEventClick ->
              EventsScreen(
                  EventsUiState(events),
                  zone = zone,
                  today = TODAY,
                  onRetry = {},
                  onClearFilters = {},
                  onSearchChange = {},
                  onSelectDate = {},
                  onToggleCategory = {},
                  onClearSheetFilters = {},
                  onSelectDistance = {},
                  onTurnOnLocation = {},
                  onEventClick = onEventClick,
              )
            },
            // The real map needs Hilt and the native renderer; the detail route is what's tested.
            mapContent = { _, _ -> TabRootScreen(Tab.MAP) },
            eventDetailContent = { onBack ->
              val id =
                  navController.currentBackStackEntry
                      ?.arguments
                      ?.getString(EventDetailViewModel.EVENT_ID_ARG)
              Button(onClick = onBack, modifier = Modifier.testTag(DETAIL)) {
                Text(id.orEmpty(), Modifier.testTag(DETAIL_ID))
              }
            },
            editEventContent = { onClose ->
              val id =
                  navController.currentBackStackEntry
                      ?.arguments
                      ?.getString(EventDetailViewModel.EVENT_ID_ARG)
              LaunchedEffect(Unit) { if (editClosesAtOnce) onClose() }
              Button(onClick = onClose, modifier = Modifier.testTag(EDIT)) {
                Text(id.orEmpty(), Modifier.testTag(EDIT_ID))
              }
            },
        )
      }
    }
  }

  private fun pressBack() {
    composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeTestRule.waitForIdle()
  }

  private fun assertBarsHidden() {
    composeTestRule.onAllNodesWithTag(C.Tag.app_bar_title).assertCountEquals(0)
    composeTestRule.onAllNodesWithTag(C.Tag.bottom_nav).assertCountEquals(0)
  }

  private fun assertOnRootOf(tab: Tab) {
    composeTestRule.onAllNodesWithTag(DETAIL).assertCountEquals(0)
    // The map draws floating overlays instead of the app bar (#94).
    if (tab == Tab.MAP) {
      composeTestRule.onAllNodesWithTag(C.Tag.app_bar_title).assertCountEquals(0)
    } else {
      composeTestRule.onNodeWithTag(C.Tag.app_bar_title).assertIsDisplayed()
    }
    composeTestRule.onNodeWithTag(C.Tag.bottom_nav).assertIsDisplayed()
    composeTestRule.onNodeWithTag(tab.navItemTag).assertIsSelected()
  }

  @Test
  fun eventDetailRoutes_areInsideEachTabsGraph_andEncodeTheId() {
    Tab.entries.forEach { tab ->
      assertEquals("${tab.route}/event/{eventId}", tab.eventDetailRoute)
      assertEquals("${tab.route}/event/party%201%2F2", tab.eventDetailRoute(trickyId))
    }
  }

  @Test
  fun tappingAnEventsCard_opensItsDetail_withTheIdIntact_andHidesTheBars() {
    composeTestRule.onNodeWithTag(EventsTags.card(trickyId)).performClick()

    composeTestRule.onNodeWithTag(DETAIL).assertIsDisplayed()
    composeTestRule.onNodeWithTag(DETAIL_ID, useUnmergedTree = true).assertTextEquals(trickyId)
    assertBarsHidden()
    composeTestRule.runOnIdle {
      assertEquals(Tab.EVENTS.eventDetailRoute, navController.currentDestination?.route)
    }
  }

  @Test
  fun anotherCard_opensTheDetailOfItsOwnEvent() {
    composeTestRule.onNodeWithTag(EventsTags.card("climbing")).performClick()

    composeTestRule.onNodeWithTag(DETAIL_ID, useUnmergedTree = true).assertTextEquals("climbing")
  }

  @Test
  fun systemBack_fromTheDetail_returnsToTheEventsList_withTheBarsBack() {
    composeTestRule.onNodeWithTag(EventsTags.card(trickyId)).performClick()
    composeTestRule.onNodeWithTag(DETAIL).assertIsDisplayed()

    pressBack()

    assertOnRootOf(Tab.EVENTS)
    composeTestRule.onNodeWithTag(EventsTags.card(trickyId)).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(C.Tag.app_bar_title)
        .assertTextEquals(composeTestRule.activity.getString(Tab.EVENTS.label))
  }

  @Test
  fun theDetailsBackAction_returnsToTheEventsList() {
    composeTestRule.onNodeWithTag(EventsTags.card(trickyId)).performClick()

    composeTestRule.onNodeWithTag(DETAIL).performClick()

    assertOnRootOf(Tab.EVENTS)
    composeTestRule.onNodeWithTag(EventsTags.card(trickyId)).assertIsDisplayed()
  }

  @Test
  fun theDetailRoute_worksInsideTheMapTab_andBackReturnsToTheMap() {
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()
    composeTestRule.onNodeWithTag(Tab.MAP.screenTag).assertIsDisplayed()

    composeTestRule.runOnIdle { navController.navigate(Tab.MAP.eventDetailRoute("x")) }

    composeTestRule.onNodeWithTag(DETAIL_ID, useUnmergedTree = true).assertTextEquals("x")
    assertBarsHidden()
    composeTestRule.runOnIdle {
      val entry = navController.currentBackStackEntry!!
      assertEquals(Tab.MAP.eventDetailRoute, entry.destination.route)
      assertEquals(Tab.MAP.route, entry.destination.parent?.route)
      // The Map tab's graph holds the detail above its root, so Back stays in the Map tab.
      navController.getBackStackEntry(Tab.MAP.rootRoute)
    }

    pressBack()

    assertOnRootOf(Tab.MAP)
    composeTestRule.onNodeWithTag(Tab.MAP.screenTag).assertIsDisplayed()
  }

  @Test
  fun aDoubleTapOnTheDetailsBack_returnsOnlyOnce_andNeverEmptiesTheStack() {
    composeTestRule.onNodeWithTag(EventsTags.card(trickyId)).performClick()
    composeTestRule.onNodeWithTag(DETAIL).assertIsDisplayed()

    // The second tap lands during the exit transition, before the detail is gone.
    composeTestRule.mainClock.autoAdvance = false
    try {
      composeTestRule.onNodeWithTag(DETAIL).performClick()
      composeTestRule.mainClock.advanceTimeBy(50)
      composeTestRule.onNodeWithTag(DETAIL).performClick()
    } finally {
      composeTestRule.mainClock.autoAdvance = true
    }
    composeTestRule.waitForIdle()

    assertOnRootOf(Tab.EVENTS)
    composeTestRule.runOnIdle {
      assertEquals(Tab.EVENTS.rootRoute, navController.currentDestination?.route)
    }
  }

  @Test
  fun aDoubleTapOnACard_opensTheDetailOnce() {
    composeTestRule.mainClock.autoAdvance = false
    try {
      composeTestRule.onNodeWithTag(EventsTags.card(trickyId)).performClick()
      composeTestRule.mainClock.advanceTimeBy(50)
      composeTestRule.onNodeWithTag(EventsTags.card(trickyId)).performClick()
    } finally {
      composeTestRule.mainClock.autoAdvance = true
    }
    composeTestRule.waitForIdle()

    pressBack()

    // One Back is enough: only one detail was opened.
    assertOnRootOf(Tab.EVENTS)
  }

  @Test
  fun editEvent_opensAboveTheDetail_fullScreen_andClosingReturnsToTheDetail() {
    composeTestRule.onNodeWithTag(EventsTags.card(trickyId)).performClick()
    composeTestRule.onNodeWithTag(DETAIL).assertIsDisplayed()

    composeTestRule.runOnIdle { navController.navigate(Tab.EVENTS.editEventRoute(trickyId)) }

    composeTestRule.onNodeWithTag(EDIT_ID, useUnmergedTree = true).assertTextEquals(trickyId)
    assertBarsHidden()
    composeTestRule.runOnIdle {
      assertEquals(Tab.EVENTS.editEventRoute, navController.currentDestination?.route)
    }

    composeTestRule.onNodeWithTag(EDIT).performClick()

    composeTestRule.onNodeWithTag(DETAIL_ID, useUnmergedTree = true).assertTextEquals(trickyId)
    pressBack()
    assertOnRootOf(Tab.EVENTS)
  }

  @Test
  fun editEvent_closingDuringItsEnterTransition_stillReturnsToTheDetail() {
    composeTestRule.onNodeWithTag(EventsTags.card(trickyId)).performClick()
    composeTestRule.onNodeWithTag(DETAIL).assertIsDisplayed()
    editClosesAtOnce = true

    composeTestRule.mainClock.autoAdvance = false
    try {
      composeTestRule.runOnIdle { navController.navigate(Tab.EVENTS.editEventRoute(trickyId)) }
      // Edit event closes in its first frames, long before its enter transition ends.
      composeTestRule.mainClock.advanceTimeByFrame()
      composeTestRule.mainClock.advanceTimeByFrame()
    } finally {
      composeTestRule.mainClock.autoAdvance = true
    }
    composeTestRule.waitForIdle()

    composeTestRule.runOnIdle {
      assertEquals(Tab.EVENTS.eventDetailRoute, navController.currentDestination?.route)
    }
    composeTestRule.onNodeWithTag(DETAIL_ID, useUnmergedTree = true).assertTextEquals(trickyId)
  }

  private companion object {
    const val DETAIL = "fake_event_detail"
    const val DETAIL_ID = "fake_event_detail_id"
    const val EDIT = "fake_edit_event"
    const val EDIT_ID = "fake_edit_event_id"
  }
}

private val TODAY = LocalDate.of(2026, 10, 11)
