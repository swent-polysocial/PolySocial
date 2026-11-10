// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests of an association's own events on
// its map, #133.
package com.polysocial.model.map

import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.TEST_NOW
import com.polysocial.model.event.validEvent
import com.polysocial.ui.map.MAP_TEST_CLOCK
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnAssociationMapEventSourceTest {
  private val repository = FakeEventRepository(currentUid = CLUB)

  private fun own(id: String, startIn: Duration, endIn: Duration? = startIn.plusHours(2)) =
      validEvent(
              startTime = TEST_NOW.plus(startIn),
              endTime = endIn?.let { TEST_NOW.plus(it) },
          )
          .copy(id = id, createdBy = CLUB, isAssociationEvent = true)

  @Test
  fun keepsTheUpcomingOwnEvents_privateOnesIncluded_andDropsTheEndedOnes() = runTest {
    val soon = own("soon", Duration.ofDays(1))
    val private = own("private", Duration.ofDays(30)).copy(isPrivate = true)
    // Started an hour ago and still running: it stays, as on the Events tab's Upcoming.
    val running = own("running", Duration.ofHours(-1))
    repository.seed(
        soon,
        private,
        running,
        own("ended", Duration.ofDays(-1)),
        own("started without end", Duration.ofHours(-1), endIn = null),
        validEvent().copy(id = "other club", createdBy = "other", isAssociationEvent = true),
    )

    assertEquals(
        MapEventResult.Events(listOf(running, soon, private)),
        OwnAssociationMapEventSource(repository, MAP_TEST_CLOCK).observeEvents().first(),
    )
  }

  @Test
  fun eventsTheRulesDeny_areAnError() = runTest {
    repository.ownEventsFailure = true

    assertEquals(
        listOf(MapEventResult.Error),
        OwnAssociationMapEventSource(repository, MAP_TEST_CLOCK).observeEvents().toList(),
    )
  }

  @Test
  fun onlyTheAssociationsSource_marksItsEventsAsItsOwn() {
    assertTrue(OwnAssociationMapEventSource(repository, MAP_TEST_CLOCK).ownEvents)
    assertFalse(RepositoryMapEventSource(repository).ownEvents)
    assertFalse(MapEventSource { error("not observed") }.ownEvents)
  }

  private companion object {
    const val CLUB = "club"
  }
}
