// Contributors: Claude Opus 5.5 (WorkManager reminder scheduling, #123).
package com.polysocial.model.reminder

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.await
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Tag of every reminder's work, so [WorkManagerReminderScheduler.cancelAll] finds them all. */
internal const val REMINDER_WORK_TAG = "event_reminder"

/** The unique work name of [eventId]'s reminder, so an event has at most one. */
internal fun reminderWorkName(eventId: String) = "event_reminder_$eventId"

/**
 * [ReminderScheduler] backed by WorkManager: each reminder is a [ReminderWorker] delayed until its
 * [reminderTime]. WorkManager keeps the work on the device, so it survives a reboot, and it runs
 * without a network because the work has no constraints.
 */
class WorkManagerReminderScheduler
@Inject
constructor(
    private val workManager: WorkManager,
    private val clock: Clock,
) : ReminderScheduler {

  override suspend fun schedule(reminder: EventReminder): ScheduleReminderResult {
    val now = clock.instant()
    val at = reminderTime(reminder.startTime, now) ?: return ScheduleReminderResult.TooLate
    val request =
        OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(Duration.between(now, at))
            .setInputData(reminder.toWorkData())
            .addTag(REMINDER_WORK_TAG)
            .build()
    return if (
        succeeded {
          workManager.enqueueUniqueWork(
              reminderWorkName(reminder.eventId),
              ExistingWorkPolicy.REPLACE,
              request,
          )
        }
    ) {
      ScheduleReminderResult.Scheduled(at)
    } else {
      ScheduleReminderResult.UnexpectedError
    }
  }

  override suspend fun cancel(eventId: String): CancelReminderResult =
      cancelResult(succeeded { workManager.cancelUniqueWork(reminderWorkName(eventId)) })

  override suspend fun cancelAll(): CancelReminderResult =
      cancelResult(succeeded { workManager.cancelAllWorkByTag(REMINDER_WORK_TAG) })

  private fun cancelResult(succeeded: Boolean) =
      if (succeeded) CancelReminderResult.Cancelled else CancelReminderResult.UnexpectedError

  /**
   * Starts [operation] and waits until WorkManager has saved it. False if WorkManager fails, for
   * example when its database can't be written.
   */
  private suspend fun succeeded(operation: () -> Operation): Boolean =
      try {
        operation().await()
        true
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        false
      }
}
