// Contributors: Claude (shared-fake Hilt setup reused from #380);
// OpenAI Codex (offline registered-events end-to-end journey and cache transition, #379).
package com.polysocial.endtoend

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.R
import com.polysocial.auth.MemoryVerificationStore
import com.polysocial.model.account.AccountDeletionModule
import com.polysocial.model.account.AccountDeletionRepository
import com.polysocial.model.account.FakeAccountDeletionRepository
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
import com.polysocial.model.chat.FakeMessageRepository
import com.polysocial.model.chat.MessageModule
import com.polysocial.model.chat.MessageRepository
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.FakeSessionGuard
import com.polysocial.model.firebase.FakeSessionOwnerStore
import com.polysocial.model.firebase.FirebaseModule
import com.polysocial.model.firebase.SessionCache
import com.polysocial.model.firebase.SessionGuard
import com.polysocial.model.firebase.SessionOwnerStore
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.FriendModule
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.geocoding.FakeGeocodingService
import com.polysocial.model.geocoding.GeocodingModule
import com.polysocial.model.geocoding.GeocodingService
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GroupModule
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.location.LocationModule
import com.polysocial.model.location.LocationService
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.network.NetworkModule
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.registration.MyRegistrationsResult
import com.polysocial.model.registration.RegisterResult
import com.polysocial.model.registration.RegistrationModule
import com.polysocial.model.registration.RegistrationRepository
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.reminder.ReminderModule
import com.polysocial.model.reminder.ReminderScheduler
import com.polysocial.model.time.ClockModule
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileModule
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileModule
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.ui.event.detail.EventDetailTags
import com.polysocial.ui.event.list.EventsTags
import com.polysocial.ui.event.list.MyEventsTags
import com.polysocial.ui.event.list.StudentEventsTags
import com.polysocial.ui.group.FindGroupTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.combine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A student registers online and revisits their saved event in My events while offline (#379). The
 * real MainActivity, navigation and Hilt ViewModels run against the shared fakes and a fixed clock;
 * no Firebase or live network is used. The cache snapshot retains the registration when
 * connectivity changes, while network-only writes return NetworkError as the real repository does.
 * This tests the app's offline UI contract, not Firestore's on-disk persistence itself.
 */
@HiltAndroidTest
@UninstallModules(
    AccountDeletionModule::class,
    AssociationModule::class,
    AuthModule::class,
    ClockModule::class,
    EventModule::class,
    FirebaseModule::class,
    MessageModule::class,
    GeocodingModule::class,
    GroupModule::class,
    LocationModule::class,
    NetworkModule::class,
    UserProfileModule::class,
    PublicProfileModule::class,
    RegistrationModule::class,
    FriendModule::class,
    ReminderModule::class,
)
@RunWith(AndroidJUnit4::class)
class OfflineRegisteredEventsEndToEndTest {
  private val fakeNetwork = FakeNetworkMonitor()
  private val fakeGroups =
      FakeGroupRepository(currentUid = UID).apply { readableEventIds += EVENT.id }
  private val fakeEvents = FakeEventRepository(currentUid = UID, now = NOW).apply { seed(EVENT) }
  private val fakeRegistrations =
      FakeRegistrationRepository(currentUid = UID).apply { readableEventIds += EVENT.id }

  @BindValue
  @JvmField
  val registrations: RegistrationRepository =
      object : RegistrationRepository by fakeRegistrations {
        // An online/cache transition is a new snapshot even when its event IDs stay unchanged,
        // like a Firestore listener with metadata changes. The registration itself is never seeded.
        override fun observeMyRegistrations() =
            combine(fakeRegistrations.observeMyRegistrations(), fakeNetwork.observeOnline()) {
                result,
                online ->
              if (result is MyRegistrationsResult.Registrations) result.copy(fromCache = !online)
              else result
            }
      }

  @BindValue @JvmField val network: NetworkMonitor = fakeNetwork
  @BindValue @JvmField val events: EventRepository = fakeEvents
  @BindValue
  @JvmField
  val auth: AuthRepository =
      FakeAuthRepository(AuthUser(UID, EMAIL, isEmailVerified = true, displayName = NAME))
  @BindValue
  @JvmField
  val profiles: UserProfileRepository =
      FakeUserProfileRepository(
          getProfileResult = ProfileResult.Found(UserProfile(UID, EMAIL, NAME, "IN", "BA3"))
      )
  @BindValue
  @JvmField
  val publicProfiles: PublicProfileRepository =
      FakePublicProfileRepository(mutableMapOf(UID to PublicProfile(UID, NAME, "IN", "BA3")))
  @BindValue @JvmField val associations: AssociationRepository = FakeAssociationRepository()
  @BindValue @JvmField val clock: Clock = Clock.fixed(NOW, ZURICH)
  @BindValue @JvmField val verificationClock = VerificationClock { NOW.toEpochMilli() }
  @BindValue @JvmField val verificationStore: VerificationStore = MemoryVerificationStore()
  @BindValue
  @JvmField
  val associationSignupStore: AssociationSignupStore = EmptyAssociationSignupStore
  @BindValue @JvmField val sessionGuard: SessionGuard = FakeSessionGuard()
  @BindValue @JvmField val sessionSource: AuthSessionSource = FakeAuthSessionSource()
  @BindValue @JvmField val sessionOwner: SessionOwnerStore = FakeSessionOwnerStore()
  @BindValue @JvmField val groups: GroupRepository = fakeGroups
  @BindValue @JvmField val messages: MessageRepository = FakeMessageRepository(currentUid = UID)
  @BindValue @JvmField val friends: FriendRepository = FakeFriendRepository()
  @BindValue @JvmField val reminders: ReminderScheduler = FakeReminderScheduler(clock)
  @BindValue @JvmField val deletion: AccountDeletionRepository = FakeAccountDeletionRepository()
  @BindValue
  @JvmField
  val cache: SessionCache =
      object : SessionCache {
        override suspend fun clear() = CacheClearResult.Cleared
      }
  @BindValue
  @JvmField
  val location: LocationService = FakeLocationService().apply { markPermissionRequested() }
  @BindValue @JvmField val geocoding: GeocodingService = FakeGeocodingService()

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun registeredEventKeepsItsScheduleOfflineAndNetworkActionsExplainTheirLimit() {
    registerThroughTheEventDetail()
    node(EventDetailTags.BACK).performClick()
    await(StudentEventsTags.MY_EVENTS)
    node(StudentEventsTags.MY_EVENTS).performClick()
    assertSavedSchedule()
    node(MyEventsTags.OFFLINE_BANNER).assertDoesNotExist()

    goOffline()
    await(MyEventsTags.OFFLINE_BANNER)
    node(MyEventsTags.OFFLINE_BANNER).assertIsDisplayed()
    assertSavedSchedule()

    // Switch away and back: the cached registration must drive My events again, rather than
    // merely leaving the previously rendered card on screen.
    node(StudentEventsTags.UPCOMING).performClick()
    node(StudentEventsTags.MY_EVENTS).performClick()
    assertSavedSchedule()
    node(MyEventsTags.card(EVENT.id)).performClick()
    await(EventDetailTags.OFFLINE_BANNER)
    assertEventDetail()
    node(EventDetailTags.GOING).assertIsDisplayed()

    cancelRegistrationWhileOffline()
    node(EventDetailTags.FIND_GROUP).performClick()
    await(FindGroupTags.OFFLINE_BANNER)
    node(FindGroupTags.OFFLINE_BANNER).assertIsDisplayed()
    node(FindGroupTags.START).assertIsNotEnabled()
    node(FindGroupTags.BACK).performClick()
    await(EventDetailTags.SCREEN)
    node(EventDetailTags.BACK).performClick()
    await(MyEventsTags.OFFLINE_BANNER)
    assertSavedSchedule()
    assertEquals(setOf(UID), fakeRegistrations.attendeesOf(EVENT.id))
  }

  private fun registerThroughTheEventDetail() {
    await(EventsTags.card(EVENT.id))
    node(EventsTags.card(EVENT.id)).performClick()
    await(EventDetailTags.TITLE)
    assertEventDetail()
    compose.waitUntil("registration can be saved", TIMEOUT_MS) {
      compose
          .onAllNodes(hasTestTag(EventDetailTags.IM_GOING) and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    node(EventDetailTags.IM_GOING).performClick()
    await(EventDetailTags.GOING)
    assertEquals(setOf(UID), fakeRegistrations.attendeesOf(EVENT.id))
  }

  private fun goOffline() {
    compose.runOnIdle {
      fakeGroups.online = false
      fakeGroups.readFromCache = true
      fakeEvents.readFromCache = true
      fakeRegistrations.readFromCache = true
      fakeRegistrations.registerFailure = RegisterResult.NetworkError
      fakeRegistrations.cancelFailure = CancelRegistrationResult.NetworkError
      fakeNetwork.online = false
      // Re-emit the event's cache metadata to existing listeners, keeping its saved content.
      fakeEvents.seed()
    }
  }

  private fun assertSavedSchedule() {
    await(MyEventsTags.card(EVENT.id))
    node(MyEventsTags.card(EVENT.id)).assertTextContains(EVENT.title)
    node(MyEventsTags.details(EVENT.id)).assertTextContains("20:00", substring = true)
    node(MyEventsTags.details(EVENT.id)).assertTextContains("23:00", substring = true)
    node(MyEventsTags.details(EVENT.id)).assertTextContains(EVENT.locationName, substring = true)
  }

  private fun assertEventDetail() {
    node(EventDetailTags.TITLE).assertTextEquals(EVENT.title)
    node(EventDetailTags.TIME).assertTextContains("20:00", substring = true)
    node(EventDetailTags.TIME).assertTextContains("23:00", substring = true)
    node(EventDetailTags.LOCATION).assertTextEquals(EVENT.locationName)
  }

  private fun cancelRegistrationWhileOffline() {
    node(EventDetailTags.CANCEL_REGISTRATION).performClick()
    val message = compose.activity.getString(R.string.event_detail_cancel_registration_offline)
    compose.waitUntil("offline cancellation is explained", TIMEOUT_MS) {
      compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNode(hasText(message)).assertIsDisplayed()
    node(EventDetailTags.GOING).assertIsDisplayed()
    assertEquals(setOf(UID), fakeRegistrations.attendeesOf(EVENT.id))
  }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  private fun await(tag: String) =
      compose.waitUntil("$tag is available", TIMEOUT_MS) {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
      }

  private companion object {
    const val TIMEOUT_MS = 30_000L
    const val UID = "offline-e2e-student"
    const val EMAIL = "alex.example@epfl.ch"
    const val NAME = "Alex Example"
    val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")
    val NOW: Instant = Instant.parse("2026-03-02T08:00:00Z")
    val EVENT =
        Event(
            id = "fictional-campus-evening",
            title = "Fictional campus evening",
            description = "A fictional event for the offline journey.",
            category = EventCategory.CULTURE,
            location = Coordinates(46.52, 6.57),
            locationName = "Fictional campus hall",
            startTime = Instant.parse("2026-03-03T19:00:00Z"),
            endTime = Instant.parse("2026-03-03T22:00:00Z"),
            isPrivate = false,
            createdBy = "another-student",
            organizerIds = listOf("another-student"),
        )
  }
}
