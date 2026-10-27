// Contributors: Claude (wrote these tests, #128; additional tests from a test audit and a
// review, #128).
package com.polysocial.ui.map

import com.polysocial.model.association.AssociationResult
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import com.polysocial.ui.event.CLUB_NAME
import com.polysocial.ui.event.CLUB_UID
import com.polysocial.ui.event.NamesFixture
import com.polysocial.ui.event.associationEvent
import com.polysocial.ui.event.club
import com.polysocial.ui.event.studentEvent
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The map reads the name of each association with an event on it, once (#128). */
class MapAssociationNamesTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val result = MutableSharedFlow<MapEventResult>(replay = 1)
  private val party = associationEvent(validEvent(title = "Party")).copy(id = "party")
  private val concert = associationEvent(validEvent(title = "Concert")).copy(id = "concert")
  private val study = studentEvent(validEvent(title = "Study")).copy(id = "study")

  private fun viewModel(fixture: NamesFixture) =
      MapViewModel(MapEventSource { result }, FakeLocationService(), MAP_TEST_CLOCK, fixture.names)

  private fun runPending() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  @Test
  fun theAssociationsOnTheMap_haveTheirNames_readOncePerAssociation() {
    val fixture = NamesFixture()
    val vm = viewModel(fixture)
    result.tryEmit(MapEventResult.Events(listOf(party, concert, study)))
    runPending()

    vm.selectEvent("party")

    assertEquals(mapOf(CLUB_UID to CLUB_NAME), vm.uiState.value.associationNames)
    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
  }

  @Test
  fun onlyStudentsEvents_readNoAssociation() {
    val fixture = NamesFixture()
    val vm = viewModel(fixture)
    result.tryEmit(MapEventResult.Events(listOf(study)))
    runPending()

    assertEquals(emptyMap<String, String>(), vm.uiState.value.associationNames)
    assertTrue(fixture.associations.requestedUids.isEmpty())
  }

  @Test
  fun aNameThatCantBeRead_isLeftOut_andIsNotReadOnEveryUpdate() {
    val fixture = NamesFixture(AssociationResult.NetworkError)
    val vm = viewModel(fixture)
    result.tryEmit(MapEventResult.Events(listOf(party)))
    runPending()
    result.tryEmit(MapEventResult.Events(listOf(party, concert)))
    runPending()

    assertEquals(emptyMap<String, String>(), vm.uiState.value.associationNames)
    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
    assertEquals(listOf("party", "concert"), vm.uiState.value.events.map { it.id })
  }

  @Test
  fun openingThePreview_ofAnUnknownName_readsItAgain_andAKnownNameIsNot() {
    // The map was opened offline: the name couldn't be read.
    val fixture = NamesFixture(AssociationResult.NetworkError)
    val vm = viewModel(fixture)
    result.tryEmit(MapEventResult.Events(listOf(party)))
    runPending()

    // Back online, opening the preview tries again.
    fixture.associations.getAssociationResult = AssociationResult.Found(club())
    vm.selectEvent("party")
    runPending()
    assertEquals(mapOf(CLUB_UID to CLUB_NAME), vm.uiState.value.associationNames)

    // Now known: opening it again, or a student's event, reads nothing.
    vm.closePreview()
    vm.selectEvent("party")
    runPending()
    assertEquals(listOf(CLUB_UID, CLUB_UID), fixture.associations.requestedUids)
  }

  @Test
  fun openingAStudentsPreview_readsNoAssociation() {
    val fixture = NamesFixture()
    val vm = viewModel(fixture)
    result.tryEmit(MapEventResult.Events(listOf(study)))
    runPending()

    vm.selectEvent("study")
    runPending()

    assertTrue(fixture.associations.requestedUids.isEmpty())
  }

  @Test
  fun theNames_stayWhenThePreviewOpensAndCloses() {
    val fixture = NamesFixture()
    val vm = viewModel(fixture)
    result.tryEmit(MapEventResult.Events(listOf(party)))
    runPending()

    vm.selectEvent("party")
    vm.closePreview()
    vm.selectEvent("party")

    assertEquals(mapOf(CLUB_UID to CLUB_NAME), vm.uiState.value.associationNames)
  }

  @Test
  fun aPrivateAssociationEvent_isNotOnTheMap_andItsAssociationIsNotRead() {
    val fixture = NamesFixture()
    val vm = viewModel(fixture)
    result.tryEmit(MapEventResult.Events(listOf(party.copy(isPrivate = true), study)))
    runPending()

    assertEquals(listOf("study"), vm.uiState.value.events.map { it.id })
    assertTrue(fixture.associations.requestedUids.isEmpty())
    assertEquals(emptyMap<String, String>(), vm.uiState.value.associationNames)
  }

  @Test
  fun theNames_stayThroughAnErrorAndRetry_withoutBeingReadAgain() {
    val fixture = NamesFixture()
    val vm = viewModel(fixture)
    result.tryEmit(MapEventResult.Events(listOf(party)))
    runPending()

    result.tryEmit(MapEventResult.Error)
    runPending()
    assertEquals(MapContentStatus.ERROR, vm.uiState.value.status)
    assertEquals(mapOf(CLUB_UID to CLUB_NAME), vm.uiState.value.associationNames)

    result.tryEmit(MapEventResult.Events(listOf(party, concert)))
    vm.retry()
    runPending()

    assertEquals(MapContentStatus.READY, vm.uiState.value.status)
    assertEquals(mapOf(CLUB_UID to CLUB_NAME), vm.uiState.value.associationNames)
    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
  }
}
