// Contributors: Claude (wrote these tests).
package com.polysocial.model.event

import org.junit.Assert.assertEquals
import org.junit.Test

class EventValidationTest {

  private fun errorsFor(event: Event) = validateNewEvent(event, TEST_NOW)

  @Test
  fun validEvent_hasNoErrors() {
    assertEquals(emptyList<EventValidationError>(), errorsFor(validEvent()))
  }

  @Test
  fun emptyTitle_isRejected() {
    assertEquals(listOf(EventValidationError.BLANK_TITLE), errorsFor(validEvent(title = "")))
  }

  @Test
  fun whitespaceOnlyTitle_isRejected() {
    assertEquals(listOf(EventValidationError.BLANK_TITLE), errorsFor(validEvent(title = "  \n ")))
  }

  @Test
  fun startBeforeNow_isRejected() {
    val event = validEvent(startTime = TEST_NOW.minusSeconds(60))
    assertEquals(listOf(EventValidationError.START_IN_PAST), errorsFor(event))
  }

  @Test
  fun startExactlyNow_isAccepted() {
    assertEquals(emptyList<EventValidationError>(), errorsFor(validEvent(startTime = TEST_NOW)))
  }

  @Test
  fun startEarlierInTheCurrentMinute_isAccepted() {
    val now = TEST_NOW.plusSeconds(40)
    val event = validEvent(startTime = TEST_NOW)
    assertEquals(emptyList<EventValidationError>(), validateNewEvent(event, now))
  }

  @Test
  fun startInThePreviousMinute_isRejected() {
    val now = TEST_NOW.plusSeconds(40)
    val event = validEvent(startTime = TEST_NOW.minusSeconds(1))
    assertEquals(listOf(EventValidationError.START_IN_PAST), validateNewEvent(event, now))
  }

  @Test
  fun noEndTime_isAccepted() {
    assertEquals(emptyList<EventValidationError>(), errorsFor(validEvent(endTime = null)))
  }

  @Test
  fun endEqualToStart_isRejected() {
    val start = TEST_NOW.plusSeconds(3600)
    val event = validEvent(startTime = start, endTime = start)
    assertEquals(listOf(EventValidationError.END_NOT_AFTER_START), errorsFor(event))
  }

  @Test
  fun endBeforeStart_isRejected() {
    val start = TEST_NOW.plusSeconds(3600)
    val event = validEvent(startTime = start, endTime = start.minusSeconds(60))
    assertEquals(listOf(EventValidationError.END_NOT_AFTER_START), errorsFor(event))
  }

  @Test
  fun noCapacity_isAccepted() {
    assertEquals(emptyList<EventValidationError>(), errorsFor(validEvent(capacity = null)))
  }

  @Test
  fun capacityAtMinimum_isAccepted() {
    val event = validEvent(capacity = MIN_CAPACITY)
    assertEquals(emptyList<EventValidationError>(), errorsFor(event))
  }

  @Test
  fun capacityBelowMinimum_isRejected() {
    for (capacity in listOf(MIN_CAPACITY - 1, 0, -5)) {
      assertEquals(
          "capacity $capacity",
          listOf(EventValidationError.CAPACITY_TOO_SMALL),
          errorsFor(validEvent(capacity = capacity)),
      )
    }
  }

  @Test
  fun everyBrokenRule_isReported() {
    val start = TEST_NOW.minusSeconds(3600)
    val event = validEvent(title = " ", startTime = start, endTime = start, capacity = 1)
    assertEquals(
        listOf(
            EventValidationError.BLANK_TITLE,
            EventValidationError.START_IN_PAST,
            EventValidationError.END_NOT_AFTER_START,
            EventValidationError.CAPACITY_TOO_SMALL,
        ),
        errorsFor(event),
    )
  }
}
