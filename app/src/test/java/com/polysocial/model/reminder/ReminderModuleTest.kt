// Contributors: Claude Opus 5.5 (wrote this test, #123).
package com.polysocial.model.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.Clock
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderModuleTest {

  @Test
  fun theApp_schedulesRemindersWithWorkManager() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )

    val scheduler = ReminderModule.reminderScheduler(context, Clock.systemUTC())

    assertTrue(scheduler is WorkManagerReminderScheduler)
  }
}
