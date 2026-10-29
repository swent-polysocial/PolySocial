// Contributors: OpenAI Codex (logout stack regression on the retained controller, #109).
// Contributors: Claude (UI tests for the bottom navigation and app bar, #41; nested tab routes,
// #42; placeholder Events root without Hilt, #105; map stub given the "+" action, #110).
// Contributors: OpenAI Codex (map tab chrome, detail routes and injected tab regression tests,
// #50).
// Contributors: Claude (the map's Details open the event detail, #112, after the M1 coach review).
package com.polysocial.ui.navigation

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var logout: () -> Unit
  private var loggedOut = 0
  private lateinit var navController: NavHostController

  private fun label(tab: Tab) = context.getString(tab.label)

  @Before
  fun setUp() {
    composeTestRule.setContent {
      navController = rememberNavController()
      PolySocialTheme {
        AppShell(
            navController = navController,
            profileContent = { done ->
              logout = done
              TabRootScreen(Tab.PROFILE)
            },
            onLoggedOut = { loggedOut++ },
            eventsContent = {
              Box(Modifier.testTag("injected_events_content")) { TabRootScreen(Tab.EVENTS) }
            },
            eventDetailContent = { navigation ->
              TextButton(navigation.onBack, Modifier.testTag(FAKE_DETAIL)) { Text("Back") }
            },
            mapContent = { details, _ ->
              Box(Modifier.testTag(C.Tag.screen_map)) {
                Column {
                  TextButton({ details("event/one") }, Modifier.testTag("fake_map_details")) {
                    Text("Open test event")
                  }
                  // A double tap: both taps reach the map before the detail opens.
                  TextButton(
                      {
                        details("event/one")
                        details("event/one")
                      },
                      Modifier.testTag("fake_map_details_twice"),
                  ) {
                    Text("Open test event twice")
                  }
                }
              }
            },
        )
      }
    }
  }

  private fun assertOnTab(tab: Tab) {
    composeTestRule.onNodeWithTag(tab.screenTag).assertIsDisplayed()
    if (tab == Tab.MAP) {
      composeTestRule.onNodeWithTag(C.Tag.app_bar_title).assertDoesNotExist()
    } else {
      composeTestRule.onNodeWithTag(C.Tag.app_bar_title).assertTextEquals(label(tab))
      composeTestRule
          .onNodeWithText(context.getString(R.string.placeholder_coming_soon, label(tab)))
          .assertIsDisplayed()
    }
    Tab.entries.forEach { other ->
      val item = composeTestRule.onNodeWithTag(other.navItemTag)
      if (other == tab) item.assertIsSelected() else item.assertIsNotSelected()
      if (other != tab) composeTestRule.onAllNodesWithTag(other.screenTag).assertCountEquals(0)
    }
  }

  @Test
  fun logoutClearsActiveAndSavedStacksOnTheSameController() {
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()
    // A deeper Map screen that keeps the bottom bar (the event detail hides it), so the test can
    // switch tabs and leave it in Map's saved stack.
    composeTestRule.runOnIdle { navController.navigate(Tab.MAP.detailRoute) }
    val mapEntry = composeTestRule.runOnIdle { navController.currentBackStackEntry!!.id }
    composeTestRule.onNodeWithTag(Tab.CHATS.navItemTag).performClick()
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()
    composeTestRule.runOnIdle {
      // Prove that this controller really keeps the previous account's saved stack.
      assertEquals(mapEntry, navController.currentBackStackEntry!!.id)
    }
    composeTestRule.onNodeWithTag(Tab.PROFILE.navItemTag).performClick()
    composeTestRule.runOnIdle {
      logout()
      assertEquals(1, loggedOut)
      assertNull(navController.currentBackStackEntry)
      assertNull(navController.previousBackStackEntry)
      // Restore on this same controller: a saved stack would resurrect the old private detail.
      navController.navigate(Tab.EVENTS.route)
      navController.navigate(Tab.MAP.route) { restoreState = true }
      assertEquals(Tab.MAP.rootRoute, navController.currentBackStackEntry!!.destination.route)
      assertNotEquals(mapEntry, navController.currentBackStackEntry!!.id)
    }
  }

  @Test
  fun mapDetails_openTheEventDetail_insideTheMapTab_andBackReturnsToTheMap() {
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()
    composeTestRule.onNodeWithTag("fake_map_details").performClick()

    // The real event detail, as from the Events tab (#112), never the old placeholder.
    composeTestRule.onNodeWithTag(FAKE_DETAIL).assertIsDisplayed()
    composeTestRule.onNodeWithTag("event_detail_placeholder").assertDoesNotExist()
    composeTestRule.runOnIdle {
      val entry = navController.currentBackStackEntry!!
      assertEquals(Tab.MAP.eventDetailRoute, entry.destination.route)
      assertEquals("event/one", entry.arguments?.getString(EventDetailViewModel.EVENT_ID_ARG))
    }
    composeTestRule.onNodeWithTag(FAKE_DETAIL).performClick()
    assertOnTab(Tab.MAP)
  }

  @Test
  fun aSecondTapOnDetails_opensTheEventDetailOnce() {
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()
    composeTestRule.onNodeWithTag("fake_map_details_twice").performClick()
    composeTestRule.onNodeWithTag(FAKE_DETAIL).assertIsDisplayed()

    // One Back is enough: the detail was opened once, not stacked on itself.
    composeTestRule.onNodeWithTag(FAKE_DETAIL).performClick()
    assertOnTab(Tab.MAP)
    composeTestRule.runOnIdle {
      assertEquals(Tab.MAP.rootRoute, navController.currentBackStackEntry?.destination?.route)
    }
  }

  @Test
  fun startsOnEventsTab() {
    assertOnTab(Tab.EVENTS)
  }

  @Test
  fun injectedEventsScreenIsRestoredAfterOpeningMapDetails() {
    composeTestRule.onNodeWithTag("injected_events_content").assertIsDisplayed()
    composeTestRule.onNodeWithTag(Tab.MAP.navItemTag).performClick()
    composeTestRule.onNodeWithTag("fake_map_details").performClick()
    composeTestRule.onNodeWithTag(FAKE_DETAIL).assertIsDisplayed().performClick()

    composeTestRule.onNodeWithTag(Tab.EVENTS.navItemTag).performClick()

    composeTestRule.onNodeWithTag("injected_events_content").assertIsDisplayed()
    composeTestRule.onNodeWithTag(Tab.EVENTS.navItemTag).assertIsSelected()
  }

  @Test
  fun bottomBarShowsEveryTabWithItsLabel() {
    composeTestRule.onNodeWithTag(C.Tag.bottom_nav).assertIsDisplayed()
    Tab.entries.forEach { tab ->
      composeTestRule.onNodeWithTag(tab.navItemTag).assertIsDisplayed().assert(hasText(label(tab)))
    }
  }

  @Test
  fun tappingEachTabShowsItsScreenAndTitle() {
    listOf(Tab.MAP, Tab.CHATS, Tab.PROFILE, Tab.EVENTS).forEach { tab ->
      composeTestRule.onNodeWithTag(tab.navItemTag).performClick()
      assertOnTab(tab)
    }
  }

  @Test
  fun tappingTheCurrentTabAgainKeepsTheSameScreen() {
    Tab.entries.forEach { tab ->
      composeTestRule.onNodeWithTag(tab.navItemTag).performClick()
      val entryId = composeTestRule.runOnIdle { navController.currentBackStackEntry?.id }

      composeTestRule.onNodeWithTag(tab.navItemTag).performClick()

      composeTestRule.runOnIdle {
        // Same back stack entry id: the screen is not recreated and keeps its state
        assertEquals(entryId, navController.currentBackStackEntry?.id)
        // Only the Events tab can sit below a tab, so back never shows a duplicate
        val below = navController.previousBackStackEntry?.destination?.route
        assertEquals(if (tab == Tab.EVENTS) null else Tab.EVENTS.rootRoute, below)
      }
      assertOnTab(tab)
    }
  }

  private companion object {
    const val FAKE_DETAIL = "fake_event_detail"
  }
}
