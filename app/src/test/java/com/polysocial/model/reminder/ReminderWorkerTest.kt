// Contributors: Claude Opus 5.5 (wrote these tests, #123; late reminder title after review;
// deleted events, #130; failed reads after review; distinct PendingIntents per event, second PR
// of #123; the PendingIntent's event-id extra, #123's second PR review).
// Contributors: Claude Opus 5.5 (a reminder only fires for the account that scheduled it, #123's
// second PR, Mehdi's review).
// Contributors: Claude Opus 5.5 (an account switch during the event lookup, #123's second PR,
// Mehdi's re-review).
// Contributors: Claude Sonnet 5.5 (the worker follows an event moved or edited, and skips a student
// who is no longer going, #485).
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
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.registration.RegistrationRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
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

  private companion object {
    const val STUDENT = "student-1"
  }

  // The Figma frame: at 19:00, an event starting today at 20:00 (Zurich time).
  private val reminderDue = Instant.parse("2026-10-03T17:00:00Z")
  private val balelec =
      EventReminder(
          "e1",
          "Balélec warm-up",
          Instant.parse("2026-10-03T18:00:00Z"),
          STUDENT,
          "Satellite, EPFL",
      )

  private val events = FakeEventRepository()
  private val registrations =
      FakeRegistrationRepository(currentUid = STUDENT).apply {
        readableEventIds += listOf("e1", "e2")
        seedRegistration("e1", STUDENT)
        seedRegistration("e2", STUDENT)
      }
  private val scheduler =
      FakeReminderScheduler(Clock.fixed(reminderDue, ZoneId.of("Europe/Zurich")))
  private val auth = FakeAuthRepository(AuthUser(STUDENT, "student@example.com", true))

  private fun stored(id: String) =
      Event(
          id = id,
          title = balelec.title,
          description = "",
          category = EventCategory.PARTY,
          location = Coordinates(46.52, 6.57),
          startTime = balelec.startTime,
          locationName = balelec.placeName.orEmpty(),
          isPrivate = false,
      )

  /** [events], except that [getEvent] returns [reads]. */
  private fun reading(reads: Flow<EventResult>): EventRepository =
      object : EventRepository by events {
        override fun getEvent(id: String) = reads
      }

  private val notifications
    get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

  @Before
  fun allowNotificationsAndStoreTheEvents() {
    shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    events.seed(stored("e1"), stored("e2"))
  }

  private suspend fun run(
      data: Data,
      at: Instant = reminderDue,
      repository: EventRepository = events,
      signedInAs: AuthRepository = auth,
      registered: RegistrationRepository = registrations,
      reminders: ReminderScheduler = scheduler,
  ): ListenableWorker.Result {
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
                  ) =
                      ReminderWorker(
                          appContext,
                          workerParameters,
                          repository,
                          signedInAs,
                          clock,
                          registered,
                          reminders,
                      )
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
    // The event is what the notification follows: one without a place name, one with a blank one.
    events.events.clear()
    events.seed(stored("e1").copy(locationName = ""), stored("e2").copy(locationName = " "))

    run(balelec.copy(placeName = null).toWorkData())
    run(balelec.copy(eventId = "e2", placeName = " ").toWorkData())

    assertEquals(listOf("Today 20:00", "Today 20:00"), notifications.map { it.text() })
  }

  @Test
  fun anEventJustAfterMidnight_saysTomorrow() = runTest {
    // 00:30 on the 4th in Zurich, reminded at 23:30 on the 3rd.
    val lateNight = balelec.copy(startTime = Instant.parse("2026-10-03T22:30:00Z"))
    edited { it.copy(startTime = lateNight.startTime) }

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
  fun differentEvents_getDifferentPendingIntents() = runTest {
    // Extras don't affect Intent.filterEquals, so a shared request code would make Android reuse
    // the first reminder's cached PendingIntent for every later one (#180 review).
    run(balelec.toWorkData())
    run(balelec.copy(eventId = "e2").toWorkData())

    val (first, second) = notifications.map { it.contentIntent }
    assertTrue(first != second)
    assertEquals("e1".hashCode(), shadowOf(first).requestCode)
    assertEquals("e2".hashCode(), shadowOf(second).requestCode)
  }

  @Test
  fun thePendingIntent_carriesTheEventIdForNavigation() = runTest {
    run(balelec.toWorkData())

    val savedIntent = shadowOf(notifications.single().contentIntent).savedIntent
    assertEquals("e1", savedIntent.getStringExtra(NOTIFICATION_EVENT_ID_EXTRA))
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
  fun aDeletedEvent_isNotNotified() = runTest {
    events.events.clear()

    assertEquals(ListenableWorker.Result.success(), run(balelec.toWorkData()))
    assertTrue(notifications.isEmpty())
  }

  @Test
  fun anEventTheStudentCanNoLongerRead_isNotNotified() = runTest {
    // For example a private event they were removed from: the read is denied.
    events.deniedIds += "e1"

    run(balelec.toWorkData())

    assertTrue(notifications.isEmpty())
  }

  @Test
  fun anEventTheServerDeletedAfterTheCachedCopy_isNotNotified() = runTest {
    val reads = flowOf(EventResult.Found(stored("e1"), fromCache = true), EventResult.NotFound)

    run(balelec.toWorkData(), repository = reading(reads))

    assertTrue(notifications.isEmpty())
  }

  @Test
  fun offline_aCachedEventIsNotifiedAfterTheTimeout() = runTest {
    events.readFromCache = true

    run(balelec.toWorkData())

    assertEquals(EVENT_CHECK_TIMEOUT.inWholeMilliseconds, currentTime)
    assertEquals("Balélec warm-up starts in 1 h", notifications.single().title())
  }

  @Test
  fun offline_anEventTheCacheDoesNotHold_isStillNotified() = runTest {
    // The reminder was scheduled from a read the cache has since dropped: it must not be lost.
    events.readFromCache = true
    events.events.clear()

    run(balelec.toWorkData())

    assertEquals(1, notifications.size)
  }

  @Test
  fun theServerConfirmingTheEvent_notifiesWithoutWaiting() = runTest {
    run(balelec.toWorkData())

    assertEquals(0L, currentTime)
    assertEquals(1, notifications.size)
  }

  @Test
  fun aRepositoryThatThrowsOrEndsWithoutAnswer_stillNotifies() = runTest {
    // FirestoreEventRepository reports a failed read as NotFound instead (see lookUpEvent).
    val failing = flow<EventResult> { throw IllegalStateException("read failed") }
    run(balelec.toWorkData(), repository = reading(failing))
    run(balelec.copy(eventId = "e2").toWorkData(), repository = reading(emptyFlow()))

    assertEquals(2, notifications.size)
  }

  /** [events] with e1 as the organizer has changed it: [change] applied to the stored event. */
  private fun edited(change: (Event) -> Event) {
    events.events.clear()
    events.seed(change(stored("e1")), stored("e2"))
  }

  @Test
  fun anEventMovedLater_isScheduledAgainForItsNewTime_notNotifiedNow() = runTest {
    val newStart = Instant.parse("2026-10-03T21:00:00Z")
    edited { it.copy(startTime = newStart) }

    val result = run(balelec.toWorkData())

    assertEquals(ListenableWorker.Result.success(), result)
    assertTrue(notifications.isEmpty())
    assertEquals(balelec.copy(startTime = newStart), scheduler.scheduled["e1"])
  }

  @Test
  fun anEventMovedLaterButWithinTheHour_isNotifiedNowWithTheTimeLeft() = runTest {
    edited { it.copy(startTime = Instant.parse("2026-10-03T17:40:00Z")) }

    run(balelec.toWorkData())

    assertEquals("Balélec warm-up starts in 40 min", notifications.single().title())
    assertTrue(scheduler.scheduled.isEmpty())
  }

  @Test
  fun anEventMovedEarlier_isNotifiedWithItsNewTime() = runTest {
    edited { it.copy(startTime = Instant.parse("2026-10-03T17:30:00Z")) }

    run(balelec.toWorkData())

    val notification = notifications.single()
    assertEquals("Balélec warm-up starts in 30 min", notification.title())
    assertEquals("Today 19:30 · Satellite, EPFL", notification.text())
    assertTrue(scheduler.scheduled.isEmpty())
  }

  @Test
  fun anEventMovedToThePast_isNotNotified() = runTest {
    edited { it.copy(startTime = Instant.parse("2026-10-03T16:00:00Z")) }

    assertEquals(ListenableWorker.Result.success(), run(balelec.toWorkData()))
    assertTrue(notifications.isEmpty())
    assertTrue(scheduler.scheduled.isEmpty())
  }

  @Test
  fun aRenamedEventWithANewPlace_isNotifiedWithTheCurrentTitleAndPlace() = runTest {
    edited { it.copy(title = "Balélec", locationName = "Rolex Learning Center") }

    run(balelec.toWorkData())

    val notification = notifications.single()
    assertEquals("Balélec starts in 1 h", notification.title())
    assertEquals("Today 20:00 · Rolex Learning Center", notification.text())
  }

  @Test
  fun aSchedulerThatFailsWhileFollowingAMovedEvent_retriesLater() = runTest {
    edited { it.copy(startTime = Instant.parse("2026-10-03T21:00:00Z")) }
    scheduler.fail = true

    assertEquals(ListenableWorker.Result.retry(), run(balelec.toWorkData()))
    assertTrue(notifications.isEmpty())
  }

  @Test
  fun aStudentTheServerSaysIsNoLongerGoing_isNotNotified() = runTest {
    registrations.cancel("e1")

    assertEquals(ListenableWorker.Result.success(), run(balelec.toWorkData()))
    assertTrue(notifications.isEmpty())
  }

  @Test
  fun offline_aCachedAbsenceDoesNotDecide_theStudentIsStillNotified() = runTest {
    // The registrations may have been joined on another device: only the server decides.
    registrations.cancel("e1")
    registrations.readFromCache = true

    run(balelec.toWorkData())

    assertEquals(EVENT_CHECK_TIMEOUT.inWholeMilliseconds, currentTime)
    assertEquals(1, notifications.size)
  }

  @Test
  fun registrationsThatCantBeRead_stillNotify() = runTest {
    registrations.readFailure = true

    run(balelec.toWorkData())

    assertEquals(1, notifications.size)
  }

  @Test
  fun aWorkerStoppedWhileCheckingTheEvent_staysCancelledAndPostsNothing() = runTest {
    // For example WorkManager stops the work because its constraints no longer hold.
    val neverAnswers = flow<EventResult> { awaitCancellation() }
    val work = launch { run(balelec.toWorkData(), repository = reading(neverAnswers)) }
    advanceTimeBy(1_000)
    runCurrent()

    work.cancel()
    work.join()

    assertTrue(work.isCancelled)
    assertTrue(notifications.isEmpty())
  }

  @Test
  fun aReminderMissingAField_fails() = runTest {
    assertEquals(ListenableWorker.Result.failure(), run(Data.EMPTY))
    assertTrue(notifications.isEmpty())
  }

  @Test
  fun aDifferentSignedInAccount_isNotNotified() = runTest {
    // For example a failed cancelAll() on logout (best-effort) leaves this queued, and a
    // different account signs in before it fires.
    val other = FakeAuthRepository(AuthUser("other-student", "other@example.com", true))

    assertEquals(
        ListenableWorker.Result.success(),
        run(balelec.toWorkData(), signedInAs = other),
    )
    assertTrue(notifications.isEmpty())
  }

  @Test
  fun anAccountSwitchDuringTheEventLookup_isNotNotified() = runTest {
    // Passes the first owner check as the student, then the account changes while the (up to 10 s)
    // lookup is suspended: nothing may be posted for the new account.
    val release = CompletableDeferred<Unit>()
    val gated =
        object : EventRepository by events {
          override fun getEvent(id: String) = flow {
            release.await()
            emit(EventResult.Found(stored(id), fromCache = false))
          }
        }
    val work = async { run(balelec.toWorkData(), repository = gated) }
    runCurrent()

    auth.user = AuthUser("other-student", "other@example.com", true)
    release.complete(Unit)

    assertEquals(ListenableWorker.Result.success(), work.await())
    assertTrue(notifications.isEmpty())
  }

  @Test
  fun noOneSignedIn_isNotNotified() = runTest {
    assertEquals(
        ListenableWorker.Result.success(),
        run(balelec.toWorkData(), signedInAs = FakeAuthRepository(user = null)),
    )
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
