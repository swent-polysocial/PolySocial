// Contributors: Claude Opus 5.5 (reminder worker and notification, from the Figma frame
// "Reminder · system notification", #123; time left after review; skipping deleted events, #130;
// failed reads documented after review; a per-event PendingIntent instead of a shared one, second
// PR of #123; carrying the event ID for notification-tap navigation, second PR's review).
// Contributors: Claude Opus 5.5 (skip a reminder whose scheduling account no longer matches
// whoever is signed in, so a failed cancelAll() on logout can't notify a different account,
// #123's second PR, Mehdi's review).
// Contributors: Claude Opus 5.5 (recheck the owner after the suspending event lookup, #123's second
// PR, Mehdi's re-review).
// Contributors: Claude Sonnet 5.5 (the worker follows the event's current time, title and place,
// and skips a student who is no longer going, #485).
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
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.registration.MyRegistrationsResult
import com.polysocial.model.registration.RegistrationRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val KEY_EVENT_ID = "eventId"
private const val KEY_TITLE = "title"
private const val KEY_START_MILLIS = "startMillis"
private const val KEY_PLACE = "place"
private const val KEY_UID = "uid"

/**
 * Extra on the launch [android.content.Intent] a reminder notification opens, carrying its event's
 * ID so [com.polysocial.MainActivity] can open that event's detail instead of just the app.
 */
const val NOTIFICATION_EVENT_ID_EXTRA = "com.polysocial.reminder.EVENT_ID"

/** The notification channel of event reminders, which students can turn off in Android settings. */
internal const val REMINDER_CHANNEL_ID = "event_reminders"

/** One notification per event: it is posted under the event ID as its tag, with this ID. */
internal const val REMINDER_NOTIFICATION_ID = 1

/** How long the worker waits for the server's answer about the event before trusting the cache. */
internal val EVENT_CHECK_TIMEOUT = 10.seconds

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM")

internal fun EventReminder.toWorkData(): Data =
    workDataOf(
        KEY_EVENT_ID to eventId,
        KEY_TITLE to title,
        KEY_START_MILLIS to startTime.toEpochMilli(),
        KEY_PLACE to placeName,
        KEY_UID to uid,
    )

/** The reminder saved in [data], or null if a field is missing. */
internal fun eventReminderFrom(data: Data): EventReminder? {
  val eventId = data.getString(KEY_EVENT_ID) ?: return null
  val title = data.getString(KEY_TITLE) ?: return null
  val uid = data.getString(KEY_UID) ?: return null
  if (!data.hasKeyWithValueOfType(KEY_START_MILLIS, Long::class.javaObjectType)) return null
  return EventReminder(
      eventId = eventId,
      title = title,
      startTime = Instant.ofEpochMilli(data.getLong(KEY_START_MILLIS, 0)),
      uid = uid,
      placeName = data.getString(KEY_PLACE),
  )
}

/** What [lookUpEvent] found about an event. */
internal sealed interface EventLookup {
  /** The event is gone: deleted, or no longer readable by the signed-in student. */
  data object Gone : EventLookup

  /** The event as it is now: its current time, title and place. */
  data class Current(val event: Event) : EventLookup

  /** Nothing could tell (offline and not cached, or the read ended), so the reminder stays. */
  data object Unknown : EventLookup
}

/**
 * The event [eventId] as [events] has it now, or [EventLookup.Gone] if it is deleted or no longer
 * readable by the signed-in student (see [EventResult.NotFound]).
 *
 * The server's answer decides. If it doesn't come within [EVENT_CHECK_TIMEOUT] (offline), the
 * latest cached answer does, and an event the cache doesn't hold is [EventLookup.Unknown], so a
 * reminder still fires offline.
 *
 * A failed read counts as gone: the app's repository can't tell a denied read from a failed one, so
 * it reports any read error, and a database blocked by the session guard (#198), as
 * [EventResult.NotFound], and the reminder is skipped. During an account switch, that is what we
 * want. Only a flow that ends without an answer or throws is [EventLookup.Unknown], which
 * `FirestoreEventRepository` never does.
 */
internal suspend fun lookUpEvent(events: EventRepository, eventId: String): EventLookup {
  var latest: EventResult? = null
  val confirmed =
      try {
        withTimeoutOrNull(EVENT_CHECK_TIMEOUT) {
          events.getEvent(eventId).first { result ->
            latest = result
            result is EventResult.NotFound || (result is EventResult.Found && !result.fromCache)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // The flow ended without an answer or threw: keep the reminder (see above).
        null
      }
  return when (val result = confirmed ?: latest) {
    is EventResult.NotFound -> EventLookup.Gone
    is EventResult.Found -> EventLookup.Current(result.event)
    EventResult.NotCached,
    null -> EventLookup.Unknown
  }
}

/**
 * False only if the server says the signed-in student is no longer registered for [eventId] (they
 * cancelled on another device, or an organizer removed them). A cached answer may be out of date,
 * so it never decides: if the server doesn't answer within [EVENT_CHECK_TIMEOUT] (offline), or the
 * read fails, the student is still taken as going and the reminder fires.
 */
internal suspend fun stillGoing(registrations: RegistrationRepository, eventId: String): Boolean {
  val confirmed =
      try {
        withTimeoutOrNull(EVENT_CHECK_TIMEOUT) {
          registrations.observeMyRegistrations().first {
            it is MyRegistrationsResult.Registrations && !it.fromCache
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
  return (confirmed as? MyRegistrationsResult.Registrations)?.let { eventId in it.eventIds } ?: true
}

/**
 * Gives [ReminderWorker], which WorkManager creates itself, the app's [EventRepository],
 * [AuthRepository] (to check who is signed in when it fires), [RegistrationRepository] (to check
 * that the student still goes) and [ReminderScheduler] (to follow an event moved later).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReminderWorkerEntryPoint {
  fun eventRepository(): EventRepository

  fun authRepository(): AuthRepository

  fun registrationRepository(): RegistrationRepository

  fun reminderScheduler(): ReminderScheduler
}

/**
 * Posts an event's reminder: the title says the event starts in 1 h (or the time actually left, see
 * [timeLeft]), and the text gives its day, time and place, as in the Figma frame "Reminder · system
 * notification". Tapping it opens the app.
 *
 * It follows the event as it is when it fires (see [lookUpEvent]): the title, time and place come
 * from the event, not from when the reminder was set, so a change by the organizer shows. An event
 * moved to a time more than [REMINDER_LEAD] away is scheduled again for its new time instead, which
 * replaces this very work: nothing is left to do after that call, and being stopped by it is
 * harmless. An event moved closer is notified right away, with the time actually left.
 *
 * Nothing is posted if the event has already started, if the student doesn't allow notifications,
 * if the event was deleted, or if the server says the student isn't going anymore (see
 * [stillGoing]). WorkManager creates this worker with the device's clock and the app's repositories
 * and scheduler; tests pass a fixed clock and fakes through a worker factory.
 */
class ReminderWorker
@JvmOverloads
constructor(
    context: Context,
    params: WorkerParameters,
    private val events: EventRepository =
        EntryPointAccessors.fromApplication(context, ReminderWorkerEntryPoint::class.java)
            .eventRepository(),
    private val auth: AuthRepository =
        EntryPointAccessors.fromApplication(context, ReminderWorkerEntryPoint::class.java)
            .authRepository(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val registrations: RegistrationRepository =
        EntryPointAccessors.fromApplication(context, ReminderWorkerEntryPoint::class.java)
            .registrationRepository(),
    private val scheduler: ReminderScheduler =
        EntryPointAccessors.fromApplication(context, ReminderWorkerEntryPoint::class.java)
            .reminderScheduler(),
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val saved = eventReminderFrom(inputData) ?: return Result.failure()
    // A failed cancelAll() on logout (best-effort, see ProfileViewModel.confirmLogout) can leave
    // this reminder queued; without this, it would fire for whichever account signs in next.
    if (!isScheduledBySignedInAccount(saved)) return Result.success()
    if (!canPostNotifications()) return Result.success()
    // Both answers are awaited together: offline, each waits out its own timeout.
    val (lookup, going) =
        coroutineScope {
          val event = async { lookUpEvent(events, saved.eventId) }
          val registered = async { stillGoing(registrations, saved.eventId) }
          event.await() to registered.await()
        }
    if (lookup is EventLookup.Gone || !going) return Result.success()
    // The lookup above can suspend for up to EVENT_CHECK_TIMEOUT, long enough for the account to
    // switch, so the owner is checked again right before posting.
    if (!isScheduledBySignedInAccount(saved)) return Result.success()
    val reminder = (lookup as? EventLookup.Current)?.event?.let { saved.followed(it) } ?: saved
    val now = clock.instant()
    if (!now.isBefore(reminder.startTime)) return Result.success()
    if (
        reminder.startTime.isAfter(saved.startTime) && reminderTime(reminder.startTime, now) != null
    ) {
      // Moved later, and the new reminder time hasn't come: wait for it instead of notifying now.
      return when (scheduler.schedule(reminder)) {
        is ScheduleReminderResult.UnexpectedError -> Result.retry()
        else -> Result.success()
      }
    }
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

  /** This reminder with the title, start time and place the organizer has now. */
  private fun EventReminder.followed(event: Event) =
      copy(
          title = event.title,
          startTime = event.startTime,
          placeName = event.locationName.ifBlank { null },
      )

  private fun isScheduledBySignedInAccount(reminder: EventReminder): Boolean =
      auth.currentUser()?.uid == reminder.uid

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
          .setContentIntent(openApp(reminder))
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

  /**
   * Opens the app on [reminder]'s event, carrying its ID as [NOTIFICATION_EVENT_ID_EXTRA] so
   * [com.polysocial.MainActivity] can navigate to its detail. The request code is the event's ID,
   * not a constant: extras don't affect [android.content.Intent.filterEquals], so a shared request
   * code would make Android reuse the first reminder's cached [PendingIntent] (and its extra) for
   * every later one, and every notification would open the first event that posted one (#180
   * review).
   */
  private fun openApp(reminder: EventReminder): PendingIntent? {
    val intent =
        applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.putExtra(NOTIFICATION_EVENT_ID_EXTRA, reminder.eventId) ?: return null
    return PendingIntent.getActivity(
        applicationContext,
        reminder.eventId.hashCode(),
        intent,
        PendingIntent.FLAG_IMMUTABLE,
    )
  }
}
