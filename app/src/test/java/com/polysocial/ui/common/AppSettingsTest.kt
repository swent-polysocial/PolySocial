// Contributors: Claude Opus 5.5 (wrote these tests, #359); Konstantinos Perdikeas (directed,
// reviewed and owns them).
package com.polysocial.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AppSettingsTest {
  @Test
  fun opensThisAppsSettingsPage() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    assertTrue(context.openAppSettings())

    val started = shadowOf(context).nextStartedActivity
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
    assertEquals(Uri.fromParts("package", context.packageName, null), started.data)
    assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test
  fun withoutASettingsApp_returnsFalseInsteadOfCrashing() {
    val context =
        mockk<Context> {
          every { packageName } returns "com.polysocial"
          every { startActivity(any()) } throws ActivityNotFoundException()
        }

    assertFalse(context.openAppSettings())
  }
}
