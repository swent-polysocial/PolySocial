// Contributors: Claude (end-to-end test of the organizer flow: create, edit and cancel an event,
// #239).
package com.polysocial.endtoend

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
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
import com.polysocial.model.auth.VerificationTiming
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.firebase.FakeSessionGuard
import com.polysocial.model.firebase.FakeSessionOwnerStore
import com.polysocial.model.firebase.SessionGuard
import com.polysocial.model.firebase.SessionOwnerStore
import com.polysocial.model.geocoding.FakeGeocodingService
import com.polysocial.model.geocoding.GeocodingModule
import com.polysocial.model.geocoding.GeocodingService
import com.polysocial.model.geocoding.Place
import com.polysocial.model.geocoding.PlaceSearchResult
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.location.LocationModule
import com.polysocial.model.location.LocationService
import com.polysocial.model.network.NetworkModule
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.time.ClockModule
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.model.user.UserProfileModule
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.resources.C
import com.polysocial.ui.event.create.LocationPickerTags
import com.polysocial.ui.event.detail.EventDetailTags
import com.polysocial.ui.event.list.EventsTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The organizer's end-to-end test (M2): a signed-in student manages an event they organize, from
 * the real [MainActivity] and its Hilt ViewModels, through the UI only (test tags).
 *
 * Steps, in order:
 * 1. The app opens on the Events tab for a student who is already signed in (#74, #105).
 * 2. Create Event from the "+" (#46, #110), with a place found by the location picker's search
 *    (#111), then "View event" opens its detail (#211) with "Organized by you" (#131).
 * 3. Edit opens the form filled with the event, a new title is saved, and the detail shows it
 *    (#130, #131).
 * 4. Cancel event, confirmed in its sheet, deletes the event: the Events tab says so and no longer
 *    lists it, while another organizer's event stays (#130, #131).
 *
 * Every data source is a fake bound through Hilt (no Firebase, no network, no real user data) and
 * the clock is fixed, as in [M1EndToEndTest]. The Map tab is never opened, so Mapbox isn't drawn.
 */
@HiltAndroidTest
@UninstallModules(
    AssociationModule::class,
    AuthModule::class,
    ClockModule::class,
    EventModule::class,
    GeocodingModule::class,
    LocationModule::class,
    NetworkModule::class,
    UserProfileModule::class,
)
@RunWith(AndroidJUnit4::class)
class ManageEventEndToEndTest {

  private val fakeEvents = FakeEventRepository(currentUid = UID, now = NOW).apply { seed(PARTY) }

  @BindValue
  @JvmField
  val auth: AuthRepository =
      FakeAuthRepository(AuthUser(UID, EMAIL, isEmailVerified = true, displayName = NAME))
  @BindValue @JvmField val events: EventRepository = fakeEvents
  @BindValue
  @JvmField
  val profiles: UserProfileRepository =
      FakeUserProfileRepository(
          getProfileResult =
              ProfileResult.Found(UserProfile(UID, EMAIL, NAME, section = "IN", year = "BA3"))
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
  @BindValue @JvmField val verificationStore: VerificationStore = InMemoryVerificationStore()
  @BindValue @JvmField val network: NetworkMonitor = AlwaysOnline
  @BindValue
  @JvmField
  val location: LocationService = FakeLocationService().apply { markPermissionRequested() }
  @BindValue
  @JvmField
  val geocoding: GeocodingService =
      FakeGeocodingService(searchResult = PlaceSearchResult.Found(listOf(ROLEX)))

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun organizerManagesTheirEvent() {
    openTheApp()
    val id = createAndOpenEvent()
    editTheTitle(id)
    cancelTheEvent(id)
  }

  private fun openTheApp() {
    await(EventsTags.LIST)
    await(EventsTags.card(PARTY.id))
  }

  /** Creates the event, opens its detail from the confirmation and returns the new event's id. */
  private fun createAndOpenEvent(): String {
    node(C.Tag.create_event_button).performClick()
    await(C.Tag.create_event_screen)
    type(C.Tag.create_event_title, TITLE)
    node(C.Tag.createEventCategory(EventCategory.STUDY.name)).performScrollTo().performClick()
    pickDate(DATE)
    // The time picker opens on 18:00.
    node(C.Tag.create_event_start).performScrollTo().performClick()
    node(C.Tag.create_event_dialog_confirm).performClick()
    pickRolexWithTheSearch()
    node(C.Tag.create_event_public).performScrollTo().performClick()
    node(C.Tag.create_event_submit).performClick()

    await(C.Tag.event_created_screen)
    val created = fakeEvents.events.single { it.title == TITLE }
    assertEquals(DATE.atTime(LocalTime.of(18, 0)).atZone(ZURICH).toInstant(), created.startTime)
    assertEquals(listOf(UID), created.organizerIds)

    node(C.Tag.event_created_view_event).performClick()
    await(EventDetailTags.SCREEN)
    awaitMatch(hasTestTag(EventDetailTags.TITLE) and hasText(TITLE))
    await(EventDetailTags.ORGANIZED_BY_YOU)
    return created.id
  }

  private fun editTheTitle(id: String) {
    node(EventDetailTags.EDIT).performClick()
    // The form opens filled with the stored event.
    awaitMatch(hasTestTag(C.Tag.create_event_title) and hasText(TITLE))
    node(C.Tag.create_event_title).performScrollTo().performTextClearance()
    type(C.Tag.create_event_title, EDITED_TITLE)
    node(C.Tag.create_event_submit).performClick()

    // Saving closes the form, and the live detail shows the stored change.
    await(EventDetailTags.SCREEN)
    awaitMatch(hasTestTag(EventDetailTags.TITLE) and hasText(EDITED_TITLE))
    val edited = fakeEvents.events.single { it.id == id }
    assertEquals(EDITED_TITLE, edited.title)
    assertEquals(EventCategory.STUDY, edited.category)
    assertEquals(ROLEX.coordinates, edited.location)
  }

  private fun cancelTheEvent(id: String) {
    // In the detail's action bar, which doesn't scroll.
    node(EventDetailTags.CANCEL_EVENT).performClick()
    await(EventDetailTags.CANCEL_SHEET)
    node(EventDetailTags.CONFIRM_CANCEL).performClick()

    // Back on the Events tab, which says the event is cancelled and no longer lists it.
    await(EventsTags.LIST)
    awaitMatch(hasText("Event cancelled", substring = true))
    compose.waitUntil(TIMEOUT_MS) { count(EventsTags.card(id)) == 0 }
    node(EventsTags.card(PARTY.id)).assertTextContains(PARTY.title, substring = true)
    assertEquals(
        0,
        compose.onAllNodes(hasText(EDITED_TITLE, substring = true)).fetchSemanticsNodes().size,
    )
    assertEquals(listOf(id), fakeEvents.deleteRequests)
    assertTrue(fakeEvents.events.none { it.id == id })
  }

  /**
   * Types [date] in the date picker's text input mode: the calendar mode opens on the device's
   * current month, which would make the test depend on the real date (see CONTEXT.md, Traps).
   */
  private fun pickDate(date: LocalDate) {
    node(C.Tag.create_event_date).performScrollTo().performClick()
    compose.onNode(hasContentDescription("text input mode", substring = true)).performClick()
    compose
        .onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        .performTextInput(dateDigits(date))
    node(C.Tag.create_event_dialog_confirm).performClick()
    node(C.Tag.create_event_date).assertTextContains(date.dayOfMonth.toString(), substring = true)
  }

  private fun pickRolexWithTheSearch() {
    node(C.Tag.create_event_location).performScrollTo().performClick()
    await(LocationPickerTags.SCREEN)
    node(LocationPickerTags.QUERY).performTextInput("Rolex")
    node(LocationPickerTags.SEARCH).performClick()
    await(LocationPickerTags.result(0))
    node(LocationPickerTags.result(0)).performClick()
    node(LocationPickerTags.USE).performClick()
    await(C.Tag.create_event_screen)
    node(C.Tag.create_event_location).assertTextContains(ROLEX.name, substring = true)
  }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  private fun type(tag: String, text: String) = node(tag).performScrollTo().performTextInput(text)

  private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

  private fun await(tag: String) = awaitMatch(hasTestTag(tag))

  private fun awaitMatch(matcher: SemanticsMatcher) =
      compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
      }

  private class InMemoryVerificationStore : VerificationStore {
    private val timings = mutableMapOf<String, VerificationTiming>()

    override suspend fun read(uid: String) = timings[uid] ?: VerificationTiming()

    override suspend fun write(uid: String, timing: VerificationTiming) {
      timings[uid] = timing
    }

    override suspend fun clear(uid: String) {
      timings.remove(uid)
    }
  }

  private object AlwaysOnline : NetworkMonitor {
    override fun isOnline() = true
  }

  private companion object {
    // Generous for CI's freshly booted emulator; a passing run doesn't wait for it.
    const val TIMEOUT_MS = 30_000L
    const val UID = "e2e-organizer"
    const val NAME = "Sam Example"
    const val EMAIL = "sam.example@epfl.ch"
    const val TITLE = "Study together"
    const val EDITED_TITLE = "Study together for the exam"

    val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")
    /** Monday 2 March 2026, 09:00 in Zurich. */
    val NOW: Instant = Instant.parse("2026-03-02T08:00:00Z")
    /** Thursday, within the 14 days the Events tab shows. */
    val DATE: LocalDate = LocalDate.of(2026, 3, 5)

    val ROLEX =
        Place(
            name = "Rolex Learning Center",
            address = "Route Cantonale, 1015 Lausanne",
            coordinates = Coordinates(latitude = 46.5184, longitude = 6.5681),
        )

    /** Another organizer's event, which must stay listed after the cancellation. */
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

    /** [date] as the digits the date picker's text field expects (see [M1EndToEndTest]). */
    fun dateDigits(date: LocalDate): String {
      val pattern =
          DateTimeFormatterBuilder.getLocalizedDateTimePattern(
              FormatStyle.SHORT,
              null,
              IsoChronology.INSTANCE,
              Locale.getDefault(),
          )
      return pattern
          .filter { it in "dMy" }
          .toList()
          .distinct()
          .joinToString("") {
            when (it) {
              'd' -> "%02d".format(date.dayOfMonth)
              'M' -> "%02d".format(date.monthValue)
              else -> "%04d".format(date.year)
            }
          }
    }
  }
}
