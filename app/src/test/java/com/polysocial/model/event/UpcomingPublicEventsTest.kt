// Contributors: Claude (wrote these tests, #49).
package com.polysocial.model.event

import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The upcoming-events window ([isUpcomingPublicEvent]) and the fake's query that follows it. */
class UpcomingPublicEventsTest {
  private val window = DEFAULT_UPCOMING_WINDOW_DAYS

  private fun startingIn(duration: Duration, isPrivate: Boolean = false, title: String = "Event") =
      validEvent(title = title, startTime = TEST_NOW.plus(duration)).copy(isPrivate = isPrivate)

  @Test
  fun aPublicEventStartingInTheWindow_isUpcoming() {
    assertTrue(startingIn(Duration.ofDays(3)).isUpcomingPublicEvent(TEST_NOW, window))
  }

  @Test
  fun aPrivateEvent_isNeverUpcomingEvenInTheWindow() {
    assertFalse(
        startingIn(Duration.ofDays(3), isPrivate = true).isUpcomingPublicEvent(TEST_NOW, window)
    )
  }

  @Test
  fun theWindow_includesNowAndExcludesItsEnd() {
    assertTrue(startingIn(Duration.ZERO).isUpcomingPublicEvent(TEST_NOW, window))
    assertFalse(startingIn(Duration.ofDays(14)).isUpcomingPublicEvent(TEST_NOW, window))
    assertTrue(
        startingIn(Duration.ofDays(14).minusSeconds(1)).isUpcomingPublicEvent(TEST_NOW, window)
    )
  }

  @Test
  fun anEventThatAlreadyStarted_isNotUpcoming() {
    assertFalse(startingIn(Duration.ofMinutes(-1)).isUpcomingPublicEvent(TEST_NOW, window))
  }

  @Test
  fun theWindowSize_isConfigurable() {
    val inEightDays = startingIn(Duration.ofDays(8))

    assertFalse(inEightDays.isUpcomingPublicEvent(TEST_NOW, windowDays = 7))
    assertTrue(inEightDays.isUpcomingPublicEvent(TEST_NOW, windowDays = 14))
  }

  @Test
  fun theFake_returnsOnlyPublicEventsInTheWindowSortedByStart() = runTest {
    val later = startingIn(Duration.ofDays(5), title = "Later")
    val sooner = startingIn(Duration.ofDays(1), title = "Sooner")
    val repository = FakeEventRepository()
    repository.seed(
        later,
        startingIn(Duration.ofDays(2), isPrivate = true, title = "Private"),
        startingIn(Duration.ofDays(30), title = "Too far"),
        startingIn(Duration.ofHours(-2), title = "Started"),
        sooner,
    )

    assertEquals(
        PublicEventsResult.Events(listOf(sooner, later), fromCache = false),
        repository.getUpcomingPublicEvents().first(),
    )
  }

  @Test
  fun theFake_neverReturnsAPrivateEvent() = runTest {
    val repository = FakeEventRepository()
    repository.seed(startingIn(Duration.ofDays(1), isPrivate = true))

    assertEquals(
        PublicEventsResult.Events(emptyList(), fromCache = false),
        repository.getUpcomingPublicEvents().first(),
    )
  }

  @Test
  fun theFake_emitsAgainWhenAnEventIsCreated() = runTest {
    val repository = FakeEventRepository()
    val results = mutableListOf<PublicEventsResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.getUpcomingPublicEvents().take(2).toList(results)
        }

    repository.createEvent(validEvent())
    collecting.join()

    assertEquals(PublicEventsResult.Events(emptyList(), fromCache = false), results[0])
    assertEquals(
        listOf("Study session"),
        (results[1] as PublicEventsResult.Events).events.map { it.title },
    )
  }

  @Test
  fun theFake_canReportAnErrorOrCachedData() = runTest {
    val repository = FakeEventRepository()

    repository.readFromCache = true
    assertEquals(
        PublicEventsResult.Events(emptyList(), fromCache = true),
        repository.getUpcomingPublicEvents().first(),
    )

    repository.readFailure = true
    assertEquals(PublicEventsResult.Error, repository.getUpcomingPublicEvents().first())
  }

  @Test
  fun aWindowThatIsNotPositive_isRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      FakeEventRepository().getUpcomingPublicEvents(windowDays = 0)
    }
  }
}
