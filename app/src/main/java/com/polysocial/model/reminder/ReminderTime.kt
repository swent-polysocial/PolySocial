// Contributors: Claude Opus 5.5 (reminder time and notification day, #123).
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
