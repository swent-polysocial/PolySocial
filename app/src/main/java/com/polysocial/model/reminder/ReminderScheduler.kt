// Contributors: Claude Opus 5.5 (reminder scheduling contract, #123).
package com.polysocial.model.reminder

import java.time.Instant

/**
 * What an event's reminder notification shows. It is saved with the scheduled reminder on the
 * device only, never in Firestore.
 *
 * @property placeName the event's place, or null if it has none to show.
 */
data class EventReminder(
    val eventId: String,
    val title: String,
    val startTime: Instant,
    val placeName: String? = null,
)

/** The outcome of [ReminderScheduler.schedule]. */
sealed interface ScheduleReminderResult {
  /** The reminder will fire [at], replacing any earlier reminder for the same event. */
  data class Scheduled(val at: Instant) : ScheduleReminderResult

  /** The event starts within [REMINDER_LEAD] or has started, so nothing was scheduled. */
  data object TooLate : ScheduleReminderResult

  /** Scheduling failed on the device. Nothing changed, and the caller may retry. */
  data object UnexpectedError : ScheduleReminderResult
}

/** The outcome of [ReminderScheduler.cancel] and [ReminderScheduler.cancelAll]. */
sealed interface CancelReminderResult {
  /** No reminder is left (also when there was none to cancel). */
  data object Cancelled : CancelReminderResult

  /** Cancelling failed on the device, so a reminder may still fire. The caller may retry. */
  data object UnexpectedError : CancelReminderResult
}

/**
 * Schedules local reminders, one per event, [REMINDER_LEAD] before it starts. Reminders stay on the
 * device: they fire offline and after a reboot, and nothing is sent to a server.
 */
interface ReminderScheduler {
  /**
   * Schedules [reminder] at [reminderTime] of its start, replacing the event's earlier reminder, so
   * an event never has two.
   */
  suspend fun schedule(reminder: EventReminder): ScheduleReminderResult

  /**
   * Removes the reminder of [eventId], when its option is turned off or the registration is
   * cancelled.
   */
  suspend fun cancel(eventId: String): CancelReminderResult

  /** Removes every reminder, when the student logs out. */
  suspend fun cancelAll(): CancelReminderResult
}
