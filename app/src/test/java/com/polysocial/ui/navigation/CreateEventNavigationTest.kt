// Contributors: OpenAI Codex (inject Profile placeholder after logout rebase, #109).
// Contributors: Claude (UI tests for opening and leaving Create Event from the Events and Map
// tabs, and the created event's id reaching the Map's detail route, #110; "View event" opening the
// event detail, #211; the ViewModel's navigation arguments, #131; the organizer's repositories,
// #134).
package com.polysocial.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.resources.C
import com.polysocial.ui.event.create.CreateEventScreen
import com.polysocial.ui.event.create.CreateEventStatus
import com.polysocial.ui.event.create.CreateEventViewModel
import com.polysocial.ui.event.create.FORM_NOW
import com.polysocial.ui.event.create.ROLEX
import com.polysocial.ui.event.create.ZURICH
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.map.MapContentStatus
import com.polysocial.ui.map.MapRenderStatus
import com.polysocial.ui.map.MapScreen
import com.polysocial.ui.map.MapUiState
import com.polysocial.ui.theme.PolySocialTheme
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The "+" button opens Create Event inside the Events or Map tab, and every way out of it (Close,
 * system Back, "Back to map", "View event") lands on the right screen. Create Event gets its
 * ViewModel per navigation entry, as with Hilt, but built from a [FakeEventRepository]. The Map tab
 * shows the real [MapScreen] (where its "+" lives) with an empty state and no map renderer, since
 * the real map needs Hilt and Mapbox. The event detail is a stub showing the event id it was opened
 * with, since the real one needs Hilt.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CreateEventNavigationTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val repository = FakeEventRepository(now = FORM_NOW)
  private lateinit var navController: NavHostController
  private lateinit var createEventViewModel: CreateEventViewModel

  @Before
  fun setUp() {
    composeTestRule.setContent {
      navController = rememberNavController()
      PolySocialTheme {
        AppShell(
            navController = navController,
            eventsContent = { TabRootScreen(Tab.EVENTS) },
            profileContent = { TabRootScreen(Tab.PROFILE) },
            mapContent = { onViewDetails, onCreateEvent ->
              MapScreen(
                  state =
                      MapUiState(
                          status = MapContentStatus.EMPTY,
                          renderStatus = MapRenderStatus.READY,
                      ),
                  onSelectEvent = {},
                  onClosePreview = {},
                  onViewDetails = onViewDetails,
                  onRetry = {},
                  onRenderStatus = {},
                  onCreateEvent = onCreateEvent,
                  tokenConfigured = true,
                  renderer = { _, _, _, _ -> },
              )
            },
            createEventContent = { navigation ->
              val viewModel = viewModel {
                CreateEventViewModel(
                    repository,
                    Clock.fixed(FORM_NOW, ZURICH),
                    SavedStateHandle(),
                    FakeAuthRepository(),
                    FakeUserProfileRepository(),
                    FakeAssociationRepository(),
                )
              }
              createEventViewModel = viewModel
              CreateEventScreen(
                  onClose = navigation.onClose,
                  onViewEvent = navigation.onViewEvent,
                  onBackToMap = navigation.onBackToMap,
                  viewModel = viewModel,
              )
            },
            eventDetailContent = {
              val id =
                  navController.currentBackStackEntry
                      ?.arguments
                      ?.getString(EventDetailViewModel.EVENT_ID_ARG)
              Text(id.orEmpty(), Modifier.testTag(EVENT_DETAIL))
            },
        )
      }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun assertGone(tag: String) = composeTestRule.onAllNodesWithTag(tag).assertCountEquals(0)

  private fun openTab(tab: Tab) {
    node(tab.navItemTag).performClick()
    composeTestRule.waitForIdle()
    assertOnRootOf(tab)
  }

  private fun openCreateEvent() {
    node(C.Tag.create_event_button).performClick()
    composeTestRule.waitForIdle()
    node(C.Tag.create_event_screen).assertIsDisplayed()
  }

  private fun pressBack() {
    composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeTestRule.waitForIdle()
  }

  private fun assertOnRootOf(tab: Tab) {
    node(tab.screenTag).assertIsDisplayed()
    node(tab.navItemTag).assertIsSelected()
    assertGone(C.Tag.create_event_screen)
    assertGone(C.Tag.event_created_screen)
  }

  /**
   * Fills the form (the pickers open dialogs, so they're set through the ViewModel), submits, and
   * returns the created event's id.
   */
  private fun createEvent(): String {
    node(C.Tag.create_event_title).performTextInput("Study together")
    composeTestRule.runOnIdle {
      createEventViewModel.onCategorySelect(EventCategory.STUDY)
      createEventViewModel.onDatePick(LocalDate.of(2026, 10, 10))
      createEventViewModel.onStartTimePick(LocalTime.of(14, 0))
      createEventViewModel.onLocationPick(ROLEX)
    }
    node(C.Tag.create_event_submit).performClick()
    composeTestRule.waitForIdle()
    node(C.Tag.event_created_screen).assertIsDisplayed()
    return composeTestRule.runOnIdle {
      (createEventViewModel.uiState.value.status as CreateEventStatus.Created).eventId
    }
  }

  @Test
  fun theEventsTabShowsThePlusButtonWithAContentDescription() {
    composeTestRule
        .onNodeWithContentDescription(
            composeTestRule.activity.getString(R.string.create_event_open)
        )
        .assertIsDisplayed()
  }

  @Test
  fun theMapTabShowsThePlusButtonWithAContentDescription() {
    openTab(Tab.MAP)

    composeTestRule
        .onNodeWithContentDescription(
            composeTestRule.activity.getString(R.string.create_event_open)
        )
        .assertIsDisplayed()
  }

  @Test
  fun chatsAndProfileDoNotShowThePlusButton() {
    listOf(Tab.CHATS, Tab.PROFILE).forEach { tab ->
      node(tab.navItemTag).performClick()
      composeTestRule.waitForIdle()

      assertGone(C.Tag.create_event_button)
    }
  }

  @Test
  fun plusOpensCreateEventFullScreen() {
    openCreateEvent()

    assertGone(C.Tag.bottom_nav)
    assertGone(C.Tag.app_bar_title)
    assertGone(C.Tag.create_event_button)
  }

  @Test
  fun closeReturnsToTheEventsTab() {
    openCreateEvent()

    node(C.Tag.create_event_close).performClick()
    composeTestRule.waitForIdle()

    assertOnRootOf(Tab.EVENTS)
    node(C.Tag.bottom_nav).assertIsDisplayed()
  }

  @Test
  fun systemBackReturnsToTheEventsTab() {
    openCreateEvent()

    pressBack()

    assertOnRootOf(Tab.EVENTS)
    assertFalse(composeTestRule.activity.isFinishing)
  }

  @Test
  fun reopeningAfterCloseShowsAnEmptyForm() {
    openCreateEvent()
    node(C.Tag.create_event_title).performTextInput("Study together")
    node(C.Tag.create_event_close).performClick()
    composeTestRule.waitForIdle()

    openCreateEvent()

    composeTestRule.runOnIdle { assertEquals("", createEventViewModel.uiState.value.form.title) }
  }

  @Test
  fun backFromTheConfirmationReturnsToTheTabNotTheForm() {
    openCreateEvent()
    createEvent()

    pressBack()

    assertOnRootOf(Tab.EVENTS)
  }

  @Test
  fun closeFromTheConfirmationReturnsToTheTab() {
    openCreateEvent()
    createEvent()

    node(C.Tag.create_event_close).performClick()
    composeTestRule.waitForIdle()

    assertOnRootOf(Tab.EVENTS)
  }

  @Test
  fun backToMapShowsTheMapTabAndLeavesCreateEventBehind() {
    openCreateEvent()
    createEvent()

    node(C.Tag.event_created_back_to_map).performClick()
    composeTestRule.waitForIdle()

    assertOnRootOf(Tab.MAP)
    // The Events tab comes back on its list, not on the confirmation.
    node(Tab.EVENTS.navItemTag).performClick()
    composeTestRule.waitForIdle()
    assertOnRootOf(Tab.EVENTS)
  }

  @Test
  fun backFromTheMapAfterBackToMapGoesToTheEventsList() {
    openCreateEvent()
    createEvent()
    node(C.Tag.event_created_back_to_map).performClick()
    composeTestRule.waitForIdle()

    pressBack()

    assertOnRootOf(Tab.EVENTS)
  }

  @Test
  fun viewEventOpensTheEventDetailAndBackSkipsTheConfirmation() {
    openCreateEvent()
    val eventId = createEvent()

    node(C.Tag.event_created_view_event).performClick()
    composeTestRule.waitForIdle()

    node(EVENT_DETAIL).assertIsDisplayed().assertTextEquals(eventId)
    composeTestRule.runOnIdle {
      assertEquals(Tab.EVENTS.eventDetailRoute, navController.currentDestination?.route)
    }
    assertGone(C.Tag.event_created_screen)

    pressBack()

    assertOnRootOf(Tab.EVENTS)
  }

  @Test
  fun plusOnTheMapOpensCreateEventFullScreen() {
    openTab(Tab.MAP)

    openCreateEvent()

    assertGone(C.Tag.bottom_nav)
    assertGone(C.Tag.screen_map)
    assertGone(C.Tag.create_event_button)
  }

  @Test
  fun closeReturnsToTheMapTab() {
    openTab(Tab.MAP)
    openCreateEvent()

    node(C.Tag.create_event_close).performClick()
    composeTestRule.waitForIdle()

    assertOnRootOf(Tab.MAP)
    node(C.Tag.create_event_button).assertIsDisplayed()
  }

  @Test
  fun systemBackReturnsToTheMapTab() {
    openTab(Tab.MAP)
    openCreateEvent()

    pressBack()

    assertOnRootOf(Tab.MAP)
    assertFalse(composeTestRule.activity.isFinishing)
  }

  @Test
  fun backFromTheConfirmationReturnsToTheMapNotTheForm() {
    openTab(Tab.MAP)
    openCreateEvent()
    createEvent()

    pressBack()

    assertOnRootOf(Tab.MAP)
    // Back from the Map root goes to the Events tab, as before Create Event was opened.
    pressBack()
    assertOnRootOf(Tab.EVENTS)
  }

  @Test
  fun backToMapFromTheMapTabLandsOnTheMapRoot() {
    openTab(Tab.MAP)
    openCreateEvent()
    createEvent()

    node(C.Tag.event_created_back_to_map).performClick()
    composeTestRule.waitForIdle()

    assertOnRootOf(Tab.MAP)
    // The Map tab comes back on its root, not on the confirmation, after another tab.
    openTab(Tab.EVENTS)
    openTab(Tab.MAP)
    pressBack()
    assertOnRootOf(Tab.EVENTS)
  }

  @Test
  fun viewEventFromTheMapOpensTheEventDetailAndBackReturnsToTheMap() {
    openTab(Tab.MAP)
    openCreateEvent()
    val eventId = createEvent()

    node(C.Tag.event_created_view_event).performClick()
    composeTestRule.waitForIdle()

    node(EVENT_DETAIL).assertIsDisplayed().assertTextEquals(eventId)
    composeTestRule.runOnIdle {
      assertEquals(Tab.MAP.eventDetailRoute, navController.currentDestination?.route)
    }
    assertGone(C.Tag.event_created_screen)

    pressBack()

    assertOnRootOf(Tab.MAP)
  }

  private companion object {
    const val EVENT_DETAIL = "test_event_detail"
  }
}
