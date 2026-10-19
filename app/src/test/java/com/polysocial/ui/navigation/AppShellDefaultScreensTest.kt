// Contributors: Claude (UI test for the real Map and Create Event screens inside AppShell, #110;
// the real Edit event screen, #131).
package com.polysocial.ui.navigation

import android.content.res.Resources
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.R
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.withCreator
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.location.LocationModule
import com.polysocial.model.location.LocationService
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import com.polysocial.model.map.MapModule
import com.polysocial.model.time.ClockModule
import com.polysocial.resources.C
import com.polysocial.ui.event.create.FORM_NOW
import com.polysocial.ui.event.create.ZURICH
import com.polysocial.ui.event.create.completeForm
import com.polysocial.ui.event.create.toEvent
import com.polysocial.ui.map.MapTags
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.FirebaseStartupRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import io.mockk.every
import io.mockk.spyk
import java.time.Clock
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Opens Create Event from the Map tab with AppShell's own Map and Create Event screens, so their
 * ViewModels come from Hilt as in the app. The other navigation tests replace both screens because
 * they render AppShell without Hilt. Hilt gets in-memory events, a denied location and a fixed
 * clock, and the Mapbox token is blanked so no native map or network request runs.
 */
@HiltAndroidTest
@UninstallModules(EventModule::class, MapModule::class, LocationModule::class, ClockModule::class)
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class AppShellDefaultScreensTest {
  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @get:Rule(order = 1) val firebaseStartupRule = FirebaseStartupRule()

  @get:Rule(order = 2) val composeTestRule = createAndroidComposeRule<MainActivity>()

  private val events = FakeEventRepository(now = FORM_NOW)

  @BindValue @JvmField val eventRepository: EventRepository = events

  private lateinit var navController: NavHostController

  @BindValue
  @JvmField
  val mapEventSource: MapEventSource = MapEventSource { flowOf(MapEventResult.Events(emptyList())) }

  // Already asked once and refused, so the map shows its notice instead of a permission dialog.
  @BindValue
  @JvmField
  val locationService: LocationService = FakeLocationService().apply { markPermissionRequested() }

  @BindValue @JvmField val clock: Clock = Clock.fixed(FORM_NOW, ZURICH)

  @Before
  fun setUp() {
    val activity = composeTestRule.activity
    val resources: Resources = spyk(activity.resources)
    every { resources.getString(R.string.mapbox_access_token) } returns ""
    activity.setContent {
      CompositionLocalProvider(LocalResources provides resources) {
        navController = rememberNavController()
        PolySocialTheme {
          AppShell(navController = navController, eventsContent = { TabRootScreen(Tab.EVENTS) })
        }
      }
    }
    node(Tab.MAP.navItemTag).performClick()
    composeTestRule.waitForIdle()
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun assertGone(tag: String) = composeTestRule.onAllNodesWithTag(tag).assertCountEquals(0)

  private fun assertOnTheMap() {
    node(C.Tag.screen_map).assertIsDisplayed()
    node(MapTags.SETUP).assertIsDisplayed()
    node(Tab.MAP.navItemTag).assertIsSelected()
    node(C.Tag.create_event_button).assertIsDisplayed()
    assertGone(C.Tag.create_event_screen)
  }

  @Test
  fun plusOnTheRealMapOpensTheRealCreateEventScreen() {
    assertOnTheMap()

    node(C.Tag.create_event_button).performClick()
    composeTestRule.waitForIdle()

    node(C.Tag.create_event_screen).assertIsDisplayed()
    node(C.Tag.create_event_title).assertIsDisplayed()
    assertGone(C.Tag.screen_map)
    assertGone(C.Tag.bottom_nav)
  }

  @Test
  fun closingTheRealCreateEventScreenReturnsToTheMap() {
    node(C.Tag.create_event_button).performClick()
    composeTestRule.waitForIdle()

    node(C.Tag.create_event_close).performClick()
    composeTestRule.waitForIdle()

    assertOnTheMap()
    node(C.Tag.bottom_nav).assertIsDisplayed()
  }

  @Test
  fun theRealEditEventScreen_showsTheEventAndClosesBackToTheMap() {
    events.seed(
        completeForm().toEvent(ZURICH)!!.withCreator("test-creator", false).copy(id = "event-1")
    )

    composeTestRule.runOnIdle { navController.navigate(Tab.MAP.editEventRoute("event-1")) }
    composeTestRule.waitForIdle()

    composeTestRule
        .onNodeWithText(composeTestRule.activity.getString(R.string.edit_event_title))
        .assertIsDisplayed()
    node(C.Tag.create_event_title).assertTextContains(completeForm().title)
    assertGone(C.Tag.bottom_nav)

    node(C.Tag.create_event_close).performClick()
    composeTestRule.waitForIdle()

    assertOnTheMap()
  }
}
