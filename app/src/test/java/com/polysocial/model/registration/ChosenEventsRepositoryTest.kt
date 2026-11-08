// Contributors: Claude Opus 5.5 (medium) (wrote these tests, #203).
// Contributors: Claude (Opus 5.5, Anthropic) tested private events in the chosen events
// (duplicates,
// Remove, failures, waiting, offline, account switch), #320.
package com.polysocial.model.registration

import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.PrivateEventsResult
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import java.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  /** A private event [STUDENT] may read (they organize it, are a member or were approved). */
  private fun readable(id: String, startsIn: Duration, reader: String = STUDENT) =
      event(id, startsIn).copy(isPrivate = true, allowedUids = listOf("organizer", reader))

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
  fun aCachedEvent_alone_makesTheListFromCache() = runTest {
    choose(tomorrow)
    events.delegate.readFromCache = true
    events.privateEvents = flowOf(PrivateEventsResult.Events(emptyList(), fromCache = false))

    val (results, _) = startCollecting()

    assertEquals(listOf(tomorrow), results.latest.events)
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
  fun failedRegistrations_stillListThePrivateEvents_andSayTheReadFailed() = runTest {
    val organized = readable("organized", Duration.ofDays(3))
    events.delegate.seed(organized)
    choose(tomorrow)
    registrations.readFailure = true

    val (results, _) = startCollecting()

    assertEquals(listOf(organized), results.latest.events)
    assertTrue(results.latest.failed)
    assertFalse(results.latest.waiting)
    assertEquals(0, events.active)
  }

  @Test
  fun failedPrivateEvents_stillListTheRegisteredOnes_andSayTheReadFailed() = runTest {
    choose(tomorrow)
    events.delegate.seed(readable("organized", Duration.ofDays(3)))
    events.delegate.privateEventsFailure = true

    val (results, _) = startCollecting()

    assertEquals(listOf(tomorrow), results.latest.events)
    assertTrue(results.latest.failed)
  }

  @Test
  fun registrationsFailingLater_dropTheRegisteredEvents_andKeepThePrivateOnesListening() = runTest {
    val organized = readable("organized", Duration.ofDays(3))
    events.delegate.seed(tomorrow, organized)
    val answers = Channel<MyRegistrationsResult>(Channel.UNLIMITED)
    val failingLater =
        object : RegistrationRepository by registrations {
          override fun observeMyRegistrations() = answers.consumeAsFlow()
        }
    val results = mutableListOf<ChosenEventsResult>()
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          ChosenEventsRepository(failingLater, events).observeChosenEvents().collect {
            results += it
          }
        }
    answers.send(MyRegistrationsResult.Registrations(setOf(tomorrow.id), fromCache = false))
    runCurrent()
    assertEquals(listOf(tomorrow, organized), results.latest.events)
    assertEquals(1, events.active)

    // Left open after its error, so the listeners must stop at the error itself, not only once
    // the registrations flow ends.
    answers.send(MyRegistrationsResult.Error)
    runCurrent()
    // A late change to the registered event must not bring it back.
    events.delegate.events[0] = tomorrow.copy(title = "Renamed")
    events.delegate.seed()
    runCurrent()

    assertEquals(listOf(organized), results.latest.events)
    assertTrue(results.latest.failed)
    assertEquals(emptySet<String>(), results.latest.pendingIds)
    assertEquals(0, events.active)
    // The private events are still listened to.
    assertTrue(job.isActive)
    val created = readable("created", Duration.ofDays(4))
    events.delegate.seed(created)
    runCurrent()
    assertEquals(listOf(organized, created), results.latest.events)
  }

  @Test
  fun bothReadsFailing_isAnError_thatEndsTheFlowAndItsListeners() = runTest {
    registrations.readFailure = true
    events.delegate.privateEventsFailure = true

    val (results, job) = startCollecting()

    assertEquals(ChosenEventsResult.Error, results.last())
    assertTrue(job.isCompleted)
    assertEquals(0, events.active)
  }

  @Test
  fun registrationsFailingLater_stopTheEventListeners_andTheErrorStaysLast() = runTest {
    events.delegate.seed(tomorrow)
    val answers = Channel<MyRegistrationsResult>(Channel.UNLIMITED)
    val failingLater =
        object : RegistrationRepository by registrations {
          override fun observeMyRegistrations() = answers.consumeAsFlow()
        }
    // With the private events failing too, the registrations' error ends everything.
    events.delegate.privateEventsFailure = true
    val results = mutableListOf<ChosenEventsResult>()
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          ChosenEventsRepository(failingLater, events).observeChosenEvents().collect {
            results += it
          }
        }
    answers.send(MyRegistrationsResult.Registrations(setOf(tomorrow.id), fromCache = false))
    runCurrent()
    assertEquals(listOf(tomorrow), results.latest.events)
    assertEquals(1, events.active)

    // Like the real repository, the registrations flow ends after its error.
    answers.send(MyRegistrationsResult.Error)
    answers.close()
    runCurrent()
    events.delegate.events[0] = tomorrow.copy(title = "Renamed")
    events.delegate.seed()
    runCurrent()

    assertEquals(ChosenEventsResult.Error, results.last())
    assertEquals(0, events.active)
    assertTrue(job.isCompleted)
  }

  @Test
  fun nobodySignedIn_isAnError() = runTest {
    registrations.currentUid = null
    events.delegate.currentUid = null

    val (results, job) = startCollecting()

    assertEquals(ChosenEventsResult.Error, results.last())
    assertTrue(job.isCompleted)
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

  @Test
  fun privateEventsTheStudentMayRead_areListedWithoutRegistering_inStartOrder() = runTest {
    choose(tomorrow)
    val organizedLastWeek = readable("organizedLastWeek", Duration.ofDays(-7))
    val memberInTwoMonths = readable("memberInTwoMonths", Duration.ofDays(60))
    events.delegate.seed(
        memberInTwoMonths,
        organizedLastWeek,
        readable("someoneElses", Duration.ofDays(2), reader = "another-student"),
        event("public", Duration.ofDays(2)),
    )

    val (results, _) = startCollecting()

    assertEquals(listOf(organizedLastWeek, tomorrow, memberInTwoMonths), results.latest.events)
    assertEquals(emptySet<String>(), results.latest.unavailableIds)
    assertFalse(results.latest.waiting)
    assertFalse(results.latest.failed)
  }

  @Test
  fun aRegisteredPrivateEvent_isListedOnce() = runTest {
    val registeredPrivate = readable("registeredPrivate", Duration.ofDays(1))
    choose(registeredPrivate)

    val (results, _) = startCollecting()

    assertEquals(listOf(registeredPrivate), results.latest.events)
    assertEquals(emptySet<String>(), results.latest.pendingIds)
  }

  @Test
  fun aNewPrivateEvent_appears_andOneTheStudentCanNoLongerRead_leavesWithoutATrace() = runTest {
    val (results, _) = startCollecting()

    val created = readable("created", Duration.ofDays(1))
    events.delegate.seed(created)
    runCurrent()
    assertEquals(listOf(created), results.latest.events)

    // The organizer removes the student from the readers: no "unavailable" row, nothing to remove.
    events.delegate.events[0] = created.copy(allowedUids = listOf("organizer"))
    events.delegate.seed()
    runCurrent()
    assertEquals(emptyList<Event>(), results.latest.events)
    assertEquals(emptySet<String>(), results.latest.unavailableIds)
  }

  @Test
  fun aDeletedPrivateEvent_isUnavailableOnlyIfTheStudentRegisteredForIt() = runTest {
    val registeredPrivate = readable("registeredPrivate", Duration.ofDays(1))
    val onlyReadable = readable("onlyReadable", Duration.ofDays(2))
    choose(registeredPrivate)
    events.delegate.seed(onlyReadable)
    val (results, _) = startCollecting()
    assertEquals(listOf(registeredPrivate, onlyReadable), results.latest.events)

    events.delegate.remove(registeredPrivate.id)
    events.delegate.remove(onlyReadable.id)
    runCurrent()

    assertEquals(emptyList<Event>(), results.latest.events)
    // Only the registration can be removed (Remove cancels it).
    assertEquals(setOf(registeredPrivate.id), results.latest.unavailableIds)
  }

  @Test
  fun aRegisteredEventThatCouldNotBeRead_isShownFromThePrivateEvents() = runTest {
    // The event's own read was denied (it ended with NotFound), but the student may read it now.
    val registeredPrivate = readable("registeredPrivate", Duration.ofDays(1))
    choose(registeredPrivate)
    events.delegate.deniedIds += registeredPrivate.id

    val (results, _) = startCollecting()

    assertEquals(listOf(registeredPrivate), results.latest.events)
    assertEquals(emptySet<String>(), results.latest.unavailableIds)
  }

  @Test
  fun privateEventsThatHaveNotAnsweredYet_areWaiting_butTheRegisteredOnesShow() = runTest {
    choose(tomorrow)
    events.privateEvents = flow { awaitCancellation() }

    val (results, _) = startCollecting()

    assertEquals(listOf(tomorrow), results.latest.events)
    assertTrue(results.latest.waiting)
  }

  @Test
  fun registrationsThatHaveNotAnsweredYet_areWaiting_butThePrivateEventsShow() = runTest {
    val organized = readable("organized", Duration.ofDays(3))
    events.delegate.seed(organized)
    val silent =
        object : RegistrationRepository by registrations {
          override fun observeMyRegistrations(): Flow<MyRegistrationsResult> = flow {
            awaitCancellation()
          }
        }
    val results = mutableListOf<ChosenEventsResult>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      ChosenEventsRepository(silent, events).observeChosenEvents().collect { results += it }
    }
    runCurrent()

    assertEquals(listOf(organized), results.latest.events)
    assertTrue(results.latest.waiting)
  }

  @Test
  fun offline_cachedPrivateEventsAreListed_fromCache() = runTest {
    val organized = readable("organized", Duration.ofDays(3))
    events.delegate.seed(organized)
    events.delegate.readFromCache = true

    val (results, _) = startCollecting()

    assertEquals(listOf(organized), results.latest.events)
    assertTrue(results.latest.fromCache)
  }

  @Test
  fun aNewCollection_afterAnAccountChange_listsOnlyTheNewAccountsPrivateEvents() = runTest {
    val mine = readable("mine", Duration.ofDays(1))
    val theirs = readable("theirs", Duration.ofDays(2), reader = "next-student")
    events.delegate.seed(mine, theirs)
    val (before, first) = startCollecting()
    assertEquals(listOf(mine), before.latest.events)
    first.cancel()

    registrations.currentUid = "next-student"
    events.delegate.currentUid = "next-student"
    val (results, _) = startCollecting()

    assertEquals(listOf(theirs), results.latest.events)
  }

  /**
   * Counts the listeners [ChosenEventsRepository] starts on [delegate]: [started] lists the IDs in
   * start order and [active] how many are still running. An ID in [silentIds] never answers, like a
   * read still waiting for the server. The private events come from [privateEvents] when it is set.
   */
  private class CountingEventRepository(val delegate: FakeEventRepository) :
      EventRepository by delegate {
    val started = mutableListOf<String>()
    var active = 0
      private set

    val silentIds = mutableSetOf<String>()

    var privateEvents: Flow<PrivateEventsResult>? = null

    override fun getReadablePrivateEvents(): Flow<PrivateEventsResult> =
        privateEvents ?: delegate.getReadablePrivateEvents()

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
