// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #364).
package com.polysocial.ui.chat

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Chats list's message times (#362's notes), in Lausanne. */
class ChatTimeTest {
  private val zurich = ZoneId.of("Europe/Zurich")

  /** Wednesday 7 October 2026, 12:00 in Lausanne. */
  private val now = Instant.parse("2026-10-07T10:00:00Z")

  private fun at(local: String) = LocalDateTime.parse(local).atZone(zurich).toInstant()

  @Test
  fun today_isTheTime_fromMidnightOn() {
    assertEquals(ChatTime.Today(LocalTime.of(0, 0)), chatTime(at("2026-10-07T00:00"), now, zurich))
    assertEquals(
        ChatTime.Today(LocalTime.of(11, 59)),
        chatTime(at("2026-10-07T11:59"), now, zurich),
    )
  }

  @Test
  fun aTimeAheadOfThisClock_countsAsToday() {
    assertEquals(ChatTime.Today(LocalTime.of(13, 5)), chatTime(at("2026-10-07T13:05"), now, zurich))
    assertEquals(ChatTime.Today(LocalTime.of(9, 0)), chatTime(at("2026-10-08T09:00"), now, zurich))
  }

  @Test
  fun theDayBefore_isYesterday_untilMidnight() {
    assertEquals(ChatTime.Yesterday, chatTime(at("2026-10-06T23:59"), now, zurich))
    assertEquals(ChatTime.Yesterday, chatTime(at("2026-10-06T00:00"), now, zurich))
  }

  @Test
  fun withinTheLastWeek_isTheWeekday_thenTheDate() {
    assertEquals(ChatTime.ThisWeek(DayOfWeek.MONDAY), chatTime(at("2026-10-05T18:00"), now, zurich))
    assertEquals(
        ChatTime.ThisWeek(DayOfWeek.THURSDAY),
        chatTime(at("2026-10-01T08:00"), now, zurich),
    )
    // A week ago, the same weekday as today, is a date: "Wed" would read as today.
    assertEquals(
        ChatTime.Earlier(LocalDate.of(2026, 9, 30)),
        chatTime(at("2026-09-30T20:00"), now, zurich),
    )
  }

  @Test
  fun theDayFollowsTheZone_notUtc() {
    // 23:30 UTC on 6 October is already 7 October in Lausanne.
    assertEquals(
        ChatTime.Today(LocalTime.of(1, 30)),
        chatTime(Instant.parse("2026-10-06T23:30:00Z"), now, zurich),
    )
  }
}
