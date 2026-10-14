// Contributors: Claude Opus 5.5 (Hilt binding for reminders, #123).
package com.polysocial.model.reminder

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock

/** Tells Hilt to schedule reminders with WorkManager wherever a [ReminderScheduler] is needed. */
@Module
@InstallIn(SingletonComponent::class)
object ReminderModule {
  @Provides
  fun reminderScheduler(@ApplicationContext context: Context, clock: Clock): ReminderScheduler =
      WorkManagerReminderScheduler(WorkManager.getInstance(context), clock)
}
