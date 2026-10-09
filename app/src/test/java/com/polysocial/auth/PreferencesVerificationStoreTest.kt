// Contributors: OpenAI Codex (persisted resend timing and account-isolation tests for #31).
package com.polysocial.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.polysocial.model.auth.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
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
}
