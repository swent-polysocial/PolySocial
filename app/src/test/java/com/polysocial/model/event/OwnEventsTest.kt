// Contributors: Claude (wrote these tests, #132).
package com.polysocial.model.event

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnEventsTest {
  private val now = TEST_NOW
  private val hour = Duration.ofHours(1)

  private fun event(id: String, startsIn: Duration, lasts: Duration? = hour) =
      validEvent(
              startTime = now.plus(startsIn),
              endTime = lasts?.let { now.plus(startsIn).plus(it) },
          )
          .copy(id = id)

  @Test
  fun anEventIsPastOnlyOnceItHasEnded() {
    assertFalse(event("ending now", startsIn = hour.negated()).isPast(now))
    assertTrue(event("ended", startsIn = hour.multipliedBy(-2)).isPast(now))
    assertFalse(event("running", startsIn = Duration.ofMinutes(-30)).isPast(now))
    assertFalse(event("later", startsIn = hour).isPast(now))
  }

  @Test
  fun withoutAnEndTime_anEventIsPastOnceItHasStarted() {
    assertFalse(event("starting now", startsIn = Duration.ZERO, lasts = null).isPast(now))
    assertTrue(event("started", startsIn = Duration.ofMinutes(-1), lasts = null).isPast(now))
  }

  @Test
  fun anEventIsLiveFromItsStartUntilItEnds() {
    assertTrue(event("starting now", startsIn = Duration.ZERO).isLive(now))
    assertTrue(event("running", startsIn = Duration.ofMinutes(-30)).isLive(now))
    assertFalse(event("later", startsIn = Duration.ofMinutes(1)).isLive(now))
    assertFalse(event("ended", startsIn = hour.multipliedBy(-2)).isLive(now))
  }

  @Test
  fun split_putsTheNextUpcomingEventAndTheLatestPastEventFirst() {
    val nextWeek = event("nextWeek", startsIn = Duration.ofDays(7))
    val running = event("running", startsIn = Duration.ofMinutes(-30))
    val tomorrow = event("tomorrow", startsIn = Duration.ofDays(1))
    val lastMonth = event("lastMonth", startsIn = Duration.ofDays(-30))
    val yesterday = event("yesterday", startsIn = Duration.ofDays(-1))

    val split = splitUpcomingAndPast(listOf(nextWeek, lastMonth, running, yesterday, tomorrow), now)

    assertEquals(listOf(running, tomorrow, nextWeek), split.upcoming)
    assertEquals(listOf(yesterday, lastMonth), split.past)
  }

  @Test
  fun split_ofNoEvents_isEmpty() {
    assertEquals(UpcomingAndPast(emptyList(), emptyList()), splitUpcomingAndPast(emptyList(), now))
  }
}
