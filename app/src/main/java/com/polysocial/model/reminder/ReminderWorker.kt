// Contributors: Claude Opus 5.5 (reminder worker and notification, from the Figma frame
// "Reminder · system notification", #123; time left after review).
package com.polysocial.model.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.polysocial.R
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter

private const val KEY_EVENT_ID = "eventId"
private const val KEY_TITLE = "title"
private const val KEY_START_MILLIS = "startMillis"
private const val KEY_PLACE = "place"

/** The notification channel of event reminders, which students can turn off in Android settings. */
internal const val REMINDER_CHANNEL_ID = "event_reminders"

/** One notification per event: it is posted under the event ID as its tag, with this ID. */
internal const val REMINDER_NOTIFICATION_ID = 1

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM")

internal fun EventReminder.toWorkData(): Data =
    workDataOf(
        KEY_EVENT_ID to eventId,
        KEY_TITLE to title,
        KEY_START_MILLIS to startTime.toEpochMilli(),
        KEY_PLACE to placeName,
    )

/** The reminder saved in [data], or null if a field is missing. */
internal fun eventReminderFrom(data: Data): EventReminder? {
  val eventId = data.getString(KEY_EVENT_ID) ?: return null
  val title = data.getString(KEY_TITLE) ?: return null
  if (!data.hasKeyWithValueOfType(KEY_START_MILLIS, Long::class.javaObjectType)) return null
  return EventReminder(
      eventId = eventId,
      title = title,
      startTime = Instant.ofEpochMilli(data.getLong(KEY_START_MILLIS, 0)),
      placeName = data.getString(KEY_PLACE),
  )
}

/**
 * Posts an event's reminder: the title says the event starts in 1 h (or the time actually left, see
 * [timeLeft]), and the text gives its day, time and place, as in the Figma frame "Reminder · system
 * notification". Tapping it opens the app.
 *
 * Nothing is posted if the event has already started (the device was off when the reminder was due)
 * or if the student doesn't allow notifications. WorkManager creates this worker with the device's
 * clock; tests pass a fixed one through a worker factory.
 */
class ReminderWorker
@JvmOverloads
constructor(
    context: Context,
    params: WorkerParameters,
    private val clock: Clock = Clock.systemDefaultZone(),
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val reminder = eventReminderFrom(inputData) ?: return Result.failure()
    if (!clock.instant().isBefore(reminder.startTime)) return Result.success()
    if (!canPostNotifications()) return Result.success()
    val notifications = NotificationManagerCompat.from(applicationContext)
    notifications.createNotificationChannel(
        NotificationChannelCompat.Builder(
                REMINDER_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            )
            .setName(applicationContext.getString(R.string.reminder_channel_name))
            .build()
    )
    return try {
      notifications.notify(reminder.eventId, REMINDER_NOTIFICATION_ID, notification(reminder))
      Result.success()
    } catch (_: SecurityException) {
      // The permission was revoked between the check and the post: the student doesn't want it.
      Result.success()
    }
  }

  private fun canPostNotifications(): Boolean =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
          ContextCompat.checkSelfPermission(
              applicationContext,
              Manifest.permission.POST_NOTIFICATIONS,
          ) == PackageManager.PERMISSION_GRANTED

  private fun notification(reminder: EventReminder) =
      NotificationCompat.Builder(applicationContext, REMINDER_CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_notification_reminder)
          .setColor(ContextCompat.getColor(applicationContext, R.color.accent))
          .setContentTitle(title(reminder))
          .setContentText(whenAndWhere(reminder))
          .setContentIntent(openApp())
          .setAutoCancel(true)
          .build()

  private fun title(reminder: EventReminder): String =
      when (val left = timeLeft(reminder.startTime, clock.instant())) {
        is TimeLeft.Minutes ->
            applicationContext.getString(
                R.string.reminder_title_minutes,
                reminder.title,
                left.minutes,
            )
        is TimeLeft.Hours ->
            applicationContext.getString(R.string.reminder_title_hours, reminder.title, left.hours)
      }

  private fun whenAndWhere(reminder: EventReminder): String {
    val start = reminder.startTime.atZone(clock.zone)
    val day =
        when (reminderDay(reminder.startTime, clock.instant(), clock.zone)) {
          ReminderDay.TODAY -> applicationContext.getString(R.string.events_today)
          ReminderDay.TOMORROW -> applicationContext.getString(R.string.events_tomorrow)
          ReminderDay.LATER -> dateFormat.format(start)
        }
    val time = timeFormat.format(start)
    val place = reminder.placeName?.takeIf { it.isNotBlank() }
    return if (place == null) applicationContext.getString(R.string.reminder_when, day, time)
    else applicationContext.getString(R.string.reminder_when_where, day, time, place)
  }

  private fun openApp(): PendingIntent? {
    val intent =
        applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?: return null
    return PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
  }
}
