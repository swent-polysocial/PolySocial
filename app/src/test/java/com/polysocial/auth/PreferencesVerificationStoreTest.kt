// Contributors: OpenAI Codex (persisted resend timing and account-isolation tests for #31).
// Contributors: Claude (Opus 5.5, Anthropic) tested the address-keyed cooldown, #225; Mohamed
// Khellaf (reviewed and revised).
package com.polysocial.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.polysocial.model.auth.PreferencesVerificationStore
import com.polysocial.model.auth.VerificationTiming
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class PreferencesVerificationStoreTest {
  @Test
  fun newInstanceRestoresDeadlineAndClearingOnlyRemovesItsAccount() = runTest {
    val context = ApplicationProvider.getApplicationContext<Application>()
    context.getSharedPreferences("verification_timing", 0).edit().clear().commit()
    val original = PreferencesVerificationStore(context)
    assertEquals(VerificationTiming(), original.read("test-uid"))
    original.write("test-uid", VerificationTiming(1000, 46_000))
    original.write("other-uid", VerificationTiming(5000, 50_000))
    val restarted = PreferencesVerificationStore(context)
    assertEquals(VerificationTiming(1000, 46_000), restarted.read("test-uid"))
    assertEquals(VerificationTiming(5000, 50_000), restarted.read("other-uid"))
    restarted.clear("test-uid")
    assertEquals(VerificationTiming(), original.read("test-uid"))
    assertEquals(VerificationTiming(5000, 50_000), original.read("other-uid"))
  }

  @Test
  fun addressDeadlineOutlivesTheAccountAndIsStoredOnlyAsADigest() = runTest {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val preferences = context.getSharedPreferences("verification_timing", 0)
    preferences.edit().clear().commit()
    val store = PreferencesVerificationStore(context)
    store.write("test-uid", VerificationTiming(1000, 46_000))
    store.writeAddress("student.test@epfl.ch", VerificationTiming(1000, 46_000))
    store.clear("test-uid")
    val restarted = PreferencesVerificationStore(context)
    assertEquals(VerificationTiming(), restarted.read("test-uid"))
    // Trimmed and case-insensitive, so the same mailbox typed differently keeps its cooldown.
    assertEquals(
        VerificationTiming(1000, 46_000),
        restarted.readAddress("  Student.Test@EPFL.ch "),
    )
    assertEquals(VerificationTiming(), restarted.readAddress("other.test@epfl.ch"))
    assertEquals(VerificationTiming(), restarted.read("student.test@epfl.ch"))
    val keys = preferences.all.keys
    assertTrue(keys.isNotEmpty())
    assertFalse(keys.any { it.contains("student", ignoreCase = true) || it.contains("@") })

    restarted.clearAddress("STUDENT.test@epfl.ch")
    assertEquals(VerificationTiming(), store.readAddress("student.test@epfl.ch"))
    assertTrue(preferences.all.isEmpty())
  }
}
