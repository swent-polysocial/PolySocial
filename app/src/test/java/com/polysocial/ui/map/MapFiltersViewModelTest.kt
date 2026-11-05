// Contributors: Claude (the Map tab's search and filters on the loaded events, #116; review fixes).
package com.polysocial.ui.map

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.location.LocationResult
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import com.polysocial.ui.event.list.DateChoice
import com.polysocial.ui.event.list.EventSelection
import com.polysocial.ui.event.list.EventsLocationState
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class MapFiltersViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()
  private val result = MutableSharedFlow<MapEventResult>(replay = 1)
  private var sourceCalls = 0
  private val location = FakeLocationService(permissionGranted = true)
  private val savedState = SavedStateHandle()

  private val study = validEvent(title = "Study session").copy(id = "study")
  private val party =
      validEvent(title = "Balélec warm-up")
          .copy(id = "party", category = EventCategory.PARTY, location = FAR)
  // Starts in three days, so neither "Today" nor the first days keep it.
  private val sports =
      validEvent(title = "Climbing", startTime = TEST_NOW.plusSeconds(3 * 24 * 3600))
          .copy(id = "sports", category = EventCategory.SPORTS)

  private fun model(state: SavedStateHandle = savedState) =
      MapViewModel(
          MapEventSource {
            sourceCalls++
            result
          },
          location,
          MAP_TEST_CLOCK,
          FakeAssociationNames(),
          state,
      )

  private fun settle() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  private fun loaded(vm: MapViewModel): MapViewModel {
    result.tryEmit(MapEventResult.Events(listOf(study, party, sports)))
    settle()
    return vm
  }

  private fun ids(vm: MapViewModel) = vm.uiState.value.events.map { it.id }

  @Test
  fun aCategoryKeepsOnlyItsMarkersWithoutANewRequest() {
    val vm = loaded(model())
    vm.toggleCategory(EventCategory.PARTY)
    assertEquals(listOf("party"), ids(vm))
    vm.toggleCategory(EventCategory.SPORTS)
    assertEquals(listOf("party", "sports"), ids(vm))
    vm.toggleCategory(EventCategory.PARTY)
    assertEquals(listOf("sports"), ids(vm))
    assertEquals(MapContentStatus.READY, vm.uiState.value.status)
    assertEquals(1, sourceCalls)
  }

  @Test
  fun theSearchLooksAtTitlesIgnoringAccents() {
    val vm = loaded(model())
    vm.setSearch("balelec")
    assertEquals(listOf("party"), ids(vm))
  }

  @Test
  fun aDateKeepsTheEventsOfThatDay() {
    val vm = loaded(model())
    vm.selectDate(DateChoice.Day(sports.startTime.atZone(MAP_TIME_ZONE).toLocalDate()))
    assertEquals(listOf("sports"), ids(vm))
  }

  @Test
  fun noMatchThenClearingShowsEveryMarkerAgain() {
    val vm = loaded(model())
    vm.setSearch("nothing like this")
    assertEquals(MapContentStatus.NO_MATCH, vm.uiState.value.status)
    assertEquals(emptyList<String>(), ids(vm))

    vm.clearSearchAndFilters()
    assertEquals(MapContentStatus.READY, vm.uiState.value.status)
    assertEquals(listOf("study", "party", "sports"), ids(vm))
    assertEquals(EventSelection(), vm.uiState.value.selection)
  }

  @Test
  fun clearFiltersKeepsTheSearch() {
    val vm = loaded(model())
    vm.setSearch("study")
    vm.toggleCategory(EventCategory.PARTY)
    vm.clearFilters()
    assertEquals(EventSelection(search = "study"), vm.uiState.value.selection)
    assertEquals(listOf("study"), ids(vm))
  }

  @Test
  fun noEventsAtAllStaysTheEmptyState() {
    val vm = model()
    vm.toggleCategory(EventCategory.PARTY)
    result.tryEmit(MapEventResult.Events(emptyList()))
    settle()
    assertEquals(MapContentStatus.EMPTY, vm.uiState.value.status)
  }

  @Test
  fun aFilterChosenWhileLoadingAppliesOnceTheEventsArrive() {
    val vm = model()
    vm.toggleCategory(EventCategory.SPORTS)
    assertEquals(MapContentStatus.LOADING, vm.uiState.value.status)
    loaded(vm)
    assertEquals(listOf("sports"), ids(vm))
  }

  @Test
  fun anErrorKeepsTheFilteredCachedMarkers() {
    val vm = loaded(model())
    vm.toggleCategory(EventCategory.STUDY)
    result.tryEmit(MapEventResult.Error)
    settle()
    vm.toggleCategory(EventCategory.PARTY)
    assertEquals(MapContentStatus.ERROR, vm.uiState.value.status)
    assertEquals(listOf("study", "party"), ids(vm))
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun newEventsAfterAnErrorNeverShowReadyWithTheCachedMarkers() {
    val vm = loaded(model())
    result.tryEmit(MapEventResult.Error)
    settle()
    // Records every state, not only the latest one, as an unconflated collector would see them.
    val seen = mutableListOf<MapUiState>()
    val collector =
        CoroutineScope(UnconfinedTestDispatcher(mainDispatcherRule.dispatcher.scheduler)).launch {
          vm.uiState.collect { seen += it }
        }
    result.tryEmit(MapEventResult.Events(listOf(party)))
    settle()
    collector.cancel()

    assertEquals(MapContentStatus.ERROR, seen.first().status)
    assertEquals(
        listOf(MapContentStatus.READY to listOf("party")),
        seen.drop(1).map { state -> state.status to state.events.map { it.id } },
    )
  }

  @Test
  fun thePreviewClosesWhenItsEventIsFilteredOut() {
    val vm = loaded(model())
    vm.selectEvent("study")
    vm.toggleCategory(EventCategory.PARTY)
    assertNull(vm.uiState.value.selectedEvent)
  }

  @Test
  fun theSelectionSurvivesRecreationAndStaysApartFromTheEventsTab() {
    val vm = loaded(model())
    vm.setSearch("climb")
    vm.toggleCategory(EventCategory.SPORTS)
    vm.selectDate(DateChoice.ThisWeek)

    val recreated = loaded(model(savedState))
    assertEquals(
        EventSelection("climb", setOf(EventCategory.SPORTS), DateChoice.ThisWeek),
        recreated.uiState.value.selection,
    )
    assertEquals(listOf("sports"), ids(recreated))
    assertNull(savedState.get<String>("events_search"))
  }

  @Test
  fun aSavedValueItDoesNotKnowIsDropped() {
    val state =
        SavedStateHandle(
            mapOf(
                "map_categories" to arrayListOf("PARTY", "UNKNOWN"),
                "map_date" to "not a date",
                "map_distance" to 123,
            )
        )
    val vm = model(state)
    assertEquals(
        EventSelection(categories = setOf(EventCategory.PARTY)),
        vm.uiState.value.selection,
    )
  }

  @Test
  fun aDistanceNeedsALocationFix() {
    location.result = LocationResult.Unavailable
    val vm = loaded(model())
    vm.onMapEntered()
    settle()
    assertEquals(EventsLocationState.Off, vm.uiState.value.filterLocation)
    vm.selectDistance(500)
    assertNull(vm.uiState.value.selection.maxDistanceMeters)
  }

  @Test
  fun aDistanceKeepsTheNearbyEventsOnly() {
    location.result = LocationResult.Available(NEAR)
    val vm = loaded(model())
    vm.onMapEntered()
    settle()
    assertEquals(EventsLocationState.Available(NEAR), vm.uiState.value.filterLocation)
    vm.selectDistance(500)
    assertEquals(listOf("study", "sports"), ids(vm))
  }

  @Test
  fun aRevokedPermissionClearsTheDistance() {
    location.result = LocationResult.Available(NEAR)
    val vm = loaded(model())
    vm.onMapEntered()
    settle()
    vm.selectDistance(500)
    vm.onMapInactive()

    location.permissionGranted = false
    vm.onMapEntered()
    assertNull(vm.uiState.value.selection.maxDistanceMeters)
    assertNull(savedState.get<Int>("map_distance"))
    assertEquals(listOf("study", "party", "sports"), ids(vm))
  }

  @Test
  fun comingBackWithADistanceReadsTheLocationAgain() {
    location.result = LocationResult.Available(NEAR)
    val vm = loaded(model())
    vm.onMapEntered()
    settle()
    vm.selectDistance(500)
    vm.onMapInactive()
    assertEquals(EventsLocationState.Off, vm.uiState.value.filterLocation)

    vm.onMapEntered()
    settle()
    assertEquals(2, location.requestCount)
    assertEquals(listOf("study", "sports"), ids(vm))
  }

  @Test
  fun withoutADistanceComingBackDoesNotReadTheLocation() {
    location.result = LocationResult.Available(NEAR)
    val vm = loaded(model())
    vm.onMapEntered()
    settle()
    vm.onMapInactive()
    vm.onMapEntered()
    settle()
    assertEquals(1, location.requestCount)
  }

  private companion object {
    /** Where the test events are (see `validEvent`). */
    val NEAR = Coordinates(latitude = 46.5191, longitude = 6.5668)
    /** About 5 km from [NEAR], in Lausanne's centre. */
    val FAR = Coordinates(latitude = 46.5197, longitude = 6.6323)
  }
}
