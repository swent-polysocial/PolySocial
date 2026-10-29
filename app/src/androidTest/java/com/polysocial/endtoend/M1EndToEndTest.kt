// Contributors: OpenAI Codex (keep association onboarding dependencies fake in Hilt tests, #125).
// Contributors: Claude (end-to-end test of the M1 student flow with Hilt test bindings and a fake
// map renderer, #136; a fake association repository for the association names, #128; the
// student's organizer row, #134).
package com.polysocial.endtoend

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.model.association.AssociationModule
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AssociationSignupStore
import com.polysocial.model.auth.AuthModule
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.EmptyAssociationSignupStore
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.auth.SignUpField
import com.polysocial.model.auth.SignUpResult
import com.polysocial.model.auth.VerificationClock
import com.polysocial.model.auth.VerificationResult
import com.polysocial.model.auth.VerificationStore
import com.polysocial.model.auth.VerificationTiming
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
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
import com.polysocial.model.user.UserProfileModule
import com.polysocial.model.user.UserProfileRepository
import com.polysocial.resources.C
import com.polysocial.ui.auth.AuthFlow
import com.polysocial.ui.auth.SignUpTags
import com.polysocial.ui.auth.SignUpViewModel
import com.polysocial.ui.auth.VerifyEmailTags
import com.polysocial.ui.auth.WelcomeTags
import com.polysocial.ui.event.create.LocationPickerTags
import com.polysocial.ui.event.detail.EventDetailTags
import com.polysocial.ui.event.list.EventsTags
import com.polysocial.ui.map.MapRenderStatus
import com.polysocial.ui.map.MapRoute
import com.polysocial.ui.map.MapTags
import com.polysocial.ui.navigation.AppShell
import com.polysocial.ui.profile.ProfileSetupTestTags
import com.polysocial.ui.theme.PolySocialTheme
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M1 end-to-end test: a new student goes through every feature of the app together, from the
 * real [MainActivity] and its Hilt ViewModels, through the UI only (test tags).
 *
 * Features covered, in order:
 * 1. Welcome → Sign up with an `@epfl.ch` email (#30, #87).
 * 2. Verify Email: the email is sent, the student confirms it and taps Continue (#31, #99).
 * 3. Profile step: section and year, then the app opens on the Events tab (#34, #74).
 * 4. Events tab: the upcoming public events, grouped by day (#105).
 * 5. Event detail opened from the list, and Back to the list (#112).
 * 6. Create Event from the "+" (#46, #110): "Organized by you" for a student (#134), title,
 *    category, date and time pickers, public, and a place found with the location picker's search
 *    (#111).
 * 7. The new event appears in the Events list and its detail opens (#105, #112).
 * 8. Map tab: both events have a marker, and tapping the new one opens its preview (#50, #94).
 *
 * Every data source is a fake bound through Hilt (no Firebase, no network, no real user data), the
 * clock is fixed, and the location comes from [FakeLocationService]. Mapbox draws its markers
 * outside Compose, so the Map tab gets [FakeMapRenderer] instead (see [useFakeMapRenderer]), like
 * `MapRepositoryRouteTest`; `MapboxRendererTest` covers the real renderer. To extend this test in
 * later sprints, add a step for the new feature in [m1Flow].
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
class M1EndToEndTest {
  @BindValue
  @JvmField
  val associationSignupStore: AssociationSignupStore = EmptyAssociationSignupStore
  @BindValue @JvmField val associations: AssociationRepository = FakeAssociationRepository()

  private val fakeAuth =
      FakeAuthRepository().apply {
        signUpResult = SignUpResult.Success(AuthUser(UID, EMAIL, isEmailVerified = false, NAME))
      }
  private val fakeEvents = FakeEventRepository(currentUid = UID, now = NOW).apply { seed(PARTY) }
  private val fakeProfiles = FakeUserProfileRepository()

  @BindValue @JvmField val auth: AuthRepository = fakeAuth
  @BindValue @JvmField val events: EventRepository = fakeEvents
  @BindValue @JvmField val profiles: UserProfileRepository = fakeProfiles
  @BindValue @JvmField val associations: AssociationRepository = FakeAssociationRepository()
  @BindValue @JvmField val clock: Clock = Clock.fixed(NOW, ZURICH)
  @BindValue @JvmField val verificationClock = VerificationClock { NOW.toEpochMilli() }
  @BindValue @JvmField val verificationStore: VerificationStore = InMemoryVerificationStore()
  @BindValue @JvmField val network: NetworkMonitor = AlwaysOnline
  // The permission was already asked once and refused, so no system dialog interrupts the test.
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
  fun m1Flow() {
    useFakeMapRenderer()
    signUp()
    verifyEmail()
    setUpProfile()
    browseUpcomingEvents()
    createPublicEvent()
    findTheNewEvent()
    seeEventsOnTheMap()
  }

  private fun signUp() {
    await(WelcomeTags.Screen)
    node(WelcomeTags.SignUp).performScrollTo().performClick()
    await(SignUpTags.Screen)
    type(SignUpTags.input(SignUpField.FullName), NAME)
    type(SignUpTags.input(SignUpField.Email), EMAIL)
    type(SignUpTags.input(SignUpField.Password), PASSWORD)
    type(SignUpTags.input(SignUpField.ConfirmPassword), PASSWORD)
    node(SignUpTags.Submit).performScrollTo().performClick()

    await(VerifyEmailTags.Screen)
    assertEquals(EMAIL, fakeAuth.lastSignUpEmail)
  }

  private fun verifyEmail() {
    // Continue is disabled while the first email is being sent (see CONTEXT.md, Traps).
    awaitMatch(hasTestTag(VerifyEmailTags.Continue) and isEnabled())
    assertEquals(1, fakeAuth.sendCalls)
    // The student opens the link from the email in the browser.
    fakeAuth.verificationResult = VerificationResult.Verified
    // A semantic click: the "email sent" Snackbar can cover Continue on small screens, where a tap
    // would land on it instead (see CONTEXT.md, Traps).
    node(VerifyEmailTags.Continue).performSemanticsAction(SemanticsActions.OnClick)
  }

  private fun setUpProfile() {
    await(ProfileSetupTestTags.SCREEN)
    node(ProfileSetupTestTags.FULL_NAME).assertTextContains(NAME)
    // The option lists scroll: on a small screen (like CI's emulator) the option can be below the
    // visible part of the list, where a tap would miss it and leave the list open.
    node(ProfileSetupTestTags.SECTION).performScrollTo().performClick()
    node(ProfileSetupTestTags.sectionOption(SECTION)).performScrollTo().performClick()
    node(ProfileSetupTestTags.YEAR).performScrollTo().performClick()
    node(ProfileSetupTestTags.yearOption(YEAR)).performScrollTo().performClick()
    node(ProfileSetupTestTags.CONTINUE).performScrollTo().performClick()

    await(C.Tag.app_shell)
    val saved = fakeProfiles.createdProfiles.single()
    assertEquals(UID, saved.uid)
    assertEquals(SECTION, saved.section)
    assertEquals(YEAR, saved.year)
  }

  private fun browseUpcomingEvents() {
    await(EventsTags.LIST)
    node(EventsTags.dayHeader(0)).assertTextContains("Tomorrow")
    node(EventsTags.card(PARTY.id)).assertTextContains(PARTY.title, substring = true)

    node(EventsTags.card(PARTY.id)).performClick()
    await(EventDetailTags.SCREEN)
    node(EventDetailTags.TITLE).assertTextContains(PARTY.title)
    node(EventDetailTags.BACK).performClick()
    await(EventsTags.LIST)
  }

  private fun createPublicEvent() {
    node(C.Tag.create_event_button).performClick()
    await(C.Tag.create_event_screen)
    // A student organizes in their own name (#134).
    await(C.Tag.create_event_organized_by)
    node(C.Tag.create_event_organized_by).assertTextEquals("Organized by you")
    type(C.Tag.create_event_title, NEW_TITLE)
    node(C.Tag.createEventCategory(EventCategory.STUDY.name)).performScrollTo().performClick()
    pickDate(NEW_DATE)
    // The time picker opens on 18:00.
    node(C.Tag.create_event_start).performScrollTo().performClick()
    node(C.Tag.create_event_dialog_confirm).performClick()
    pickRolexWithTheSearch()
    node(C.Tag.create_event_public).performScrollTo().performClick()
    node(C.Tag.create_event_submit).performClick()

    await(C.Tag.event_created_screen)
    val created = fakeEvents.events.single { it.title == NEW_TITLE }
    assertEquals(EventCategory.STUDY, created.category)
    assertEquals(NEW_DATE.atTime(LocalTime.of(18, 0)).atZone(ZURICH).toInstant(), created.startTime)
    assertEquals(ROLEX.coordinates, created.location)
    assertEquals(false, created.isPrivate)
    node(C.Tag.create_event_close).performClick()
  }

  /**
   * Types [date] in the date picker's text input mode: the calendar mode opens on the device's
   * current month, which would make the test depend on the real date.
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

  private fun findTheNewEvent() {
    await(EventsTags.LIST)
    val card = hasText(NEW_TITLE, substring = true) and hasAnyAncestor(hasTestTag(EventsTags.LIST))
    awaitMatch(card)
    compose.onNode(card).performScrollTo().performClick()
    await(EventDetailTags.SCREEN)
    node(EventDetailTags.TITLE).assertTextContains(NEW_TITLE)
    node(EventDetailTags.BACK).performClick()
    await(EventsTags.LIST)
  }

  private fun seeEventsOnTheMap() {
    node(C.Tag.nav_item_map).performClick()
    await(C.Tag.screen_map)
    val created = fakeEvents.events.single { it.title == NEW_TITLE }
    await(MapTags.marker(PARTY.id))
    await(MapTags.marker(created.id))

    node(MapTags.marker(created.id)).performClick()
    await(MapTags.PREVIEW)
    node(MapTags.TITLE).assertTextContains(NEW_TITLE)
  }

  /**
   * Shows the app as [MainActivity] does (its theme and [AuthFlow] with the activity's Hilt
   * ViewModels), except that the Map tab draws [FakeMapRenderer] instead of Mapbox.
   */
  private fun useFakeMapRenderer() {
    compose.activity.setContent {
      PolySocialTheme {
        AuthFlow(
            viewModel = hiltViewModel<SignUpViewModel>(),
            onExit = {},
            signedInContent = {
              AppShell(
                  mapContent = { onViewDetails, onCreateEvent ->
                    MapRoute(
                        onViewDetails,
                        onCreateEvent = onCreateEvent,
                        tokenConfigured = true,
                        renderer = { events, onSelect, onStatus, _ ->
                          FakeMapRenderer(events, onSelect, onStatus)
                        },
                    )
                  }
              )
            },
        )
      }
    }
  }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  private fun type(tag: String, text: String) = node(tag).performScrollTo().performTextInput(text)

  private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

  private fun await(tag: String) = awaitMatch(hasTestTag(tag))

  private fun awaitMatch(matcher: SemanticsMatcher) =
      compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
      }

  /** One button per event marker, tagged like a real marker; the map reports itself loaded. */
  @Composable
  private fun FakeMapRenderer(
      events: List<Event>,
      onSelect: (String) -> Unit,
      onStatus: (MapRenderStatus) -> Unit,
  ) {
    LaunchedEffect(Unit) { onStatus(MapRenderStatus.READY) }
    Column(
        Modifier.fillMaxSize().testTag(MapTags.CANVAS),
        verticalArrangement = Arrangement.Center,
    ) {
      events.forEach { event ->
        TextButton({ onSelect(event.id) }, Modifier.testTag(MapTags.marker(event.id))) {
          Text(event.title)
        }
      }
    }
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
    const val UID = "e2e-student"
    const val NAME = "Alex Example"
    const val EMAIL = "alex.example@epfl.ch"
    const val PASSWORD = "password1"
    const val SECTION = "IN"
    const val YEAR = "BA3"
    const val NEW_TITLE = "Study together"

    val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")
    /** Monday 2 March 2026, 09:00 in Zurich. */
    val NOW: Instant = Instant.parse("2026-03-02T08:00:00Z")
    /** Thursday, within the 14 days the Events tab shows. */
    val NEW_DATE: LocalDate = LocalDate.of(2026, 3, 5)

    val ROLEX =
        Place(
            name = "Rolex Learning Center",
            address = "Route Cantonale, 1015 Lausanne",
            coordinates = Coordinates(latitude = 46.5184, longitude = 6.5681),
        )

    /** Tomorrow evening, so the Events tab lists it under "Tomorrow". */
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
        )

    /**
     * [date] as the digits the date picker's text field expects, in the order of the device
     * locale's short date pattern (MMddyyyy in English (US)), as Material 3 reads it.
     */
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
