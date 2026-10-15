// Contributors: Claude (wrote these tests of the fake's getEvent contract, #112).
package com.polysocial.model.event

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks that [FakeEventRepository.getEvent] follows the [EventRepository.getEvent] contract, since
 * the event detail ViewModel tests rely on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeEventRepositoryTest {
  private val repository = FakeEventRepository()
  private val event = validEvent().copy(id = "event-1")

  @Test
  fun getEvent_emitsTheEventThenNotFoundOnceItIsDeleted() = runTest {
    repository.seed(event)
    val results = mutableListOf<EventResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getEvent("event-1").toList(results)
        }

    repository.remove("event-1")
    collecting.cancel()

    assertEquals(listOf(EventResult.Found(event, false), EventResult.NotFound), results)
  }

  @Test
  fun getEvent_offline_isFoundFromTheCacheOrNotCached() = runTest {
    repository.readFromCache = true

    assertEquals(EventResult.NotCached, repository.getEvent("event-1").first())
    repository.seed(event)
    assertEquals(EventResult.Found(event, true), repository.getEvent("event-1").first())
  }

  @Test
  fun getEvent_aDeniedIdIsNotFoundAndEndsEvenIfTheEventExists() = runTest {
    repository.seed(event)
    repository.deniedIds += "event-1"

    assertEquals(listOf(EventResult.NotFound), repository.getEvent("event-1").toList())
  }

  @Test
  fun getEvent_anIdThatCannotBeADocumentIdIsNotFoundEvenOffline() = runTest {
    repository.seed(validEvent())
    repository.readFromCache = true

    for (id in listOf("", " ", "a/b")) {
      assertEquals(id, EventResult.NotFound, repository.getEvent(id).first())
    }
  }
}
