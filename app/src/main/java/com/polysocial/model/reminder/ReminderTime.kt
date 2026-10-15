// Contributors: Claude Opus 5.5 (reminder time and notification day, #123; time left after review).
package com.polysocial.model.reminder

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** How long before the start an event's reminder fires ("Remind me 1 h before"). */
val REMINDER_LEAD: Duration = Duration.ofHours(1)

/**
 * When the reminder for an event starting at [startTime] fires: [REMINDER_LEAD] before the start.
 * Returns null when that time isn't after [now], that is when the event starts within the hour or
 * has already started, since no reminder is scheduled then.
 */
fun reminderTime(startTime: Instant, now: Instant): Instant? =
    startTime.minus(REMINDER_LEAD).takeIf { it.isAfter(now) }

/** The time left before an event starts, as a reminder's title says it. */
sealed interface TimeLeft {
  data class Minutes(val minutes: Long) : TimeLeft

  data class Hours(val hours: Long) : TimeLeft
}

/**
 * The time left between [now] and [startTime], which must be after [now]: whole minutes, rounded
 * up, under an hour, and otherwise hours, rounded to the nearest one. A reminder on time says "1
 * h", even if it runs a few seconds late; WorkManager may run it later than due (its delay is a
 * minimum), and its title then gives the minutes actually left.
 */
fun timeLeft(startTime: Instant, now: Instant): TimeLeft {
  val minutes = (Duration.between(now, startTime).seconds + 59) / 60
  return if (minutes < 60) TimeLeft.Minutes(minutes) else TimeLeft.Hours((minutes + 30) / 60)
}

/** The day a reminder names for the event's start: "Today", "Tomorrow" or its date. */
enum class ReminderDay {
  TODAY,
  TOMORROW,
  LATER,
}

/**
 * Which day the event starting at [startTime] falls on, seen at [now] in [zone]. A reminder fires
 * an hour before the start, so it's usually [ReminderDay.TODAY], or [ReminderDay.TOMORROW] for an
 * event starting just after midnight.
 */
fun reminderDay(startTime: Instant, now: Instant, zone: ZoneId): ReminderDay {
  val today = now.atZone(zone).toLocalDate()
  return when (startTime.atZone(zone).toLocalDate()) {
    today -> ReminderDay.TODAY
    today.plusDays(1) -> ReminderDay.TOMORROW
    else -> ReminderDay.LATER
  }
}
