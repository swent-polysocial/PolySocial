// Contributors: Claude (wrote this test, #128).
package com.polysocial.ui.event

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.ui.event.detail.EventDetailUiState
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.event.list.EventsViewModel
import com.polysocial.ui.map.MapViewModel
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Events tab, the map and the detail share one set of association names (#128): an association
 * with events on every screen is read once in all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssociationNamesAcrossScreensTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun anAssociationShownOnEveryScreen_isReadOnce() = runTest {
    val fixture = NamesFixture()
    val clock = Clock.fixed(TEST_NOW, ZoneId.of("Europe/Zurich"))
    val online =
        object : NetworkMonitor {
          override fun isOnline() = true
        }
    val party = associationEvent(validEvent(title = "Party")).copy(id = "party")
    val events = FakeEventRepository().apply { seed(party) }

    val list =
        EventsViewModel(
            events,
            online,
            FakeLocationService(permissionGranted = true),
            clock,
            SavedStateHandle(),
            fixture.names,
        )
    val map =
        MapViewModel(
            MapEventSource { flowOf(MapEventResult.Events(listOf(party))) },
            FakeLocationService(),
            clock,
            fixture.names,
        )
    val detail =
        EventDetailViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to "party")),
            events,
            online,
            fixture.auth,
            clock,
            fixture.names,
        )
    var detailState: EventDetailUiState = EventDetailUiState.Loading
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      detail.uiState.collect { detailState = it }
    }
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    assertEquals(CLUB_NAME, list.uiState.value.associationNames[CLUB_UID])
    assertEquals(CLUB_NAME, map.uiState.value.associationNames[CLUB_UID])
    assertEquals(CLUB_NAME, (detailState as EventDetailUiState.Loaded).associationName)
    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
  }
}
