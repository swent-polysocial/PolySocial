// Contributors: Claude Opus 5.5 (medium) (wrote these tests, #204).
package com.polysocial.ui.event.list

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.ChosenEventsRepository
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The students' Events tab: its Upcoming and My events segments, with fake repositories. */
@RunWith(AndroidJUnit4::class)
class StudentEventsTabTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val registrations = FakeRegistrationRepository(currentUid = STUDENT)
  private val events = FakeEventRepository(currentUid = STUDENT)
  private val clock = Clock.fixed(TEST_NOW, ZoneId.of("Europe/Zurich"))
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = true
      }

  private val tomorrow =
      validEvent(title = "Ciné-club: Dune", startTime = TEST_NOW.plus(Duration.ofDays(1)))
          .copy(id = "tomorrow")
  private val opened = mutableListOf<String>()

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  private fun settle() {
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeTestRule.waitForIdle()
  }

  private fun showRoute() {
    // Created once, outside composition, like hiltViewModel() would.
    val viewModel =
        MyEventsViewModel(
            ChosenEventsRepository(registrations, events),
            registrations,
            network,
            clock,
        )
    composeTestRule.setContent {
      PolySocialTheme {
        StudentEventsRoute(
            onEventClick = { opened += it },
            myEventsViewModel = viewModel,
            upcomingContent = { Text("Upcoming list", Modifier.testTag(UPCOMING_LIST)) },
        )
      }
    }
    settle()
  }

  @Test
  fun upcoming_isSelectedFirst() {
    showRoute()

    composeTestRule.onNodeWithTag(StudentEventsTags.UPCOMING).assertIsSelected()
    composeTestRule.onNodeWithTag(UPCOMING_LIST).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(MyEventsTags.SCREEN).assertCountEquals(0)
  }

  @Test
  fun myEvents_countsTheUpcomingChosenEvents_andListsThem() {
    events.seed(tomorrow)
    registrations.seedRegistration(tomorrow.id, STUDENT)
    showRoute()

    composeTestRule
        .onNodeWithTag(StudentEventsTags.MY_EVENTS)
        .assertTextEquals(string(R.string.events_segment_my_events_count, 1))
    composeTestRule.onNodeWithTag(StudentEventsTags.MY_EVENTS).performClick()

    composeTestRule.onNodeWithTag(StudentEventsTags.MY_EVENTS).assertIsSelected()
    composeTestRule.onAllNodesWithTag(UPCOMING_LIST).assertCountEquals(0)
    composeTestRule.onNodeWithTag(MyEventsTags.card(tomorrow.id)).performClick()
    assertEquals(listOf(tomorrow.id), opened)
  }

  @Test
  fun aRegistrationAndItsCancellation_showWithoutRestarting() {
    events.seed(tomorrow)
    registrations.readableEventIds += tomorrow.id
    showRoute()
    composeTestRule.onNodeWithTag(StudentEventsTags.MY_EVENTS).performClick()
    composeTestRule.onNodeWithTag(MyEventsTags.EMPTY).assertIsDisplayed()

    runBlocking { registrations.register(tomorrow.id) }
    settle()
    composeTestRule.onNodeWithTag(MyEventsTags.card(tomorrow.id)).assertIsDisplayed()

    runBlocking { registrations.cancel(tomorrow.id) }
    settle()
    composeTestRule.onAllNodesWithTag(MyEventsTags.card(tomorrow.id)).assertCountEquals(0)
    composeTestRule.onNodeWithTag(MyEventsTags.EMPTY).assertIsDisplayed()
  }

  @Test
  fun browseUpcomingEvents_selectsUpcoming() {
    showRoute()
    composeTestRule.onNodeWithTag(StudentEventsTags.MY_EVENTS).performClick()

    composeTestRule.onNodeWithTag(MyEventsTags.BROWSE).performClick()

    composeTestRule.onNodeWithTag(StudentEventsTags.UPCOMING).assertIsSelected()
    composeTestRule.onNodeWithTag(UPCOMING_LIST).assertIsDisplayed()
  }

  @Test
  fun anUnknownCount_showsMyEventsAlone() {
    composeTestRule.setContent {
      PolySocialTheme { StudentEventsTab(myEventsCount = null, upcoming = {}, myEvents = {}) }
    }

    composeTestRule
        .onNodeWithTag(StudentEventsTags.MY_EVENTS)
        .assertTextEquals(string(R.string.events_segment_my_events))
  }

  @Test
  fun theSelectedSegment_survivesRecreation() {
    val restoration = StateRestorationTester(composeTestRule)
    restoration.setContent {
      PolySocialTheme {
        StudentEventsTab(
            myEventsCount = 0,
            upcoming = { Text("Upcoming list", Modifier.testTag(UPCOMING_LIST)) },
            myEvents = { Text("My events", Modifier.testTag(MyEventsTags.SCREEN)) },
        )
      }
    }
    composeTestRule.onNodeWithTag(StudentEventsTags.MY_EVENTS).performClick()

    restoration.emulateSavedInstanceStateRestore()

    composeTestRule.onNodeWithTag(StudentEventsTags.MY_EVENTS).assertIsSelected()
    composeTestRule.onNodeWithTag(MyEventsTags.SCREEN).assertIsDisplayed()
  }

  private companion object {
    const val STUDENT = "test-student"
    const val UPCOMING_LIST = "upcoming_list"
  }
}
