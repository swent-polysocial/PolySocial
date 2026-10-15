// Contributors: Claude Opus 5.5 (reminder scheduler fake for tests, #123).
package com.polysocial.model.reminder

import java.time.Clock

/**
 * In-memory [ReminderScheduler] for tests: it keeps the scheduled reminders by event ID and uses
 * the same [reminderTime] as the real one. Set [fail] to make every call return its error.
 */
class FakeReminderScheduler(private val clock: Clock) : ReminderScheduler {
  /** The scheduled reminders, by event ID. */
  val scheduled = mutableMapOf<String, EventReminder>()

  var fail = false

  override suspend fun schedule(reminder: EventReminder): ScheduleReminderResult {
    if (fail) return ScheduleReminderResult.UnexpectedError
    val at =
        reminderTime(reminder.startTime, clock.instant()) ?: return ScheduleReminderResult.TooLate
    scheduled[reminder.eventId] = reminder
    return ScheduleReminderResult.Scheduled(at)
  }

  override suspend fun cancel(eventId: String): CancelReminderResult {
    if (fail) return CancelReminderResult.UnexpectedError
    scheduled.remove(eventId)
    return CancelReminderResult.Cancelled
  }

  override suspend fun cancelAll(): CancelReminderResult {
    if (fail) return CancelReminderResult.UnexpectedError
    scheduled.clear()
    return CancelReminderResult.Cancelled
  }
}
