// Contributors: Claude (wrote these tests, #128; additional tests from a test audit and a
// review, #128).
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.ui.event.CLUB_NAME
import com.polysocial.ui.event.CLUB_UID
import com.polysocial.ui.event.NamesFixture
import com.polysocial.ui.event.STUDENT_UID
import com.polysocial.ui.event.associationEvent
import com.polysocial.ui.event.studentEvent
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The event detail reads the name of an association's event once (#128). */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailAssociationNameTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneId.of("Europe/Zurich"))
  private val repository = FakeEventRepository()
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = true
      }
  private val party = associationEvent(validEvent(title = "Party")).copy(id = "party")
  private val study = studentEvent(validEvent(title = "Study")).copy(id = "study")

  private fun viewModel(id: String, fixture: NamesFixture) =
      EventDetailViewModel(
          SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to id)),
          repository,
          network,
          fixture.auth,
          clock,
          fixture.names,
      )

  /** Collects [vm]'s state like the screen does, and returns every state it showed. */
  private fun TestScope.observe(vm: EventDetailViewModel) =
      mutableListOf<EventDetailUiState>().also { states ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          vm.uiState.collect { states += it }
        }
        runCurrent()
      }

  private fun List<EventDetailUiState>.lastLoaded() = last() as EventDetailUiState.Loaded

  @Test
  fun anAssociationsEvent_getsItsName() = runTest {
    val fixture = NamesFixture()
    repository.seed(party)

    val states = observe(viewModel("party", fixture))

    assertEquals(CLUB_NAME, states.lastLoaded().associationName)
    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
  }

  @Test
  fun aStudentsEvent_hasNoName_andReadsNoAssociation() = runTest {
    val fixture = NamesFixture()
    repository.seed(study)

    val states = observe(viewModel("study", fixture))

    assertNull(states.lastLoaded().associationName)
    assertTrue(fixture.associations.requestedUids.isEmpty())
  }

  @Test
  fun anEventWithoutTheBadge_getsNoName_evenWhenItsCreatorsNameIsKnown() = runTest {
    // Say an association whose verification was revoked: its older events have no badge, but its
    // name may already be known from another screen.
    val fixture = NamesFixture()
    fixture.names.load(STUDENT_UID)
    repository.seed(study)

    val states = observe(viewModel("study", fixture))

    assertNull(states.lastLoaded().associationName)
  }

  @Test
  fun aNameThatCantBeRead_isNotAskedForAgainOnEveryChangeOfTheEvent() = runTest {
    val fixture = NamesFixture(AssociationResult.NetworkError)
    repository.seed(party)
    val states = observe(viewModel("party", fixture))

    repository.remove("party")
    repository.seed(party.copy(title = "Party, moved"))
    runCurrent()

    assertEquals("Party, moved", states.lastLoaded().details.event.title)
    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
  }

  @Test
  fun aNameThatCantBeRead_leavesTheEventWithoutAName() = runTest {
    for (failure in
        listOf(
            AssociationResult.NetworkError,
            AssociationResult.NotFound,
            AssociationResult.UnexpectedError,
        )) {
      val fixture = NamesFixture(failure)
      repository.seed(party)

      val states = observe(viewModel("party", fixture))

      val loaded = states.lastLoaded()
      assertNull("After $failure", loaded.associationName)
      // The badge comes from the event itself, so it stays without the name.
      assertTrue(loaded.details.event.isAssociationEvent)
      assertEquals(party.title, loaded.details.event.title)
      repository.events.clear()
    }
  }

  @Test
  fun theEventShows_beforeItsNameArrives() = runTest {
    val fixture = NamesFixture()
    fixture.associations.gate = CompletableDeferred()
    repository.seed(party)

    val states = observe(viewModel("party", fixture))
    assertNull(states.lastLoaded().associationName)

    fixture.associations.gate!!.complete(Unit)
    runCurrent()

    assertEquals(CLUB_NAME, states.lastLoaded().associationName)
  }

  @Test
  fun everyChangeOfTheEvent_andRetry_keepTheName_withoutReadingItAgain() = runTest {
    val fixture = NamesFixture()
    repository.seed(party)
    val vm = viewModel("party", fixture)
    val states = observe(vm)

    repository.remove("party")
    repository.seed(party.copy(title = "Party, moved"))
    runCurrent()
    vm.retry()
    runCurrent()

    assertEquals("Party, moved", states.lastLoaded().details.event.title)
    assertEquals(CLUB_NAME, states.lastLoaded().associationName)
    assertEquals(listOf(CLUB_UID), fixture.associations.requestedUids)
  }
}
