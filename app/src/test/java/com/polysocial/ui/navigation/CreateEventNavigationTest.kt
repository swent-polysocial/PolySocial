// Contributors: Claude (UI tests for opening and leaving Create Event from a tab, #110).
package com.polysocial.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.resources.C
import com.polysocial.ui.event.create.CreateEventScreen
import com.polysocial.ui.event.create.CreateEventViewModel
import com.polysocial.ui.event.create.FORM_NOW
import com.polysocial.ui.event.create.ROLEX
import com.polysocial.ui.event.create.ZURICH
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
 * The "+" button opens Create Event inside the Events tab, and every way out of it (Close, system
 * Back, "Back to map", "View event") lands on the right screen. Create Event gets its ViewModel per
 * navigation entry, as with Hilt, but built from a [FakeEventRepository].
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
            // The real map needs Hilt and the native renderer; the tests only check it's shown.
            mapContent = { Box(Modifier.fillMaxSize().testTag(C.Tag.screen_map)) },
            createEventContent = { navigation ->
              val viewModel = viewModel {
                CreateEventViewModel(repository, Clock.fixed(FORM_NOW, ZURICH))
              }
              createEventViewModel = viewModel
              CreateEventScreen(
                  onClose = navigation.onClose,
                  onPickLocation = {},
                  onViewEvent = navigation.onViewEvent,
                  onBackToMap = navigation.onBackToMap,
                  viewModel = viewModel,
              )
            },
        )
      }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun assertGone(tag: String) = composeTestRule.onAllNodesWithTag(tag).assertCountEquals(0)

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
   * Fills the form (the pickers open dialogs, so they're set through the ViewModel) and submits.
   */
  private fun createEvent() {
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
  fun theOtherTabsDoNotShowThePlusButtonYet() {
    listOf(Tab.MAP, Tab.CHATS, Tab.PROFILE).forEach { tab ->
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
  fun viewEventOpensTheEventScreenAndBackSkipsTheConfirmation() {
    openCreateEvent()
    createEvent()

    node(C.Tag.event_created_view_event).performClick()
    composeTestRule.waitForIdle()

    // The placeholder detail stands in for the event detail screen until #112.
    node(Tab.EVENTS.detailTag).assertIsDisplayed()
    assertGone(C.Tag.event_created_screen)
    node(C.Tag.bottom_nav).assertIsDisplayed()

    pressBack()

    assertOnRootOf(Tab.EVENTS)
  }
}
