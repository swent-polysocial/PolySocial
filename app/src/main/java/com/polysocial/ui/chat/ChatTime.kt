// Contributors: Claude (Opus 5.5, Anthropic) (the Chats list's message times, from #362's notes,
// #364).
package com.polysocial.ui.chat

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * How the Chats list shows a latest message's time (#362's notes): the time today, "Yesterday", the
 * weekday within the last week, then the date.
 */
sealed interface ChatTime {
  data class Today(val time: LocalTime) : ChatTime

  data object Yesterday : ChatTime

  data class ThisWeek(val day: DayOfWeek) : ChatTime

  data class Earlier(val date: LocalDate) : ChatTime
}

/**
 * The [ChatTime] of a message sent [at], seen [now] in [zone]. A time after [now] (another device's
 * clock ahead of this one's) counts as today.
 */
fun chatTime(at: Instant, now: Instant, zone: ZoneId): ChatTime {
  val sent = at.atZone(zone)
  val today = now.atZone(zone).toLocalDate()
  val day = sent.toLocalDate()
  return when {
    !day.isBefore(today) -> ChatTime.Today(sent.toLocalTime())
    day == today.minusDays(1) -> ChatTime.Yesterday
    day.isAfter(today.minusDays(DAYS_IN_A_WEEK)) -> ChatTime.ThisWeek(day.dayOfWeek)
    else -> ChatTime.Earlier(day)
  }
}

private const val DAYS_IN_A_WEEK = 7L
