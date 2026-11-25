// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #469 permission and group navigation tests).
// Contributors: OpenAI Codex (6.2 Sol, high; real request action integration, #361).
// Contributors: OpenAI Codex (6.2 Sol, high; Friends route and association visibility tests, #360).
// Contributors: Claude Opus 5.5 (a fake FriendRepository for the scanner's ViewModel, #359; the
// real member profile from the roster, #368); Konstantinos Perdikeas (directed, reviewed and owns
// it).
// Contributors: Claude (UI test for the real Map and Create Event screens inside AppShell, #110;
// the real Edit event screen, #131; a fake association repository for the association names, #128;
// which map each account's Map tab reads, #133).
// Contributors: OpenAI Codex (verified the real Hilt-backed event detail and roster, #234).
// Contributors: Claude Opus 5.5 (test WorkManager initialization, needed once
// EventDetailViewModel depends on ReminderScheduler for real DI, #123's second PR review).
package com.polysocial.ui.navigation

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.Resources
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
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
import com.polysocial.model.checkin.CheckInModule
import com.polysocial.model.checkin.CheckInRepository
import com.polysocial.model.checkin.FakeCheckInRepository
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.withCreator
import com.polysocial.model.firebase.FakeSessionGuard
import com.polysocial.model.firebase.FakeSessionOwnerStore
import com.polysocial.model.firebase.SessionGuard
import com.polysocial.model.firebase.SessionOwnerStore
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.FriendModule
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.friendshipId
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
import com.polysocial.ui.event.checkin.CheckInTags
import com.polysocial.ui.event.create.FORM_NOW
import com.polysocial.ui.event.create.ZURICH
import com.polysocial.ui.event.create.completeForm
import com.polysocial.ui.event.create.toEvent
import com.polysocial.ui.event.detail.EventDetailTags
import com.polysocial.ui.friend.FriendsTags
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
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
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
    CheckInModule::class,
    FriendModule::class,
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
  private val fakeFriends = FakeFriendRepository()

  @BindValue @JvmField val friends: FriendRepository = fakeFriends

  private val fakeRegistrations = FakeRegistrationRepository()
  @BindValue @JvmField val registrations: RegistrationRepository = fakeRegistrations
  private val fakeCheckIns = FakeCheckInRepository()
  @BindValue @JvmField val checkIns: CheckInRepository = fakeCheckIns

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
    val eventId = "group-event"
    val student = "test-student"
    showMap()
    composeTestRule.runOnIdle {
      auth.user = AuthUser(student, "fictional@epfl.ch", true)
      val event = completeForm().toEvent(ZURICH)!!.withCreator("organizer", false)
      events.seed(event.copy(id = eventId, startTime = FORM_NOW.plusSeconds(600), endTime = null))
      fakeRegistrations.seedRegistration(eventId, student)
      navController.navigate(Tab.MAP.eventDetailRoute(eventId))
    }
    composeTestRule.waitUntilAtLeastOneExists(hasTestTag(CheckInTags.ENTRY), 10_000)
    node(CheckInTags.ENTRY).performScrollTo().performClick()
    val activity = composeTestRule.activity
    val app = shadowOf(activity.application)
    app.grantPermissions(ACCESS_COARSE_LOCATION)
    node(CheckInTags.LOCATION).performClick()
    val request = shadowOf(activity).lastRequestedPermission
    val requested = request.requestedPermissions
    assertEquals(listOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION), requested.toList())
    composeTestRule.runOnIdle {
      val grants = intArrayOf(PERMISSION_DENIED, PERMISSION_GRANTED)
      activity.onRequestPermissionsResult(request.requestCode, requested, grants)
    }
    composeTestRule.onNodeWithText("Precise location is off").assertIsDisplayed()
    node(CheckInTags.SETTINGS).performClick()
    assertEquals("package:${activity.packageName}", app.nextStartedActivity.dataString)
    node(CheckInTags.BACK).performClick()
    composeTestRule.runOnIdle { fakeCheckIns.seedCheckIn(eventId, student, FORM_NOW) }
    node(CheckInTags.ENTRY).performScrollTo().performClick()
    composeTestRule.onNodeWithText("Checked in at 12:00.").assertIsDisplayed()
    assertGone(CheckInTags.GROUP)
    composeTestRule.runOnIdle { groups.seedGroup(eventId, listOf(student, "peer")) }
    composeTestRule.waitUntilAtLeastOneExists(hasTestTag(CheckInTags.GROUP), 10_000)
    node(CheckInTags.GROUP).performClick()
    node(YourGroupTags.SCREEN).assertIsDisplayed()
    composeTestRule.onNodeWithText("Test Peer").assertIsDisplayed()
    assertGone(C.Tag.bottom_nav)

    node(YourGroupTags.BACK).performClick()
    node(EventDetailTags.YOUR_GROUP).performScrollTo().assertIsDisplayed()
    assertGone(YourGroupTags.SCREEN)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun theRealMemberProfileOpensFromTheRoster_andBackReturnsToIt() {
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

    node(YourGroupTags.member("peer")).performClick()

    // The real route, with its Hilt ViewModel reading the peer's public profile by UID.
    node(C.Tag.student_profile_name).assertTextEquals("Test Peer")
    node(C.Tag.student_profile_details).assertTextEquals("SC · BA4")
    assertGone(C.Tag.bottom_nav)
    node(C.Tag.student_profile_back).performClick()
    node(YourGroupTags.SCREEN).assertIsDisplayed()
    assertGone(C.Tag.student_profile_screen)
  }

  @Test
  fun profileOpensFriendsAndBackFromThePublicProfileReturnsToTheList() {
    showMap()
    composeTestRule.runOnIdle {
      val uid = "test-student"
      val peer = "peer"
      auth.user = AuthUser(uid, "fictional@epfl.ch", true)
      fakeFriends.seedFriendship(
          Friendship(
              checkNotNull(friendshipId(uid, peer)),
              listOf(peer, uid),
              uid,
              FriendshipStatus.ACCEPTED,
              Instant.EPOCH,
              Instant.EPOCH,
          )
      )
    }
    node(Tab.PROFILE.navItemTag).performClick()
    node(FriendsTags.ENTRY).performClick()
    node(FriendsTags.SCREEN).assertIsDisplayed()
    assertGone(C.Tag.bottom_nav)
    clickTwiceDuringNavigation(FriendsTags.row("peer"))
    node(C.Tag.student_profile_name).assertTextEquals("Test Peer")
    assertEquals(Tab.PROFILE.studentProfileRoute, navController.currentDestination?.route)
    node(C.Tag.student_profile_back).performClick()
    node(FriendsTags.row("peer")).assertIsDisplayed()
    clickTwiceDuringNavigation(FriendsTags.SCAN)
    node(C.Tag.scan_friend_screen).assertIsDisplayed()
    assertEquals(Tab.PROFILE.scanFriendRoute, navController.currentDestination?.route)
    node(C.Tag.scan_friend_close).performClick()
    node(FriendsTags.SCREEN).assertIsDisplayed()
    clickTwiceDuringNavigation(FriendsTags.BACK)
    node(FriendsTags.ENTRY).assertIsDisplayed()
  }

  private fun clickTwiceDuringNavigation(tag: String) {
    val click = checkNotNull(node(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    composeTestRule.runOnIdle {
      click()
      click()
    }
  }

  @Test
  fun acceptingFromTheRealFriendsRouteMovesTheStudentIntoFriends() {
    showMap()
    composeTestRule.runOnIdle {
      val uid = "test-student"
      val peer = "peer"
      auth.user = AuthUser(uid, "fictional@epfl.ch", true)
      fakeFriends.seedFriendship(
          Friendship(
              checkNotNull(friendshipId(uid, peer)),
              listOf(peer, uid),
              peer,
              FriendshipStatus.PENDING,
              Instant.EPOCH,
              null,
          )
      )
    }
    node(Tab.PROFILE.navItemTag).performClick()
    node(FriendsTags.ENTRY).performClick()
    node(FriendsTags.accept("peer")).performClick()
    assertGone(FriendsTags.accept("peer"))
    node(FriendsTags.options("peer")).assertIsDisplayed()
  }

  @Test
  fun associationsHaveNeitherTheFriendsEntryNorTheDestination() {
    showMap(AccountType.ASSOCIATION)
    node(C.Tag.nav_item_organization).performClick()
    assertGone(FriendsTags.ENTRY)
    composeTestRule.runOnIdle {
      val profileGraph =
          navController.graph.findNode(Tab.PROFILE.route) as androidx.navigation.NavGraph
      assertEquals(null, profileGraph.findNode(Tab.PROFILE.friendsRoute))
    }
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
