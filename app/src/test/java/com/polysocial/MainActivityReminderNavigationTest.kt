// Contributors: Claude Opus 5.5 (tests for reading a reminder notification's event ID on a cold
// and a warm launch, #123's second PR review).
package com.polysocial

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.reminder.NOTIFICATION_EVENT_ID_EXTRA
import com.polysocial.utils.FirebaseStartupRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MainActivity] reads a reminder notification's event ID from its launch [Intent] (cold launch)
 * and from [MainActivity.onNewIntent] (a later tap while already open, `singleTop` in the
 * manifest), so [com.polysocial.ui.navigation.AppShell] can navigate to it once signed in.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityReminderNavigationTest {
  @get:Rule val firebaseStartupRule = FirebaseStartupRule()

  private fun intentFor(eventId: String?): Intent {
    val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
    eventId?.let { intent.putExtra(NOTIFICATION_EVENT_ID_EXTRA, it) }
    return intent
  }

  @Test
  fun anOrdinaryLaunch_hasNoPendingEvent() {
    ActivityScenario.launch<MainActivity>(intentFor(null)).use { scenario ->
      scenario.onActivity { assertNull(it.pendingReminderEventId) }
    }
  }

  @Test
  fun aColdLaunchFromANotification_readsItsEventId() {
    ActivityScenario.launch<MainActivity>(intentFor("event-1")).use { scenario ->
      scenario.onActivity { assertEquals("event-1", it.pendingReminderEventId) }
    }
  }

  @Test
  fun aLaterNotificationTap_readsItsEventIdThroughOnNewIntent() {
    ActivityScenario.launch<MainActivity>(intentFor(null)).use { scenario ->
      scenario.onActivity { it.onNewIntent(intentFor("event-2")) }
      scenario.onActivity { assertEquals("event-2", it.pendingReminderEventId) }
    }
  }

  @Test
  fun anOrdinaryResume_viaOnNewIntent_leavesAnEarlierPendingEventAlone() {
    ActivityScenario.launch<MainActivity>(intentFor("event-1")).use { scenario ->
      scenario.onActivity { assertEquals("event-1", it.pendingReminderEventId) }

      // The launcher icon, or any intent without the extra, must not clear it: only
      // onPendingReminderEventIdConsumed (AuthFlow, once navigated) does.
      scenario.onActivity { it.onNewIntent(intentFor(null)) }
      scenario.onActivity { assertEquals("event-1", it.pendingReminderEventId) }
    }
  }

  @Test
  fun twoNotificationsInARow_eachReplaceThePendingEvent() {
    ActivityScenario.launch<MainActivity>(intentFor("event-1")).use { scenario ->
      scenario.onActivity { assertEquals("event-1", it.pendingReminderEventId) }

      scenario.onActivity { it.onNewIntent(intentFor("event-2")) }
      scenario.onActivity { assertEquals("event-2", it.pendingReminderEventId) }
    }
  }
}
