// Contributors: Claude (drafted the location picker ViewModel tests, #111).
package com.polysocial.ui.event.create

import com.polysocial.model.event.Coordinates
import com.polysocial.model.geocoding.FakeGeocodingService
import com.polysocial.model.geocoding.Place
import com.polysocial.model.geocoding.PlaceSearchResult
import com.polysocial.model.geocoding.ReverseGeocodeResult
import com.polysocial.utils.MainDispatcherRule
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationPickerViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val geocoding = FakeGeocodingService()
  private val viewModel = LocationPickerViewModel(geocoding)

  private fun state() = viewModel.uiState.value

  /** Runs the lookup started by the last pin move, which waits for the map to settle first. */
  private fun TestScope.settle() {
    advanceTimeBy(500.milliseconds)
    runCurrent()
  }

  // ---------- Search ----------

  @Test
  fun typing_sendsNoSearch() = runTest {
    viewModel.onQueryChange("R")
    viewModel.onQueryChange("Ro")
    viewModel.onQueryChange("Rolex")
    advanceUntilIdle()

    assertEquals("Rolex", state().query)
    assertEquals(PlaceSearchState.Idle, state().search)
    assertTrue(geocoding.searches.isEmpty())
  }

  @Test
  fun onSearch_showsSearchingThenTheResults() = runTest {
    val pending = CompletableDeferred<Unit>()
    geocoding.pendingSearch = pending
    geocoding.searchResult = PlaceSearchResult.Found(listOf(LEARNING_CENTER, GARE))
    viewModel.onQueryChange("lausanne")

    viewModel.onSearch()
    assertEquals(PlaceSearchState.Searching, state().search)
    runCurrent()
    assertEquals(PlaceSearchState.Searching, state().search)

    pending.complete(Unit)
    advanceUntilIdle()

    assertEquals(PlaceSearchState.Results(listOf(LEARNING_CENTER, GARE)), state().search)
    assertEquals(listOf("lausanne"), geocoding.searches)
  }

  @Test
  fun onSearch_withNoMatchShowsNoResults() = runTest {
    geocoding.searchResult = PlaceSearchResult.Found(emptyList())
    viewModel.onQueryChange("zzzz")

    viewModel.onSearch()
    advanceUntilIdle()

    assertEquals(PlaceSearchState.NoResults, state().search)
  }

  @Test
  fun onSearch_offlineShowsAnOfflineError() = runTest {
    geocoding.searchResult = PlaceSearchResult.NetworkError
    viewModel.onQueryChange("rolex")

    viewModel.onSearch()
    advanceUntilIdle()

    assertEquals(PlaceSearchState.Failed(LookupFailure.OFFLINE), state().search)
  }

  @Test
  fun onSearch_anUnexpectedFailureShowsAnUnexpectedError() = runTest {
    geocoding.searchResult = PlaceSearchResult.UnexpectedError
    viewModel.onQueryChange("rolex")

    viewModel.onSearch()
    advanceUntilIdle()

    assertEquals(PlaceSearchState.Failed(LookupFailure.UNEXPECTED), state().search)
  }

  @Test
  fun aFailedSearch_leavesThePinUsable() = runTest {
    viewModel.open(INITIAL)
    geocoding.searchResult = PlaceSearchResult.NetworkError
    viewModel.onQueryChange("rolex")
    viewModel.onSearch()
    advanceUntilIdle()

    viewModel.onPinMove(ELSEWHERE)
    geocoding.reverseResult = ReverseGeocodeResult.NetworkError
    settle()

    assertEquals(
        PickedLocation(ELSEWHERE, "Dropped pin", "", isNamed = false),
        viewModel.selection("Dropped pin"),
    )
  }

  @Test
  fun onSearch_withABlankQuerySendsNothing() = runTest {
    viewModel.onSearch()
    viewModel.onQueryChange("   ")
    viewModel.onSearch()
    advanceUntilIdle()

    assertEquals(PlaceSearchState.Idle, state().search)
    assertTrue(geocoding.searches.isEmpty())
  }

  @Test
  fun onSearch_whileSearchingSendsNothingMore() = runTest {
    val pending = CompletableDeferred<Unit>()
    geocoding.pendingSearch = pending
    viewModel.onQueryChange("rolex")
    viewModel.onSearch()
    runCurrent()

    viewModel.onQueryChange("gare")
    viewModel.onSearch()
    runCurrent()
    pending.complete(Unit)
    advanceUntilIdle()

    assertEquals(listOf("rolex"), geocoding.searches)
  }

  @Test
  fun onSearch_sendsTheTrimmedQuery() = runTest {
    viewModel.onQueryChange("  Rolex Learning Center \n")

    viewModel.onSearch()
    advanceUntilIdle()

    assertEquals(listOf("Rolex Learning Center"), geocoding.searches)
  }

  @Test
  fun onSearch_canSearchAgainOnceTheFirstSearchIsDone() = runTest {
    viewModel.onQueryChange("rolex")
    viewModel.onSearch()
    advanceUntilIdle()
    viewModel.onQueryChange("gare")
    geocoding.searchResult = PlaceSearchResult.Found(listOf(GARE))

    viewModel.onSearch()
    advanceUntilIdle()

    assertEquals(listOf("rolex", "gare"), geocoding.searches)
    assertEquals(PlaceSearchState.Results(listOf(GARE)), state().search)
  }

  @Test
  fun onSearchDismiss_closesTheResults() = runTest {
    geocoding.searchResult = PlaceSearchResult.Found(listOf(LEARNING_CENTER))
    viewModel.onQueryChange("rolex")
    viewModel.onSearch()
    advanceUntilIdle()

    viewModel.onSearchDismiss()

    assertEquals(PlaceSearchState.Idle, state().search)
  }

  @Test
  fun onSearchDismiss_cancelsARunningSearchSoItsLateResultNeverShows() = runTest {
    val pending = CompletableDeferred<Unit>()
    geocoding.pendingSearch = pending
    geocoding.searchResult = PlaceSearchResult.Found(listOf(LEARNING_CENTER))
    viewModel.onQueryChange("rolex")
    viewModel.onSearch()
    runCurrent()

    viewModel.onSearchDismiss()
    assertEquals(PlaceSearchState.Idle, state().search)
    pending.complete(Unit)
    advanceUntilIdle()

    assertEquals(PlaceSearchState.Idle, state().search)
  }

  // ---------- Choosing a result ----------

  @Test
  fun onResultSelect_movesThePinThereWithItsNameAndClosesTheResults() = runTest {
    viewModel.open(INITIAL)
    geocoding.searchResult = PlaceSearchResult.Found(listOf(LEARNING_CENTER, GARE))
    viewModel.onQueryChange("lausanne")
    viewModel.onSearch()
    advanceUntilIdle()

    viewModel.onResultSelect(GARE)
    advanceUntilIdle()

    assertEquals(GARE.coordinates, state().pin)
    assertEquals(PinPlaceState.Found(GARE), state().pinPlace)
    assertEquals(PlaceSearchState.Idle, state().search)
    assertTrue(geocoding.reverses.isEmpty())
  }

  @Test
  fun theMapSettlingOnAChosenResult_keepsItsName() = runTest {
    viewModel.open(INITIAL)
    viewModel.onResultSelect(GARE)

    // The map's center after animating to the result is a hair off its coordinates.
    viewModel.onPinMove(Coordinates(GARE.coordinates.latitude + 5e-6, GARE.coordinates.longitude))
    advanceUntilIdle()

    assertEquals(PinPlaceState.Found(GARE), state().pinPlace)
    assertEquals(GARE.coordinates, state().pin)
    assertTrue(geocoding.reverses.isEmpty())
  }

  @Test
  fun onResultSelect_cancelsAPendingLookupSoTheResultsNameStays() = runTest {
    geocoding.reverseResult = ReverseGeocodeResult.Found(OTHER)
    viewModel.open(INITIAL)
    viewModel.onPinMove(ELSEWHERE)
    advanceTimeBy(100.milliseconds)

    viewModel.onResultSelect(LEARNING_CENTER)
    advanceUntilIdle()

    assertEquals(PinPlaceState.Found(LEARNING_CENTER), state().pinPlace)
    assertEquals(LEARNING_CENTER.coordinates, state().pin)
    assertTrue(geocoding.reverses.isEmpty())
  }

  @Test
  fun onResultSelect_cancelsALookupAlreadySentSoItsAnswerIsIgnored() = runTest {
    val pending = CompletableDeferred<Unit>()
    geocoding.pendingReverse = pending
    geocoding.reverseResult = ReverseGeocodeResult.Found(OTHER)
    viewModel.open(INITIAL)
    viewModel.onPinMove(ELSEWHERE)
    settle()
    assertEquals(listOf(ELSEWHERE), geocoding.reverses)

    viewModel.onResultSelect(LEARNING_CENTER)
    pending.complete(Unit)
    advanceUntilIdle()

    assertEquals(PinPlaceState.Found(LEARNING_CENTER), state().pinPlace)
  }

  @Test
  fun onResultSelect_cancelsARunningSearch() = runTest {
    val pending = CompletableDeferred<Unit>()
    geocoding.pendingSearch = pending
    geocoding.searchResult = PlaceSearchResult.Found(listOf(GARE))
    viewModel.onQueryChange("gare")
    viewModel.onSearch()
    runCurrent()

    viewModel.onResultSelect(LEARNING_CENTER)
    pending.complete(Unit)
    advanceUntilIdle()

    assertEquals(PlaceSearchState.Idle, state().search)
  }

  // ---------- Moving the pin ----------

  @Test
  fun onPinMove_movesThePinAndShowsResolvingAtOnce() = runTest {
    viewModel.open(INITIAL)

    viewModel.onPinMove(ELSEWHERE)

    assertEquals(ELSEWHERE, state().pin)
    assertEquals(PinPlaceState.Resolving, state().pinPlace)
  }

  @Test
  fun onPinMove_looksUpThePinOnceOnlyAfterTheMapSettled() = runTest {
    viewModel.open(INITIAL)
    viewModel.onPinMove(ELSEWHERE)

    advanceTimeBy(499.milliseconds)
    runCurrent()
    assertTrue(geocoding.reverses.isEmpty())

    advanceTimeBy(1.milliseconds)
    runCurrent()
    assertEquals(listOf(ELSEWHERE), geocoding.reverses)

    advanceUntilIdle()
    assertEquals(listOf(ELSEWHERE), geocoding.reverses)
  }

  @Test
  fun quickSuccessiveMoves_sendOneLookupForTheLastPosition() = runTest {
    val second = Coordinates(46.53, 6.61)
    val third = Coordinates(46.54, 6.62)
    viewModel.open(INITIAL)

    viewModel.onPinMove(ELSEWHERE)
    advanceTimeBy(400.milliseconds)
    viewModel.onPinMove(second)
    advanceTimeBy(499.milliseconds)
    viewModel.onPinMove(third)
    advanceUntilIdle()

    assertEquals(listOf(third), geocoding.reverses)
    assertEquals(third, state().pin)
  }

  @Test
  fun aFoundAddress_namesThePin() = runTest {
    geocoding.reverseResult = ReverseGeocodeResult.Found(OTHER)
    viewModel.open(INITIAL)

    viewModel.onPinMove(ELSEWHERE)
    settle()

    assertEquals(PinPlaceState.Found(OTHER), state().pinPlace)
    // The pin stays where the student put it, not on the place the server returned.
    assertEquals(ELSEWHERE, state().pin)
  }

  @Test
  fun noAddressThere_leavesThePinUnnamedWithoutAnError() = runTest {
    geocoding.reverseResult = ReverseGeocodeResult.NotFound
    viewModel.open(INITIAL)

    viewModel.onPinMove(ELSEWHERE)
    settle()

    assertEquals(PinPlaceState.Unnamed(null), state().pinPlace)
  }

  @Test
  fun anOfflineLookup_leavesThePinUnnamedOffline() = runTest {
    geocoding.reverseResult = ReverseGeocodeResult.NetworkError
    viewModel.open(INITIAL)

    viewModel.onPinMove(ELSEWHERE)
    settle()

    assertEquals(PinPlaceState.Unnamed(LookupFailure.OFFLINE), state().pinPlace)
  }

  @Test
  fun anUnexpectedLookupFailure_leavesThePinUnnamedWithThatError() = runTest {
    geocoding.reverseResult = ReverseGeocodeResult.UnexpectedError
    viewModel.open(INITIAL)

    viewModel.onPinMove(ELSEWHERE)
    settle()

    assertEquals(PinPlaceState.Unnamed(LookupFailure.UNEXPECTED), state().pinPlace)
  }

  @Test
  fun aMoveWithinAboutAMetre_isIgnored() = runTest {
    viewModel.open(INITIAL)
    val pin = INITIAL.coordinates

    viewModel.onPinMove(Coordinates(pin.latitude + 9e-6, pin.longitude - 9e-6))
    viewModel.onPinMove(pin)
    advanceUntilIdle()

    assertEquals(pin, state().pin)
    assertEquals(
        PinPlaceState.Found(Place(INITIAL.name, INITIAL.address, pin)),
        state().pinPlace,
    )
    assertTrue(geocoding.reverses.isEmpty())
  }

  @Test
  fun aMoveOfAFewMetres_isLookedUpEvenAlongOneAxisOnly() = runTest {
    viewModel.open(INITIAL)
    val pin = INITIAL.coordinates
    val east = Coordinates(pin.latitude, pin.longitude + 3e-5)
    val north = Coordinates(pin.latitude + 3e-5, pin.longitude + 3e-5)

    viewModel.onPinMove(east)
    settle()
    viewModel.onPinMove(north)
    settle()

    assertEquals(listOf(east, north), geocoding.reverses)
  }

  // ---------- Opening ----------

  @Test
  fun openingWithoutAPlace_putsThePinOnLausanneAndLooksItUp() = runTest {
    geocoding.reverseResult = ReverseGeocodeResult.Found(OTHER)

    viewModel.open(null)
    assertEquals(LocationPickerViewModel.LAUSANNE, state().pin)
    assertEquals(PinPlaceState.Resolving, state().pinPlace)
    advanceUntilIdle()

    assertEquals(listOf(LocationPickerViewModel.LAUSANNE), geocoding.reverses)
    assertEquals(PinPlaceState.Found(OTHER), state().pinPlace)
  }

  @Test
  fun openingOnTheFormsPlace_showsItWithoutALookup() = runTest {
    viewModel.open(INITIAL)
    advanceUntilIdle()

    assertEquals(INITIAL.coordinates, state().pin)
    assertEquals(
        PinPlaceState.Found(Place(INITIAL.name, INITIAL.address, INITIAL.coordinates)),
        state().pinPlace,
    )
    assertTrue(geocoding.reverses.isEmpty())
    assertEquals(INITIAL, viewModel.selection("Dropped pin"))
  }

  @Test
  fun openingOnAPlaceConfirmedWithoutAName_looksItUpAgain() = runTest {
    // Confirmed offline, so the form holds only the placeholder name: reopening online must look
    // the pin up instead of showing the placeholder as a found place.
    val unnamed = PickedLocation(ELSEWHERE, "Dropped pin", "", isNamed = false)
    geocoding.reverseResult = ReverseGeocodeResult.Found(OTHER)

    viewModel.open(unnamed)
    assertEquals(ELSEWHERE, state().pin)
    assertEquals(PinPlaceState.Resolving, state().pinPlace)
    advanceUntilIdle()

    assertEquals(listOf(ELSEWHERE), geocoding.reverses)
    assertEquals(PinPlaceState.Found(OTHER), state().pinPlace)
    assertEquals(
        PickedLocation(ELSEWHERE, OTHER.name, OTHER.address),
        viewModel.selection("Dropped pin"),
    )
  }

  @Test
  fun aSecondOpen_isIgnored() = runTest {
    viewModel.open(INITIAL)
    viewModel.open(null)
    advanceUntilIdle()

    assertEquals(INITIAL.coordinates, state().pin)
    assertTrue(geocoding.reverses.isEmpty())
  }

  @Test
  fun aSecondOpen_afterOpeningOnLausanneIsIgnoredToo() = runTest {
    viewModel.open(null)
    advanceUntilIdle()
    viewModel.open(INITIAL)
    viewModel.open(null)
    advanceUntilIdle()

    assertEquals(LocationPickerViewModel.LAUSANNE, state().pin)
    assertEquals(1, geocoding.reverses.size)
  }

  // ---------- Confirming ----------

  @Test
  fun selection_returnsThePinWithItsFoundNameAndAddress() = runTest {
    geocoding.reverseResult = ReverseGeocodeResult.Found(OTHER)
    viewModel.open(INITIAL)
    viewModel.onPinMove(ELSEWHERE)
    settle()

    assertEquals(
        PickedLocation(ELSEWHERE, OTHER.name, OTHER.address),
        viewModel.selection("Dropped pin"),
    )
  }

  @Test
  fun selection_whileResolvingReturnsThePinWithTheUnnamedName() = runTest {
    viewModel.open(INITIAL)
    viewModel.onPinMove(ELSEWHERE)

    assertEquals(
        PickedLocation(ELSEWHERE, "Dropped pin", "", isNamed = false),
        viewModel.selection("Dropped pin"),
    )
  }

  @Test
  fun selection_offlineReturnsThePinWithTheUnnamedName() = runTest {
    geocoding.reverseResult = ReverseGeocodeResult.NetworkError
    viewModel.open(null)
    advanceUntilIdle()

    assertEquals(PinPlaceState.Unnamed(LookupFailure.OFFLINE), state().pinPlace)
    assertEquals(
        PickedLocation(LocationPickerViewModel.LAUSANNE, "Dropped pin", "", isNamed = false),
        viewModel.selection("Dropped pin"),
    )
  }

  @Test
  fun selection_withNoAddressThereReturnsThePinWithTheUnnamedName() = runTest {
    geocoding.reverseResult = ReverseGeocodeResult.NotFound
    viewModel.open(INITIAL)
    viewModel.onPinMove(ELSEWHERE)
    settle()

    assertEquals(PickedLocation(ELSEWHERE, "Pin", "", isNamed = false), viewModel.selection("Pin"))
  }

  @Test
  fun selection_afterChoosingAResultReturnsTheResult() = runTest {
    viewModel.open(null)
    viewModel.onResultSelect(LEARNING_CENTER)
    advanceUntilIdle()

    assertEquals(
        PickedLocation(LEARNING_CENTER.coordinates, LEARNING_CENTER.name, LEARNING_CENTER.address),
        viewModel.selection("Dropped pin"),
    )
  }

  private companion object {
    val LEARNING_CENTER =
        Place(
            "Rolex Learning Center",
            "Place Maurice-Cosandey, 1015 Lausanne",
            Coordinates(46.5183517, 6.5676152),
        )
    val GARE =
        Place("Gare de Lausanne", "Place de la Gare 9, 1003 Lausanne", Coordinates(46.5167, 6.6291))
    val OTHER =
        Place(
            "Esplanade de Montbenon",
            "Allée Ernest-Ansermet, 1003 Lausanne",
            Coordinates(46.5208, 6.6267),
        )
    val ELSEWHERE = Coordinates(46.5100, 6.6000)
    val INITIAL =
        PickedLocation(
            Coordinates(46.5220, 6.5660),
            "SwissTech Convention Center",
            "Route Louis-Favre, 1024 Ecublens",
        )
  }
}
