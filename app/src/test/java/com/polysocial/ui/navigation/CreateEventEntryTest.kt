// Contributors: Claude (wrote these tests, #46).
package com.polysocial.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "+" on the Events and Map tabs and the navigation around Create Event. A stand-in replaces
 * the real screen, which gets its ViewModel from Hilt (see [CreateEventEntryHiltTest]).
 */
@RunWith(AndroidJUnit4::class)
class CreateEventEntryTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var navController: NavHostController

  @Before
  fun setUp() {
    composeTestRule.setContent {
      navController = rememberNavController()
      PolySocialTheme {
        AppShell(navController = navController) { actions ->
          Column(Modifier.testTag(C.Tag.create_event_screen)) {
            Button(onClick = actions.onClose, modifier = Modifier.testTag(CLOSE)) { Text("Close") }
            Button(
                onClick = { actions.onViewEvent("event-1") },
                modifier = Modifier.testTag(VIEW_EVENT),
            ) {
              Text("View event")
            }
            Button(onClick = actions.onBackToMap, modifier = Modifier.testTag(BACK_TO_MAP)) {
              Text("Back to map")
            }
          }
        }
      }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun assertAbsent(tag: String) =
      composeTestRule.onAllNodesWithTag(tag).assertCountEquals(0)

  private fun select(tab: Tab) = node(tab.navItemTag).performClick()

  private fun openCreateEvent(from: Tab) {
    select(from)
    node(C.Tag.create_event_button).performClick()
    node(C.Tag.create_event_screen).assertIsDisplayed()
  }

  private fun pressBack() {
    composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeTestRule.waitForIdle()
  }

  private fun currentRoute() = navController.currentBackStackEntry?.destination?.route

  @Test
  fun thePlusButton_showsOnlyOnTheEventsAndMapRoots() {
    Tab.entries.forEach { tab ->
      select(tab)
      if (tab.canCreateEvent) node(C.Tag.create_event_button).assertIsDisplayed()
      else assertAbsent(C.Tag.create_event_button)
    }

    composeTestRule.runOnIdle { navController.navigate(Tab.MAP.detailRoute) }
    assertAbsent(C.Tag.create_event_button)
  }

  @Test
  fun createEvent_opensFullScreenFromEitherTab() {
    listOf(Tab.EVENTS, Tab.MAP).forEach { tab ->
      openCreateEvent(from = tab)

      assertEquals(tab.createEventRoute, currentRoute())
      assertAbsent(C.Tag.app_bar_title)
      assertAbsent(C.Tag.bottom_nav)
      assertAbsent(C.Tag.create_event_button)

      node(CLOSE).performClick()
    }
  }

  @Test
  fun closingTheForm_goesBackToTheTabRoot() {
    openCreateEvent(from = Tab.EVENTS)

    node(CLOSE).performClick()

    node(Tab.EVENTS.screenTag).assertIsDisplayed()
    node(C.Tag.bottom_nav).assertIsDisplayed()
  }

  @Test
  fun backFromTheForm_goesBackToTheTabRoot() {
    openCreateEvent(from = Tab.MAP)

    pressBack()

    node(Tab.MAP.screenTag).assertIsDisplayed()
  }

  @Test
  fun viewEvent_replacesTheFormWithTheEventDetail() {
    openCreateEvent(from = Tab.EVENTS)

    node(VIEW_EVENT).performClick()

    node(Tab.EVENTS.detailTag).assertIsDisplayed()
    assertEquals(Tab.EVENTS.eventDetailRoute, currentRoute())
    assertEquals(
        "event-1",
        navController.currentBackStackEntry?.arguments?.getString("eventId"),
    )

    pressBack()

    node(Tab.EVENTS.screenTag).assertIsDisplayed()
    assertAbsent(C.Tag.create_event_screen)
  }

  @Test
  fun backToMap_fromTheEventsTab_showsTheMapAndClosesTheForm() {
    openCreateEvent(from = Tab.EVENTS)

    node(BACK_TO_MAP).performClick()

    node(Tab.MAP.screenTag).assertIsDisplayed()
    node(Tab.MAP.navItemTag).assertIsSelected()

    select(Tab.EVENTS)

    node(Tab.EVENTS.screenTag).assertIsDisplayed()
    assertAbsent(C.Tag.create_event_screen)
  }

  @Test
  fun backToMap_fromTheMapTab_showsTheMapRoot() {
    openCreateEvent(from = Tab.MAP)

    node(BACK_TO_MAP).performClick()

    node(Tab.MAP.screenTag).assertIsDisplayed()
    assertEquals(Tab.MAP.rootRoute, currentRoute())
  }

  @Test
  fun theEventDetailRoute_encodesTheEventId() {
    assertEquals("map/event/a%2Fb", Tab.MAP.eventDetailRoute("a/b"))
  }

  private companion object {
    const val CLOSE = "test_close"
    const val VIEW_EVENT = "test_view_event"
    const val BACK_TO_MAP = "test_back_to_map"
  }
}
