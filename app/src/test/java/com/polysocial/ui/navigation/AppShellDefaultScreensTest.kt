// Contributors: Claude (UI test for the real Map and Create Event screens inside AppShell, #110;
// the real Edit event screen, #131; a fake association repository for the association names, #128;
// which map each account's Map tab reads, #133).
// Contributors: OpenAI Codex (verified the real Hilt-backed event detail and roster, #234).
// Contributors: Claude Opus 5.5 (test WorkManager initialization, needed once
// EventDetailViewModel depends on ReminderScheduler for real DI, #123's second PR review).
package com.polysocial.ui.navigation

import android.content.res.Resources
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.polysocial.MainActivity
import com.polysocial.R
import com.polysocial.auth.MemoryVerificationStore
import com.polysocial.model.association.AssociationModule
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AssociationSignupStore
import com.polysocial.model.auth.AuthModule
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthSessionSource
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.FakeAuthSessionSource
import com.polysocial.model.auth.VerificationClock
import com.polysocial.model.auth.VerificationStore
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.withCreator
import com.polysocial.model.firebase.FakeSessionGuard
import com.polysocial.model.firebase.FakeSessionOwnerStore
import com.polysocial.model.firebase.SessionGuard
import com.polysocial.model.firebase.SessionOwnerStore
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GroupModule
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.location.LocationModule
import com.polysocial.model.location.LocationService
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import com.polysocial.model.map.MapModule
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.network.NetworkModule
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.registration.RegistrationModule
import com.polysocial.model.registration.RegistrationRepository
import com.polysocial.model.time.ClockModule
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileModule
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.resources.C
import com.polysocial.ui.event.create.FORM_NOW
import com.polysocial.ui.event.create.ZURICH
import com.polysocial.ui.event.create.completeForm
import com.polysocial.ui.event.create.toEvent
import com.polysocial.ui.event.detail.EventDetailTags
import com.polysocial.ui.group.YourGroupTags
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Opens Create Event from the Map tab with AppShell's own Map and Create Event screens, so their
 * ViewModels come from Hilt as in the app. The other navigation tests replace both screens because
 * they render AppShell without Hilt. Hilt gets in-memory events, a denied location and a fixed
 * clock, and the Mapbox token is blanked so no native map or network request runs. It also checks
 * which events each account's Map tab reads (#133).
 */
@HiltAndroidTest
@UninstallModules(
    AssociationModule::class,
    EventModule::class,
    MapModule::class,
    LocationModule::class,
    ClockModule::class,
    AuthModule::class,
    GroupModule::class,
    NetworkModule::class,
    PublicProfileModule::class,
    RegistrationModule::class,
)
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class AppShellDefaultScreensTest {
  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @get:Rule(order = 1) val firebaseStartupRule = FirebaseStartupRule()

  @get:Rule(order = 2) val composeTestRule = createAndroidComposeRule<MainActivity>()

  /**
   * [com.polysocial.ui.event.detail.EventDetailViewModel] depends on a
   * [com.polysocial.model.reminder.ReminderScheduler], which Hilt's real `ReminderModule` backs
   * with WorkManager; it needs test initialization, like
   * [com.polysocial.model.reminder.WorkManagerReminderScheduler]'s own tests already do.
   */
  @Before
  fun initializeTestWorkManager() {
    WorkManagerTestInitHelper.initializeTestWorkManager(
        ApplicationProvider.getApplicationContext(),
        Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
  }

  private val events = FakeEventRepository(now = FORM_NOW)

  @BindValue @JvmField val eventRepository: EventRepository = events

  private val auth = FakeAuthRepository()
  private val groups = FakeGroupRepository()

  @BindValue @JvmField val authRepository: AuthRepository = auth
  @BindValue @JvmField val authSessionSource: AuthSessionSource = FakeAuthSessionSource()
  @BindValue @JvmField val sessionOwner: SessionOwnerStore = FakeSessionOwnerStore()
  @BindValue @JvmField val sessions: SessionGuard = FakeSessionGuard()
  @BindValue @JvmField val verificationStore: VerificationStore = MemoryVerificationStore()
  @BindValue @JvmField val verificationClock: VerificationClock = VerificationClock { 1000 }
  @BindValue @JvmField val signupStore: AssociationSignupStore = EmptyAssociationSignupStore
  @BindValue @JvmField val groupRepository: GroupRepository = groups
  @BindValue @JvmField val network: NetworkMonitor = FakeNetworkMonitor()
  @BindValue @JvmField val registrations: RegistrationRepository = FakeRegistrationRepository()

  @BindValue
  @JvmField
  val publicProfiles: PublicProfileRepository =
      FakePublicProfileRepository(
          mutableMapOf("peer" to PublicProfile("peer", "Test Peer", "SC", "BA4"))
      )

  // In memory, so the names of associations never reach Firestore.
  @BindValue @JvmField val associations: AssociationRepository = FakeAssociationRepository()

  private lateinit var navController: NavHostController

  /** How many times the students' map read its events. */
  private var studentReads = 0

  @BindValue
  @JvmField
  val mapEventSource: MapEventSource = MapEventSource {
    studentReads++
    flowOf(MapEventResult.Events(emptyList()))
  }

  // Already asked once and refused, so the map shows its notice instead of a permission dialog.
  @BindValue
  @JvmField
  val locationService: LocationService = FakeLocationService().apply { markPermissionRequested() }

  @BindValue @JvmField val clock: Clock = Clock.fixed(FORM_NOW, ZURICH)

  /** Shows AppShell's own screens for [accountType], on the Map tab. */
  private fun showMap(accountType: AccountType = AccountType.STUDENT) {
    val activity = composeTestRule.activity
    val resources: Resources = spyk(activity.resources)
    every { resources.getString(R.string.mapbox_access_token) } returns ""
    activity.setContent {
      CompositionLocalProvider(LocalResources provides resources) {
        navController = rememberNavController()
        PolySocialTheme {
          AppShell(
              accountType = accountType,
              navController = navController,
              eventsContent = { TabRootScreen(Tab.EVENTS) },
          )
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
    showMap()
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
    showMap()
    node(C.Tag.create_event_button).performClick()
    composeTestRule.waitForIdle()

    node(C.Tag.create_event_close).performClick()
    composeTestRule.waitForIdle()

    assertOnTheMap()
    node(C.Tag.bottom_nav).assertIsDisplayed()
  }

  @Test
  fun theRealEditEventScreen_showsTheEventAndClosesBackToTheMap() {
    showMap()
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

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun theRealRosterOpensFromEventDetailAndBackReturnsToThatEvent() {
    showMap()
    composeTestRule.runOnIdle {
      val eventId = "group-event"
      val student = "test-student"
      auth.user = AuthUser(student, "fictional@epfl.ch", true)
      events.seed(
          completeForm().toEvent(ZURICH)!!.withCreator("organizer", false).copy(id = eventId)
      )
      groups.seedGroup(eventId, listOf(student, "peer"))
      navController.navigate(Tab.MAP.eventDetailRoute(eventId))
    }
    composeTestRule.waitUntilAtLeastOneExists(hasTestTag(EventDetailTags.YOUR_GROUP), 10_000)
    node(EventDetailTags.YOUR_GROUP).performScrollTo().performClick()
    node(YourGroupTags.SCREEN).assertIsDisplayed()
    composeTestRule.onNodeWithText("Test Peer").assertIsDisplayed()
    assertGone(C.Tag.bottom_nav)

    node(YourGroupTags.BACK).performClick()
    node(EventDetailTags.YOUR_GROUP).performScrollTo().assertIsDisplayed()
    assertGone(YourGroupTags.SCREEN)
  }

  @Test
  fun aStudentsMapTab_readsThePublicEvents() {
    showMap(AccountType.STUDENT)

    assertOnTheMap()
    assertEquals(1, studentReads)
  }

  @Test
  fun anAssociationsMapTab_neverReadsTheStudentsEvents() {
    showMap(AccountType.ASSOCIATION)

    assertOnTheMap()
    assertEquals(0, studentReads)
  }
}
