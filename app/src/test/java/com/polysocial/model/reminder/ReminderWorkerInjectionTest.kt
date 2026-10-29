// Contributors: Claude Opus 5.5 (wrote this test, #130).
package com.polysocial.model.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@UninstallModules(EventModule::class)
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class ReminderWorkerInjectionTest {
  @BindValue @JvmField val events: EventRepository = FakeEventRepository()

  @get:Rule val hilt = HiltAndroidRule(this)

  @Test
  fun theWorker_readsEventsFromTheAppRepository() {
    hilt.inject()
    val context = ApplicationProvider.getApplicationContext<Context>()

    val repository =
        EntryPointAccessors.fromApplication(context, ReminderWorkerEntryPoint::class.java)
            .eventRepository()

    assertSame(events, repository)
  }

  @Test
  fun workManager_createsTheWorkerWithItsDefaultConstructor() {
    hilt.inject()
    val context = ApplicationProvider.getApplicationContext<Context>()

    // No worker factory: WorkManager's own (Context, WorkerParameters) path, as in the app, which
    // gets the repository through the entry point.
    val worker = TestListenableWorkerBuilder<ReminderWorker>(context).build()

    assertTrue(worker is ReminderWorker)
  }
}
