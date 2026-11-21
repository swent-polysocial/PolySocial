// Contributors: Claude Opus 5.5 (a fake FriendRepository for the scanner's ViewModel, #359);
// Konstantinos Perdikeas (directed, reviewed and owns it).
// Contributors: Claude (Fable 5.2, Anthropic) wrote this end-to-end test of going to an event,
// joining a group and sending a chat message, #380; Claude (Opus 5.5, Anthropic) reordered its
// group fixtures.
// Contributors: Claude (Opus 5.5, Anthropic) typed the message through typeText, #427.
package com.polysocial.endtoend

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
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
import com.polysocial.model.chat.FakeOneToOneChatRepository
import com.polysocial.model.chat.MessageModule
import com.polysocial.model.chat.MessageRepository
import com.polysocial.model.chat.OneToOneChatModule
import com.polysocial.model.chat.OneToOneChatRepository
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
import com.polysocial.model.registration.FakeRegistrationRepository
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
import com.polysocial.typeText
import com.polysocial.ui.chat.ChatTags
import com.polysocial.ui.event.detail.EventDetailTags
import com.polysocial.ui.event.list.EventsTags
import com.polysocial.ui.group.FindGroupTags
import com.polysocial.ui.group.YourGroupTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Groups & Matchmaking end-to-end test (M3): a signed-in student goes to an event with a group
 * found in the app, from the real [MainActivity] and its Hilt ViewModels, through the UI only (test
 * tags).
 *
 * Steps, in order:
 * 1. The event's detail opens from the Events tab; Find a group is greyed out until the student is
 *    going (#105, #112, #244).
 * 2. "I'm going" registers the student and enables Find a group (#122).
 * 3. Find a group lists the open groups, best match first, with who is in each and why it fits; a
 *    full group isn't offered (#231, #233).
 * 4. Join returns to the detail, which says so and shows the group card instead of Find a group
 *    (#233).
 * 5. The card opens the group's members (#234).
 * 6. The members' screen opens the group chat on what was already said, and the student sends a
 *    message (#237, #238).
 *
 * Every data source is a fake bound through Hilt (no Firebase, no network, no real user data) and
 * the clock is fixed, as in [M1EndToEndTest]. The Map tab is never opened, so Mapbox isn't drawn.
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
    OneToOneChatModule::class,
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
class FindGroupAndChatEndToEndTest {

  private val fakeRegistrations =
      FakeRegistrationRepository(currentUid = UID).apply { readableEventIds += PARTY.id }
  private val fakeGroups =
      FakeGroupRepository(currentUid = UID).apply { readableEventIds += PARTY.id }
  // Stored oldest first, which is neither the ranked order nor its reverse. The fake numbers the
  // groups in this order and equal scores rank by ID, so the stranger's group gets the lowest ID:
  // if the shared section stopped counting, it would tie with the same-section group and come
  // before it.
  private val strangerGroup = fakeGroups.seedGroup(PARTY.id, listOf(LINA.uid))
  private val bestGroup = fakeGroups.seedGroup(PARTY.id, listOf(JULIA.uid, MARC.uid))
  private val sameSectionGroup = fakeGroups.seedGroup(PARTY.id, listOf(NOAH.uid))
  private val fullGroup = fakeGroups.seedGroup(PARTY.id, FULL_GROUP.map { it.uid })
  private val fakeMessages =
      FakeMessageRepository(currentUid = UID).apply {
        seedMessage(PARTY.id, bestGroup.id, "greeting", JULIA.uid, GREETING, NOW.minusSeconds(3600))
      }

  @BindValue @JvmField val messages: MessageRepository = fakeMessages
  @BindValue @JvmField val oneToOneChats: OneToOneChatRepository = FakeOneToOneChatRepository()
  @BindValue
  @JvmField
  val auth: AuthRepository =
      FakeAuthRepository(AuthUser(UID, EMAIL, isEmailVerified = true, displayName = ME.displayName))
  @BindValue
  @JvmField
  val events: EventRepository =
      FakeEventRepository(currentUid = UID, now = NOW).apply { seed(PARTY) }
  @BindValue
  @JvmField
  val profiles: UserProfileRepository =
      FakeUserProfileRepository(
          getProfileResult =
              ProfileResult.Found(
                  UserProfile(
                      UID,
                      EMAIL,
                      ME.displayName,
                      ME.section,
                      ME.year,
                      interests = ME.interests,
                  )
              )
      )
  @BindValue @JvmField val associations: AssociationRepository = FakeAssociationRepository()
  @BindValue @JvmField val clock: Clock = Clock.fixed(NOW, ZURICH)
  @BindValue @JvmField val verificationClock = VerificationClock { NOW.toEpochMilli() }
  @BindValue
  @JvmField
  val associationSignupStore: AssociationSignupStore = EmptyAssociationSignupStore
  @BindValue @JvmField val sessionGuard: SessionGuard = FakeSessionGuard()
  // PolySocialApp injects the real guard, which needs these too, though nothing starts it here.
  @BindValue @JvmField val sessionSource: AuthSessionSource = FakeAuthSessionSource()
  @BindValue @JvmField val sessionOwner: SessionOwnerStore = FakeSessionOwnerStore()
  @BindValue @JvmField val verificationStore: VerificationStore = MemoryVerificationStore()
  @BindValue @JvmField val network: NetworkMonitor = FakeNetworkMonitor()
  @BindValue @JvmField val friends: FriendRepository = FakeFriendRepository()

  @BindValue @JvmField val registrations: RegistrationRepository = fakeRegistrations
  @BindValue @JvmField val groups: GroupRepository = fakeGroups
  @BindValue
  @JvmField
  val publicProfiles: PublicProfileRepository =
      FakePublicProfileRepository(
          (listOf(ME, JULIA, MARC, NOAH, LINA) + FULL_GROUP).associateByTo(mutableMapOf()) {
            it.uid
          }
      )
  // Once going, the detail reads whether a reminder is scheduled: not from the device's
  // WorkManager.
  @BindValue @JvmField val reminders: ReminderScheduler = FakeReminderScheduler(clock)
  // The flow never logs out or deletes the account, but the app's graph still needs these.
  @BindValue @JvmField val deletion: AccountDeletionRepository = FakeAccountDeletionRepository()
  @BindValue
  @JvmField
  val cache: SessionCache =
      object : SessionCache {
        override suspend fun clear() = CacheClearResult.Cleared
      }
  // The permission was already asked once and refused, so no system dialog interrupts the test.
  @BindValue
  @JvmField
  val location: LocationService = FakeLocationService().apply { markPermissionRequested() }
  @BindValue @JvmField val geocoding: GeocodingService = FakeGeocodingService()

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun studentJoinsAGroupAndChatsWithIt() {
    openTheEvent()
    sayImGoing()
    seeTheRankedGroups()
    joinTheBestMatch()
    seeTheMembers()
    sendAMessage()
  }

  private fun openTheEvent() {
    await(EventsTags.card(PARTY.id))
    node(EventsTags.card(PARTY.id)).performClick()
    awaitMatch(hasTestTag(EventDetailTags.TITLE) and hasText(PARTY.title))
    // Not going yet: Find a group is there, greyed out (#244), and there is no group to show.
    node(EventDetailTags.FIND_GROUP).assertIsNotEnabled()
    node(EventDetailTags.YOUR_GROUP).assertDoesNotExist()
  }

  private fun sayImGoing() {
    // "I'm going" can't be tapped until the student's registrations are read.
    awaitMatch(hasTestTag(EventDetailTags.IM_GOING) and isEnabled())
    node(EventDetailTags.IM_GOING).performClick()

    await(EventDetailTags.GOING)
    node(EventDetailTags.FIND_GROUP).assertIsEnabled()
    assertEquals(setOf(UID), fakeRegistrations.attendeesOf(PARTY.id))
  }

  private fun seeTheRankedGroups() {
    node(EventDetailTags.FIND_GROUP).performClick()
    await(FindGroupTags.card(bestGroup.id))
    // Best match first, whatever order the groups were formed in.
    assertEquals(
        listOf(bestGroup.id, sameSectionGroup.id, strangerGroup.id),
        listOf(strangerGroup.id, bestGroup.id, sameSectionGroup.id).sortedBy {
          node(FindGroupTags.card(it)).fetchSemanticsNode().positionInRoot.y
        },
    )
    // A full group can't be joined, however well it fits.
    node(FindGroupTags.card(fullGroup.id)).assertDoesNotExist()
    // Each card says who is in the group, how full it is and why it fits, and nothing else: a
    // group that has less in common gives fewer reasons, down to none.
    assertEquals(
        listOf(
            "JE",
            "ME",
            "2 of 4",
            "2 places left",
            "Join",
            "Julia Example · IN · BA3   Marc Example · IN · MA1",
            "2 shared interests",
            "Same section · IN",
            "Same year · BA3",
        ),
        textsIn(FindGroupTags.card(bestGroup.id)),
    )
    assertEquals(
        listOf(
            "NE",
            "1 of 4",
            "3 places left",
            "Join",
            "Noah Example · IN · MA1",
            "Same section · IN",
        ),
        textsIn(FindGroupTags.card(sameSectionGroup.id)),
    )
    assertEquals(
        listOf("LE", "1 of 4", "3 places left", "Join", "Lina Example · GM · MA1"),
        textsIn(FindGroupTags.card(strangerGroup.id)),
    )
  }

  private fun joinTheBestMatch() {
    node(FindGroupTags.join(bestGroup.id)).performScrollTo().performClick()

    // Back on the detail, which says so and shows the group instead of Find a group.
    awaitMatch(hasText(JOINED))
    // The members' names join the card once their profiles are read.
    awaitMatch(
        hasTestTag(EventDetailTags.YOUR_GROUP) and
            hasText("Julia, Marc and you. One place left, so someone may still join.")
    )
    node(EventDetailTags.YOUR_GROUP).assertTextContains("Your group · 3 of 4")
    node(EventDetailTags.FIND_GROUP).assertDoesNotExist()

    val members = fakeGroups.groupsOf(PARTY.id).single { it.id == bestGroup.id }.memberUids
    assertEquals(listOf(JULIA.uid, MARC.uid, UID), members)
    // Like the Security Rules, the chat takes its members from the group.
    fakeMessages.setMembers(PARTY.id, bestGroup.id, members.toSet())
    // The app shell's Snackbar stays up over the screens opened next and covers the chat's Send:
    // the tap would land on the Snackbar (#380). Its few seconds run on the test's clock, which
    // moves only while the test waits: without this wait it is still up at Send on any device.
    compose.waitUntil("the Snackbar is gone", TIMEOUT_MS) {
      compose.onAllNodes(hasText(JOINED)).fetchSemanticsNodes().isEmpty()
    }
  }

  private fun seeTheMembers() {
    node(EventDetailTags.YOUR_GROUP).performScrollTo().performClick()
    awaitMatch(textIn(YourGroupTags.SCREEN, "Members · 3 of 4"))
    // Each member under their initials, with their own section, year and interests, then the
    // student ("You" as initials and as name), then the place still free.
    assertShowsInARow(
        YourGroupTags.SCREEN,
        "Members · 3 of 4",
        "JE",
        "Julia Example",
        "IN · BA3 · Music, Cinema, Games",
        "ME",
        "Marc Example",
        "IN · MA1 · Music",
        "You",
        "You",
        "IN · BA3 · Music, Cinema",
        "One place left, so someone may still join.",
    )
  }

  private fun sendAMessage() {
    node(YourGroupTags.CHAT).performScrollTo().performClick()
    // The chat opens on what the group already said, under its sender's name once it is read.
    awaitMatch(textIn(ChatTags.TIMELINE, "Julia Example"))
    val earlier = listOf("Today", "Julia Example", "JE", GREETING)
    assertEquals(earlier, textsIn(ChatTags.TIMELINE))

    // Typing closes the keyboard, which opens late and would move Send after the tap is aimed.
    compose.typeText(node(ChatTags.INPUT), MESSAGE)
    node(ChatTags.SEND).assertIsEnabled().performClick()

    awaitMatch(textIn(ChatTags.TIMELINE, MESSAGE))
    compose.onNode(textIn(ChatTags.TIMELINE, MESSAGE)).assertIsDisplayed()
    // Under the earlier message come only the new message's day (the fake dates it years later,
    // so its text isn't checked) and the message: no name or initials over it, since it is the
    // student's own.
    val shown = textsIn(ChatTags.TIMELINE)
    assertEquals(earlier, shown.take(earlier.size))
    assertEquals(listOf(MESSAGE), shown.drop(earlier.size + 1))
    node(ChatTags.RETRY_SEND).assertDoesNotExist()
    assertEquals(
        listOf(JULIA.uid to GREETING, UID to MESSAGE),
        fakeMessages.messagesOf(PARTY.id, bestGroup.id).map { it.senderId to it.text },
    )
  }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  /** The whole text [text], drawn inside the node tagged [tag]. */
  private fun textIn(tag: String, text: String) = hasText(text) and hasAnyAncestor(hasTestTag(tag))

  /** Every text drawn inside the node tagged [tag], in the order the screen lays them out. */
  private fun textsIn(tag: String): List<String> =
      compose
          .onAllNodes(hasAnyAncestor(hasTestTag(tag)))
          .fetchSemanticsNodes()
          .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
          .map { it.text }

  /** Checks that [texts] are drawn one right after the other inside the node tagged [tag]. */
  private fun assertShowsInARow(tag: String, vararg texts: String) {
    val shown = textsIn(tag)
    assertTrue(
        "Expected ${texts.toList()} in a row inside $tag, which shows $shown",
        Collections.indexOfSubList(shown, texts.toList()) >= 0,
    )
  }

  private fun await(tag: String) = awaitMatch(hasTestTag(tag))

  private fun awaitMatch(matcher: SemanticsMatcher) =
      compose.waitUntil(matcher.description, TIMEOUT_MS) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
      }

  private companion object {
    // Generous for CI's freshly booted emulator; a passing run doesn't wait for it.
    const val TIMEOUT_MS = 30_000L
    const val UID = "e2e-student"
    const val EMAIL = "alex.example@epfl.ch"
    const val JOINED = "You joined the group."
    const val GREETING = "Shall we meet at the entrance?"
    const val MESSAGE = "Yes, see you there at 8!"

    val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")
    /** Monday 2 March 2026, 09:00 in Zurich. */
    val NOW: Instant = Instant.parse("2026-03-02T08:00:00Z")

    /** The student. */
    val ME = PublicProfile(UID, "Alex Example", "IN", "BA3", setOf("music", "cinema"))
    /** Shares both interests, the section and the year. */
    val JULIA =
        PublicProfile("julia", "Julia Example", "IN", "BA3", setOf("music", "cinema", "games"))
    /** Shares one interest and the section. */
    val MARC = PublicProfile("marc", "Marc Example", "IN", "MA1", setOf("music"))
    /** Shares only the section. */
    val NOAH = PublicProfile("noah", "Noah Example", "IN", "MA1", setOf("sports"))
    /** Shares nothing. */
    val LINA = PublicProfile("lina", "Lina Example", "GM", "MA1", setOf("sports"))
    /** Four students just like the student: the best match, if their group had a place left. */
    val FULL_GROUP = (1..4).map { ME.copy(uid = "full-$it", displayName = "Full Example $it") }

    /** Another student's event, tomorrow evening, so the Events tab lists it. */
    val PARTY =
        Event(
            id = "party",
            title = "Balelec warm-up",
            description = "Meet before the concerts.",
            category = EventCategory.PARTY,
            location = Coordinates(latitude = 46.5197, longitude = 6.5657),
            startTime = Instant.parse("2026-03-03T19:00:00Z"),
            endTime = Instant.parse("2026-03-03T22:00:00Z"),
            capacity = null,
            isPrivate = false,
            createdBy = "another-student",
            organizerIds = listOf("another-student"),
        )
  }
}
