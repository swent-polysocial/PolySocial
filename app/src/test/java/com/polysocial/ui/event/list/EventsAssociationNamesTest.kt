// Contributors: Claude (wrote these tests, #128; additional tests from a test audit and a
// review, #128).
package com.polysocial.ui.event.list

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.ui.event.CLUB_NAME
import com.polysocial.ui.event.CLUB_UID
import com.polysocial.ui.event.NamesFixture
import com.polysocial.ui.event.associationEvent
import com.polysocial.ui.event.club
import com.polysocial.ui.event.studentEvent
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Events tab reads the name of each association whose events it lists, once, through the shared
 * association names (#128).
 */
class EventsAssociationNamesTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val repository = FakeEventRepository()
  private val clock = Clock.fixed(TEST_NOW, ZoneId.of("Europe/Zurich"))
  private val online =
      object : NetworkMonitor {
        override fun isOnline() = true
      }

  private val party =
      associationEvent(validEvent(title = "Party", startTime = TEST_NOW.plusSeconds(3600)))
          .copy(id = "party")
  private val concert =
      associationEvent(validEvent(title = "Concert", startTime = TEST_NOW.plus(Duration.ofDays(1))))
          .copy(id = "concert")
  private val studySession = studentEvent(validEvent(title = "Study session")).copy(id = "study")

  private fun viewModel(fixture: NamesFixture) =
      EventsViewModel(
          repository,
          online,
          FakeLocationService(permissionGranted = true),
          clock,
          SavedStateHandle(),
          fixture.names,
      )

  private fun runPending() = mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

  @Test
  fun anAssociationsEvents_getItsName_readOnceForAllItsCards() {
    val fixture = NamesFixture()
    repository.seed(party, concert, studySession)
    val vm = viewModel(fixture)
    runPending()

    assertEquals(mapOf(CLUB_UID to CLUB_NAME), vm.uiState.value.associationNames)
    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
    assertTrue(vm.uiState.value.list is EventsListState.Events)
  }

  @Test
  fun aStudentsEvent_readsNoAssociation() {
    val fixture = NamesFixture()
    repository.seed(studySession)
    val vm = viewModel(fixture)
    runPending()

    assertEquals(emptyMap<String, String>(), vm.uiState.value.associationNames)
    assertTrue(fixture.associations.requestedUids.isEmpty())
  }

  @Test
  fun aNameThatCantBeRead_isLeftOut_andTheEventsStillShow() {
    for (failure in
        listOf(
            AssociationResult.NetworkError,
            AssociationResult.NotFound,
            AssociationResult.UnexpectedError,
        )) {
      val fixture = NamesFixture(failure)
      repository.seed(party)
      val vm = viewModel(fixture)
      runPending()

      assertEquals("After $failure", emptyMap<String, String>(), vm.uiState.value.associationNames)
      val list = vm.uiState.value.list as EventsListState.Events
      assertTrue(list.sections.flatMap { it.events }.any { it.id == "party" })
      repository.events.clear()
    }
  }

  @Test
  fun aNameThatCantBeRead_isNotReadAgainOnEveryChangeOfTheCachedList() {
    // Offline, the list changes from the cache: asking again would fail the same way.
    val fixture = NamesFixture(AssociationResult.NetworkError)
    repository.readFromCache = true
    repository.seed(party)
    val vm = viewModel(fixture)
    runPending()

    repository.seed(concert)
    runPending()
    vm.setSearch("Con")
    runPending()

    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
  }

  @Test
  fun anUnknownName_isReadAgainWhenFreshEventsArrive_andAKnownNameIsNot() {
    // Opened offline: the name can't be read and the events come from the cache.
    val fixture = NamesFixture(AssociationResult.NetworkError)
    repository.readFromCache = true
    repository.seed(party)
    val vm = viewModel(fixture)
    runPending()
    assertEquals(emptyMap<String, String>(), vm.uiState.value.associationNames)

    // Back online: the server's events bring a second try, which now succeeds.
    fixture.associations.getAssociationResult = AssociationResult.Found(club())
    repository.readFromCache = false
    repository.seed(concert)
    runPending()
    assertEquals(mapOf(CLUB_UID to CLUB_NAME), vm.uiState.value.associationNames)

    // Once known, later events from the server don't read it again.
    repository.seed(studySession)
    runPending()
    assertEquals(listOf(CLUB_UID, CLUB_UID), fixture.associations.requestedUids)
  }

  @Test
  fun theNames_stayWhenTheSearchAndFiltersChange() {
    val fixture = NamesFixture()
    repository.seed(party, concert)
    val vm = viewModel(fixture)
    runPending()

    vm.setSearch("Concert")
    vm.clearSearchAndFilters()
    runPending()

    assertEquals(mapOf(CLUB_UID to CLUB_NAME), vm.uiState.value.associationNames)
    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
  }

  @Test
  fun anAssociationThatAppearsLater_isReadThen() {
    val fixture = NamesFixture()
    repository.seed(studySession)
    val vm = viewModel(fixture)
    runPending()
    assertTrue(fixture.associations.requestedUids.isEmpty())

    repository.seed(party)
    runPending()

    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
    assertEquals(mapOf(CLUB_UID to CLUB_NAME), vm.uiState.value.associationNames)
  }

  @Test
  fun knownNames_stayThroughAnErrorAndRetry_withoutBeingReadAgain() {
    val fixture = NamesFixture()
    // Say the map read the name first.
    runBlocking { fixture.names.load(CLUB_UID) }
    repository.readFailure = true
    val vm = viewModel(fixture)
    runPending()
    assertEquals(EventsListState.Error, vm.uiState.value.list)
    assertEquals(mapOf(CLUB_UID to CLUB_NAME), vm.uiState.value.associationNames)

    repository.readFailure = false
    repository.seed(party)
    vm.retry()
    runPending()

    assertTrue(vm.uiState.value.list is EventsListState.Events)
    assertEquals(mapOf(CLUB_UID to CLUB_NAME), vm.uiState.value.associationNames)
    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
  }
}
