// Contributors: Claude Opus 5.5 (wrote these tests, #123).
package com.polysocial.model.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkManagerReminderSchedulerTest {

  private val now = Instant.parse("2026-10-03T12:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private lateinit var workManager: WorkManager
  private lateinit var scheduler: WorkManagerReminderScheduler

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
    workManager = WorkManager.getInstance(context)
    scheduler = WorkManagerReminderScheduler(workManager, clock)
  }

  private fun reminder(eventId: String, startsInHours: Long) =
      EventReminder(eventId, "Study session", now.plusSeconds(startsInHours * 3600), "BC 410")

  /** The reminders of [eventId] that are still waiting to fire. */
  private fun pending(eventId: String) =
      workManager.getWorkInfosForUniqueWork(reminderWorkName(eventId)).get().filter {
        it.state == WorkInfo.State.ENQUEUED
      }

  @Test
  fun scheduling_waitsUntilOneHourBeforeTheStart() = runTest {
    val result = scheduler.schedule(reminder("e1", startsInHours = 3))

    assertEquals(ScheduleReminderResult.Scheduled(now.plusSeconds(2 * 3600)), result)
    val work = pending("e1").single()
    assertEquals(TimeUnit.HOURS.toMillis(2), work.initialDelayMillis)
    assertTrue(REMINDER_WORK_TAG in work.tags)
  }

  @Test
  fun schedulingAgain_replacesTheEventsReminder() = runTest {
    scheduler.schedule(reminder("e1", startsInHours = 3))
    scheduler.schedule(reminder("e1", startsInHours = 5))

    assertEquals(TimeUnit.HOURS.toMillis(4), pending("e1").single().initialDelayMillis)
  }

  @Test
  fun anEventStartingWithinTheHour_isNotScheduled() = runTest {
    val result = scheduler.schedule(reminder("e1", startsInHours = 1))

    assertEquals(ScheduleReminderResult.TooLate, result)
    assertTrue(workManager.getWorkInfosByTag(REMINDER_WORK_TAG).get().isEmpty())
  }

  @Test
  fun cancelling_removesOnlyThatEventsReminder() = runTest {
    scheduler.schedule(reminder("e1", startsInHours = 3))
    scheduler.schedule(reminder("e2", startsInHours = 3))

    assertEquals(CancelReminderResult.Cancelled, scheduler.cancel("e1"))

    assertTrue(pending("e1").isEmpty())
    assertEquals(1, pending("e2").size)
  }

  @Test
  fun cancellingAnEventWithoutAReminder_succeeds() = runTest {
    assertEquals(CancelReminderResult.Cancelled, scheduler.cancel("none"))
  }

  @Test
  fun cancellingAll_removesEveryReminder() = runTest {
    scheduler.schedule(reminder("e1", startsInHours = 3))
    scheduler.schedule(reminder("e2", startsInHours = 4))

    assertEquals(CancelReminderResult.Cancelled, scheduler.cancelAll())

    assertTrue(pending("e1").isEmpty())
    assertTrue(pending("e2").isEmpty())
  }

  @Test
  fun aWorkManagerFailure_isReportedInsteadOfThrown() = runTest {
    val broken = mockk<WorkManager>()
    every { broken.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) } throws
        IllegalStateException("database unavailable")
    every { broken.cancelUniqueWork(any()) } throws IllegalStateException("database unavailable")
    every { broken.cancelAllWorkByTag(any()) } throws IllegalStateException("database unavailable")
    val failing = WorkManagerReminderScheduler(broken, clock)

    assertEquals(
        ScheduleReminderResult.UnexpectedError,
        failing.schedule(reminder("e1", startsInHours = 3)),
    )
    assertEquals(CancelReminderResult.UnexpectedError, failing.cancel("e1"))
    assertEquals(CancelReminderResult.UnexpectedError, failing.cancelAll())
  }
}
