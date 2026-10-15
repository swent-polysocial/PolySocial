// Contributors: Claude (wrote these tests for the location picker screen, #111; pin tip and
// small-window results after review).
package com.polysocial.ui.event.create

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.Coordinates
import com.polysocial.model.geocoding.FakeGeocodingService
import com.polysocial.model.geocoding.Place
import com.polysocial.model.geocoding.PlaceSearchResult
import com.polysocial.model.geocoding.ReverseGeocodeResult
import com.polysocial.model.location.FakeLocationService
import com.polysocial.ui.theme.PolySocialTheme
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LocationPickerScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val calls = mutableListOf<String>()
  private var selected: Place? = null
  private var query: String? = null
  private var mapCamera: CameraRequest? = null
  private var settleMap: ((Coordinates) -> Unit)? = null
  private var tapMap: (() -> Unit)? = null

  private val actions =
      LocationPickerActions(
          onBack = { calls += "back" },
          onQueryChange = { query = it },
          onSearch = { calls += "search" },
          onResultSelect = { selected = it },
          onAllowInSettings = { calls += "settings" },
          onMyLocation = { calls += "my location" },
          onUseLocation = { calls += "use" },
          onCenterSettled = { calls += "settled $it" },
          onMapTap = { calls += "map tap" },
      )

  /** Stands in for the native map: records what the screen gives it. */
  private val fakeMap: PickerMapRenderer = { camera, onCenterSettled, onMapTap, _ ->
    mapCamera = camera
    settleMap = onCenterSettled
    tapMap = onMapTap
  }

  private fun show(state: LocationPickerUiState) {
    composeTestRule.setContent { PolySocialTheme { LocationPickerScreen(state, actions, fakeMap) } }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun text(id: Int, vararg args: Any) = composeTestRule.activity.getString(id, *args)

  private fun assertShown(vararg texts: String) = texts.forEach {
    composeTestRule.onNodeWithText(it).assertIsDisplayed()
  }

  private fun assertNotShown(text: String) =
      composeTestRule.onNodeWithText(text).assertDoesNotExist()

  private val access = LocationPickerUiState(hasLocationAccess = true)

  // ---------- Top: hint, location access off ----------

  @Test
  fun idleWithAccess_showsTheHintAndTheLocationButton() {
    show(access.copy(pinPlace = PinPlaceState.Found(LEARNING_CENTER)))

    node(LocationPickerTags.HINT).assertIsDisplayed()
    assertShown(
        text(R.string.location_picker_hint),
        text(R.string.location_picker_search_placeholder),
    )
    node(LocationPickerTags.MY_LOCATION).assertIsDisplayed()
    node(LocationPickerTags.PIN).assertIsDisplayed()
  }

  @Test
  fun withoutAccess_showsLocationOffWithAllowInSettingsAndNoLocationButton() {
    show(LocationPickerUiState(hasLocationAccess = false))

    assertShown(
        text(R.string.location_picker_access_off_title),
        text(R.string.location_picker_access_off_body),
    )
    node(LocationPickerTags.HINT).assertDoesNotExist()
    node(LocationPickerTags.MY_LOCATION).assertDoesNotExist()
    composeTestRule.onNodeWithText(text(R.string.location_picker_allow_in_settings)).performClick()
    assertEquals(listOf("settings"), calls)
  }

  // ---------- Search ----------

  @Test
  fun typingReportsTheQuery_andTheKeyboardsSearchKeySearches() {
    show(access)

    node(LocationPickerTags.QUERY).performTextInput("Rolex")
    node(LocationPickerTags.QUERY).performImeAction()

    assertEquals("Rolex", query)
    assertEquals(listOf("search"), calls)
  }

  @Test
  fun theSearchButtonSearches() {
    show(access.copy(query = "Rolex"))

    node(LocationPickerTags.SEARCH).assertIsEnabled().performClick()

    assertEquals(listOf("search"), calls)
    composeTestRule
        .onNodeWithText(text(R.string.location_picker_search_placeholder))
        .assertDoesNotExist()
  }

  @Test
  fun whileSearching_showsSearchingAndDisablesSearch() {
    show(access.copy(query = "Rolex", search = PlaceSearchState.Searching))

    node(LocationPickerTags.SEARCHING).assertIsDisplayed()
    assertShown(text(R.string.location_picker_searching))
    node(LocationPickerTags.SEARCH).assertIsNotEnabled()
    node(LocationPickerTags.HINT).assertDoesNotExist()
  }

  @Test
  fun results_showEachPlaceAndTheOsmCredit_andTappingOneSelectsIt() {
    show(access.copy(search = PlaceSearchState.Results(listOf(LEARNING_CENTER, NO_ADDRESS))))

    node(LocationPickerTags.RESULTS).assertIsDisplayed()
    assertShown(LEARNING_CENTER.name, LEARNING_CENTER.address, NO_ADDRESS.name)
    assertShown(text(R.string.location_picker_osm_credit))
    node(LocationPickerTags.result(1)).performClick()

    assertEquals(NO_ADDRESS, selected)
  }

  @Test
  fun thePinsTip_marksTheMapsCenter() {
    show(access)

    val screen = node(LocationPickerTags.SCREEN).getUnclippedBoundsInRoot()
    val pin = node(LocationPickerTags.PIN).getUnclippedBoundsInRoot()
    // The vector's tip is 54 dp down its 56 dp; the map reports its center as the picked spot.
    assertEquals((screen.top + screen.bottom) / 2, pin.top + 54.dp)
  }

  @Test
  @Config(qualifiers = "w640dp-h360dp")
  fun inAShortWindow_theLastOfFiveResultsScrollsIntoViewAndCanBeSelected() {
    val places =
        (1..5).map { Place("Place $it", "Rue $it, 1015 Lausanne", Coordinates(46.5, 6.5 + it)) }
    show(
        access.copy(
            search = PlaceSearchState.Results(places),
            pinPlace = PinPlaceState.Found(LEARNING_CENTER),
        )
    )

    node(LocationPickerTags.result(4)).performScrollTo().assertIsDisplayed().performClick()

    assertEquals(places.last(), selected)
  }

  @Test
  fun noResults_namesTheQuery() {
    show(access.copy(query = " Rolex Lerning ", search = PlaceSearchState.NoResults))

    assertShown(
        text(R.string.location_picker_no_results_title),
        text(R.string.location_picker_no_results_body, "Rolex Lerning"),
    )
    node(LocationPickerTags.BANNER_ACTION).assertDoesNotExist()
  }

  @Test
  fun anOfflineSearch_saysSoAndTryAgainSearches() {
    show(access.copy(search = PlaceSearchState.Failed(LookupFailure.OFFLINE)))

    assertShown(
        text(R.string.location_picker_offline_title),
        text(R.string.location_picker_offline_body),
    )
    node(LocationPickerTags.BANNER_ACTION).performClick()

    assertEquals(listOf("search"), calls)
  }

  @Test
  fun aFailedSearch_saysSoAndTryAgainSearches() {
    show(access.copy(search = PlaceSearchState.Failed(LookupFailure.UNEXPECTED)))

    assertShown(
        text(R.string.location_picker_search_failed_title),
        text(R.string.location_picker_search_failed_body),
        text(R.string.location_picker_try_again),
    )
    node(LocationPickerTags.BANNER_ACTION).performClick()

    assertEquals(listOf("search"), calls)
  }

  // ---------- The place card ----------

  @Test
  fun aFoundPlace_showsItsNameAddressAndTheAddressCredit() {
    show(access.copy(pinPlace = PinPlaceState.Found(LEARNING_CENTER)))

    assertCard(LEARNING_CENTER.name, LEARNING_CENTER.address)
    assertShown(text(R.string.location_picker_address_credit))
  }

  @Test
  fun aFoundPlaceWithoutAddress_showsItsNameOnly() {
    show(access.copy(pinPlace = PinPlaceState.Found(NO_ADDRESS)))

    assertCard(NO_ADDRESS.name)
  }

  @Test
  fun resolving_showsFindingTheAddress() {
    show(access.copy(pinPlace = PinPlaceState.Resolving))

    assertCard(text(R.string.location_picker_finding_address))
    assertShown(text(R.string.location_picker_address_credit))
  }

  @Test
  fun theStudentsPosition_showsYourLocation() {
    show(access.copy(pinPlace = PinPlaceState.YourLocation))

    assertCard(
        text(R.string.location_picker_your_location),
        text(R.string.location_picker_your_location_body),
    )
  }

  @Test
  fun noAddressThere_showsADroppedPinWithTheCredit() {
    show(access.copy(pinPlace = PinPlaceState.Unnamed(failure = null)))

    assertCard(
        text(R.string.location_picker_dropped_pin),
        text(R.string.location_picker_no_address),
    )
    assertShown(text(R.string.location_picker_address_credit))
  }

  @Test
  fun anOfflineLookup_showsADroppedPinWithoutTheCredit() {
    show(access.copy(pinPlace = PinPlaceState.Unnamed(LookupFailure.OFFLINE)))

    assertCard(
        text(R.string.location_picker_dropped_pin),
        text(R.string.location_picker_no_address_offline),
    )
    assertNotShown(text(R.string.location_picker_address_credit))
  }

  @Test
  fun aFailedLookup_showsADroppedPinWithoutTheCredit() {
    show(access.copy(pinPlace = PinPlaceState.Unnamed(LookupFailure.UNEXPECTED)))

    assertCard(
        text(R.string.location_picker_dropped_pin),
        text(R.string.location_picker_address_failed),
    )
    assertNotShown(text(R.string.location_picker_address_credit))
  }

  private fun assertCard(vararg texts: String) = texts.forEach {
    node(LocationPickerTags.CARD).assert(hasAnyDescendant(hasText(it)))
  }

  // ---------- Buttons and the map ----------

  @Test
  fun backLocationAndUseThisLocation_reportTheirActions() {
    show(access)

    node(LocationPickerTags.BACK).performClick()
    node(LocationPickerTags.MY_LOCATION).performClick()
    node(LocationPickerTags.USE).performClick()

    assertEquals(listOf("back", "my location", "use"), calls)
  }

  @Test
  fun theMap_getsTheCameraAndReportsWhereItStopsAndTaps() {
    val camera = CameraRequest(LEARNING_CENTER.coordinates, 3)
    show(access.copy(camera = camera))

    composeTestRule.runOnIdle {
      settleMap!!(LEARNING_CENTER.coordinates)
      tapMap!!()
    }

    assertEquals(camera, mapCamera)
    assertEquals(listOf("settled ${LEARNING_CENTER.coordinates}", "map tap"), calls)
  }

  // ---------- The route, with the real ViewModel ----------

  private val geocoding = FakeGeocodingService()
  private val location = FakeLocationService()
  private val viewModel = LocationPickerViewModel(geocoding, location)
  private var picked: PickedLocation? = null
  private var left = 0
  private val shown = mutableStateOf(true)

  private fun showRoute(initial: PickedLocation? = null) {
    composeTestRule.setContent {
      PolySocialTheme {
        if (shown.value) {
          LocationPickerRoute(
              initial = initial,
              onBack = { left++ },
              onPicked = { picked = it },
              viewModel = viewModel,
              renderer = fakeMap,
          )
        }
      }
    }
  }

  private fun pressBack() = composeTestRule.runOnIdle {
    composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
  }

  private fun settle() {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_MILLIS))
    composeTestRule.waitForIdle()
  }

  @Test
  fun route_opensOnTheFormsPlace_andUseThisLocationReturnsIt() {
    val initial =
        PickedLocation(LEARNING_CENTER.coordinates, LEARNING_CENTER.name, LEARNING_CENTER.address)
    showRoute(initial)

    assertCard(LEARNING_CENTER.name)
    node(LocationPickerTags.USE).performClick()
    composeTestRule.waitForIdle()

    assertEquals(initial, picked)
  }

  @Test
  fun route_anUnnamedPinIsReturnedAsADroppedPin() {
    geocoding.reverseResult = ReverseGeocodeResult.NotFound
    showRoute()
    settle()

    node(LocationPickerTags.USE).performClick()
    composeTestRule.waitForIdle()

    assertEquals(
        PickedLocation(
            LocationPickerViewModel.LAUSANNE,
            text(R.string.location_picker_dropped_pin),
            isNamed = false,
        ),
        picked,
    )
  }

  @Test
  fun route_backClosesTheResultsFirst_thenLeaves() {
    geocoding.searchResult = PlaceSearchResult.Found(listOf(LEARNING_CENTER))
    showRoute()
    node(LocationPickerTags.QUERY).performTextInput("Rolex")
    node(LocationPickerTags.SEARCH).performClick()
    node(LocationPickerTags.RESULTS).assertIsDisplayed()

    pressBack()
    node(LocationPickerTags.RESULTS).assertDoesNotExist()
    assertEquals(0, left)

    pressBack()
    assertEquals(1, left)
    assertNull(picked)
  }

  @Test
  fun route_tappingTheMapClosesTheResults() {
    geocoding.searchResult = PlaceSearchResult.Found(listOf(LEARNING_CENTER))
    showRoute()
    node(LocationPickerTags.QUERY).performTextInput("Rolex")
    node(LocationPickerTags.SEARCH).performClick()

    composeTestRule.runOnIdle { tapMap!!() }

    node(LocationPickerTags.RESULTS).assertDoesNotExist()
    assertEquals(0, left)
  }

  @Test
  fun route_withoutAMapboxTokenShowsAPlainMapAndStillConfirms() {
    geocoding.reverseResult = ReverseGeocodeResult.Found(LEARNING_CENTER)
    composeTestRule.setContent {
      PolySocialTheme {
        LocationPickerRoute(
            initial = null,
            onBack = {},
            onPicked = { picked = it },
            viewModel = viewModel,
            renderer = defaultPickerRenderer(hasToken = false),
        )
      }
    }
    settle()

    node(LocationPickerTags.MAP).assertIsDisplayed()
    node(LocationPickerTags.USE).performClick()
    composeTestRule.waitForIdle()

    assertEquals(LEARNING_CENTER.name, picked?.name)
    assertEquals(LocationPickerViewModel.LAUSANNE, picked?.coordinates)
  }

  @Test
  fun route_allowInSettingsOpensThisAppsSettings() {
    showRoute()

    node(LocationPickerTags.BANNER_ACTION).performClick()

    val intent = shadowOf(composeTestRule.activity.application).nextStartedActivity
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
    assertEquals(composeTestRule.activity.packageName, intent.data?.schemeSpecificPart)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test
  fun route_comingBackWithThePermissionShowsTheLocationButton() {
    showRoute()
    node(LocationPickerTags.MY_LOCATION).assertDoesNotExist()

    // Granted in Settings; the app comes back to the foreground.
    location.permissionGranted = true
    composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
    composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

    node(LocationPickerTags.MY_LOCATION).assertIsDisplayed()
    node(LocationPickerTags.HINT).assertIsDisplayed()
  }

  @Test
  fun route_leavingStopsListeningToTheLifecycle() {
    showRoute()
    composeTestRule.runOnIdle { shown.value = false }
    composeTestRule.waitForIdle()
    node(LocationPickerTags.SCREEN).assertDoesNotExist()
    location.permissionGranted = true

    composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
    composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

    // The removed picker no longer reads the permission.
    assertFalse(viewModel.uiState.value.hasLocationAccess)
  }

  private companion object {
    val LEARNING_CENTER =
        Place(
            "Rolex Learning Center",
            "Route Cantonale, 1015 Ecublens",
            Coordinates(46.5184, 6.5683),
        )
    val NO_ADDRESS = Place("Esplanade", "", Coordinates(46.5208, 6.6267))
    val SETTLE_MILLIS = LocationPickerViewModel.SETTLE_DELAY.inWholeMilliseconds + 100
  }
}
