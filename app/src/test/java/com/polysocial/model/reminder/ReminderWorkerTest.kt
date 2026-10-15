// Contributors: Claude Opus 5.5 (wrote these tests, #123; late reminder title after review).
package com.polysocial.model.reminder

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ReminderWorkerTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val zurich = ZoneId.of("Europe/Zurich")

  // The Figma frame: at 19:00, an event starting today at 20:00 (Zurich time).
  private val reminderDue = Instant.parse("2026-10-03T17:00:00Z")
  private val balelec =
      EventReminder(
          "e1",
          "Balélec warm-up",
          Instant.parse("2026-10-03T18:00:00Z"),
          "Satellite, EPFL",
      )

  private val notifications
    get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

  @Before
  fun allowNotifications() {
    shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
  }

  private suspend fun run(data: Data, at: Instant = reminderDue): ListenableWorker.Result {
    val clock = Clock.fixed(at, zurich)
    val worker =
        TestListenableWorkerBuilder<ReminderWorker>(context)
            .setInputData(data)
            .setWorkerFactory(
                object : WorkerFactory() {
                  override fun createWorker(
                      appContext: Context,
                      workerClassName: String,
                      workerParameters: WorkerParameters,
                  ) = ReminderWorker(appContext, workerParameters, clock)
                }
            )
            .build()
    return worker.doWork()
  }

  private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

  private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

  @Test
  fun theReminder_showsTheFigmaTitleAndText() = runTest {
    assertEquals(ListenableWorker.Result.success(), run(balelec.toWorkData()))

    val notification = notifications.single()
    assertEquals("Balélec warm-up starts in 1 h", notification.title())
    assertEquals("Today 20:00 · Satellite, EPFL", notification.text())
    assertEquals(REMINDER_CHANNEL_ID, notification.channelId)
    assertNotNull(notification.contentIntent)
    assertNotNull(
        context
            .getSystemService(NotificationManager::class.java)
            .getNotificationChannel(REMINDER_CHANNEL_ID)
    )
  }

  @Test
  fun anEventWithoutAPlace_showsOnlyItsDayAndTime() = runTest {
    run(balelec.copy(placeName = null).toWorkData())
    run(balelec.copy(eventId = "e2", placeName = " ").toWorkData())

    assertEquals(listOf("Today 20:00", "Today 20:00"), notifications.map { it.text() })
  }

  @Test
  fun anEventJustAfterMidnight_saysTomorrow() = runTest {
    // 00:30 on the 4th in Zurich, reminded at 23:30 on the 3rd.
    val lateNight = balelec.copy(startTime = Instant.parse("2026-10-03T22:30:00Z"))

    run(lateNight.toWorkData(), at = Instant.parse("2026-10-03T21:30:00Z"))

    assertEquals("Tomorrow 00:30 · Satellite, EPFL", notifications.single().text())
  }

  @Test
  fun aReminderRunningLate_saysTheMinutesLeft() = runTest {
    // WorkManager's delay is a minimum: the system may run the reminder later than 19:00.
    run(balelec.toWorkData(), at = Instant.parse("2026-10-03T17:50:00Z"))

    val notification = notifications.single()
    assertEquals("Balélec warm-up starts in 10 min", notification.title())
    assertEquals("Today 20:00 · Satellite, EPFL", notification.text())
  }

  @Test
  fun anEventOnALaterDay_showsItsDate() = runTest {
    // Only if the device's clock moved back after the reminder was scheduled.
    run(balelec.toWorkData(), at = Instant.parse("2026-10-01T17:00:00Z"))

    val notification = notifications.single()
    assertEquals("Balélec warm-up starts in 49 h", notification.title())
    assertEquals("Sat 3 Oct 20:00 · Satellite, EPFL", notification.text())
  }

  @Test
  fun eachEvent_hasOneNotification() = runTest {
    run(balelec.toWorkData())
    run(balelec.toWorkData())
    run(balelec.copy(eventId = "e2").toWorkData())

    assertEquals(2, notifications.size)
  }

  @Test
  fun anEventThatHasStarted_isNotNotified() = runTest {
    // The device was off when the reminder was due, and the event has started since.
    val result = run(balelec.toWorkData(), at = balelec.startTime)

    assertEquals(ListenableWorker.Result.success(), result)
    assertTrue(notifications.isEmpty())
  }

  @Test
  fun withoutTheNotificationPermission_nothingIsPosted() = runTest {
    shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    assertEquals(ListenableWorker.Result.success(), run(balelec.toWorkData()))
    assertTrue(notifications.isEmpty())
  }

  @Test
  fun aReminderMissingAField_fails() = runTest {
    assertEquals(ListenableWorker.Result.failure(), run(Data.EMPTY))
    assertTrue(notifications.isEmpty())
  }

  @Test
  fun theWorkData_keepsEveryField() {
    assertEquals(balelec, eventReminderFrom(balelec.toWorkData()))
    assertEquals(
        balelec.copy(placeName = null),
        eventReminderFrom(balelec.copy(placeName = null).toWorkData()),
    )
    val noStart =
        Data.Builder().putString("eventId", "e1").putString("title", "Balélec warm-up").build()
    assertNull(eventReminderFrom(noStart))
    assertNull(eventReminderFrom(Data.Builder().putString("eventId", "e1").build()))
  }
}
