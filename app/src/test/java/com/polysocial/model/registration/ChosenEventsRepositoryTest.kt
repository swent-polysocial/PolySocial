// Contributors: Claude Opus 5.5 (medium) (wrote these tests, #203).
package com.polysocial.model.registration

import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import java.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChosenEventsRepositoryTest {

  private val registrations = FakeRegistrationRepository(currentUid = STUDENT)
  private val events = CountingEventRepository(FakeEventRepository(currentUid = STUDENT))
  private val repository = ChosenEventsRepository(registrations, events)

  private fun event(id: String, startsIn: Duration) =
      validEvent(title = "Event $id", startTime = TEST_NOW.plus(startsIn)).copy(id = id)

  private val tomorrow = event("tomorrow", Duration.ofDays(1))
  private val inTwoMonths = event("inTwoMonths", Duration.ofDays(60))
  private val lastWeek = event("lastWeek", Duration.ofDays(-7))

  /** Seeds [chosen] as stored events the student registered for. */
  private fun choose(vararg chosen: Event) {
    events.delegate.seed(*chosen)
    chosen.forEach { registrations.seedRegistration(it.id, STUDENT) }
  }

  /** Collects the chosen events in the background; the list holds every result so far. */
  private fun TestScope.startCollecting(): Pair<MutableList<ChosenEventsResult>, Job> {
    val results = mutableListOf<ChosenEventsResult>()
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeChosenEvents().collect { results += it }
        }
    runCurrent()
    return results to job
  }

  private val List<ChosenEventsResult>.latest: ChosenEventsResult.Events
    get() = last() as ChosenEventsResult.Events

  @Test
  fun noRegistrations_isAnEmptyList() = runTest {
    val (results, _) = startCollecting()

    assertEquals(
        ChosenEventsResult.Events(emptyList(), emptySet(), emptySet(), emptySet(), false),
        results.last(),
    )
  }

  @Test
  fun everyChosenEvent_beyondThePublicWindowAndPast_isListedInStartOrder() = runTest {
    choose(inTwoMonths, lastWeek, tomorrow)

    val (results, _) = startCollecting()

    assertEquals(listOf(lastWeek, tomorrow, inTwoMonths), results.latest.events)
    assertEquals(emptySet<String>(), results.latest.pendingIds)
  }

  @Test
  fun eventsAtTheSameTime_areOrderedByIdSoTheOrderIsStable() = runTest {
    val b = event("b", Duration.ofDays(2))
    val a = event("a", Duration.ofDays(2))
    choose(b, a)

    val (results, _) = startCollecting()

    assertEquals(listOf("a", "b"), results.latest.events.map { it.id })
  }

  @Test
  fun onlyTheStudentsOwnRegistrations_areListed() = runTest {
    choose(tomorrow)
    events.delegate.seed(inTwoMonths)
    registrations.seedRegistration(inTwoMonths.id, "another-student")

    val (results, _) = startCollecting()

    assertEquals(listOf(tomorrow), results.latest.events)
    assertEquals(listOf(tomorrow.id), events.started)
  }

  @Test
  fun registeringAndCancelling_updateTheListWithoutANewCollection() = runTest {
    events.delegate.seed(tomorrow, inTwoMonths)
    registrations.readableEventIds += setOf(tomorrow.id, inTwoMonths.id)
    val (results, _) = startCollecting()

    registrations.register(inTwoMonths.id)
    runCurrent()
    assertEquals(listOf(inTwoMonths), results.latest.events)

    registrations.register(tomorrow.id)
    runCurrent()
    assertEquals(listOf(tomorrow, inTwoMonths), results.latest.events)

    registrations.cancel(inTwoMonths.id)
    runCurrent()
    assertEquals(listOf(tomorrow), results.latest.events)
  }

  @Test
  fun anEditedEvent_showsItsNewValues() = runTest {
    choose(tomorrow)
    val (results, _) = startCollecting()

    events.delegate.events[0] = tomorrow.copy(title = "Renamed")
    events.delegate.seed()
    runCurrent()

    assertEquals("Renamed", results.latest.events.single().title)
  }

  @Test
  fun eachEvent_hasOneListener_keptWhileRegistered_andStoppedOnCancel() = runTest {
    events.delegate.seed(tomorrow, inTwoMonths)
    registrations.readableEventIds += setOf(tomorrow.id, inTwoMonths.id)
    registrations.register(tomorrow.id)
    startCollecting()

    // A new registration emits all the IDs again; the existing listener must stay.
    registrations.register(inTwoMonths.id)
    runCurrent()
    assertEquals(listOf(tomorrow.id, inTwoMonths.id), events.started)
    assertEquals(2, events.active)

    registrations.cancel(tomorrow.id)
    runCurrent()
    assertEquals(1, events.active)
    assertEquals(listOf(tomorrow.id, inTwoMonths.id), events.started)
  }

  @Test
  fun cancellingTheCollection_stopsEveryListener() = runTest {
    choose(tomorrow, inTwoMonths)
    val (_, job) = startCollecting()
    assertEquals(2, events.active)

    job.cancel()
    runCurrent()

    assertEquals(0, events.active)
  }

  @Test
  fun aDeletedEvent_isUnavailable_withoutItsDetails() = runTest {
    choose(tomorrow, inTwoMonths)
    val (results, _) = startCollecting()

    events.delegate.remove(tomorrow.id)
    runCurrent()

    assertEquals(listOf(inTwoMonths), results.latest.events)
    assertEquals(setOf(tomorrow.id), results.latest.unavailableIds)
  }

  @Test
  fun anEventTheStudentCanNoLongerRead_orAMalformedId_isUnavailable() = runTest {
    choose(tomorrow)
    events.delegate.deniedIds += tomorrow.id
    registrations.seedRegistration("bad/id", STUDENT)

    val (results, _) = startCollecting()

    assertEquals(emptyList<Any>(), results.latest.events)
    assertEquals(setOf(tomorrow.id, "bad/id"), results.latest.unavailableIds)
  }

  @Test
  fun offline_cachedEventsAreListed_andTheOthersAreNotCached() = runTest {
    choose(tomorrow)
    registrations.seedRegistration("never-read", STUDENT)
    registrations.readFromCache = true
    events.delegate.readFromCache = true

    val (results, _) = startCollecting()

    assertEquals(listOf(tomorrow), results.latest.events)
    assertEquals(setOf("never-read"), results.latest.notCachedIds)
    assertTrue(results.latest.fromCache)
  }

  @Test
  fun cachedRegistrations_alone_areFromCache() = runTest {
    registrations.readFromCache = true

    val (results, _) = startCollecting()

    assertTrue(results.latest.fromCache)
  }

  @Test
  fun anEventThatHasNotAnsweredYet_isPending() = runTest {
    choose(tomorrow)
    events.silentIds += tomorrow.id

    val (results, _) = startCollecting()

    assertEquals(setOf(tomorrow.id), results.latest.pendingIds)
    assertEquals(emptyList<Any>(), results.latest.events)
  }

  @Test
  fun failedRegistrations_areAnError_thatEndsTheFlowAndItsListeners() = runTest {
    registrations.readFailure = true

    val (results, job) = startCollecting()

    assertEquals(listOf(ChosenEventsResult.Error), results)
    assertTrue(job.isCompleted)
    assertEquals(0, events.active)
  }

  @Test
  fun nobodySignedIn_isAnError() = runTest {
    registrations.currentUid = null

    val (results, _) = startCollecting()

    assertEquals(listOf(ChosenEventsResult.Error), results)
  }

  @Test
  fun aNewCollection_afterAnAccountChange_listsOnlyTheNewAccountsEvents() = runTest {
    choose(tomorrow)
    events.delegate.seed(inTwoMonths)
    registrations.seedRegistration(inTwoMonths.id, "next-student")
    val (_, first) = startCollecting()
    first.cancel()

    registrations.currentUid = "next-student"
    val (results, _) = startCollecting()

    assertEquals(listOf(inTwoMonths), results.latest.events)
  }

  /**
   * Counts the listeners [ChosenEventsRepository] starts on [delegate]: [started] lists the IDs in
   * start order and [active] how many are still running. An ID in [silentIds] never answers, like a
   * read still waiting for the server.
   */
  private class CountingEventRepository(val delegate: FakeEventRepository) :
      EventRepository by delegate {
    val started = mutableListOf<String>()
    var active = 0
      private set

    val silentIds = mutableSetOf<String>()

    override fun getEvent(id: String): Flow<EventResult> {
      val source =
          if (id in silentIds) flow<EventResult> { awaitCancellation() } else delegate.getEvent(id)
      return source
          .onStart {
            started += id
            active++
          }
          .onCompletion { active-- }
    }
  }

  private companion object {
    const val STUDENT = "test-student"
  }
}
