// Contributors: OpenAI Codex (GPT-6.2 Sol, high; fused location verification for #469 under
// Lucas Pinto Ricardo’s supervision and approval).
package com.polysocial.ui.event.checkin

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.polysocial.emulator.EmulatorTest
import com.polysocial.model.checkin.FakeCheckInRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.AndroidLocationService
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.ui.theme.PolySocialTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@EmulatorTest
@SdkSuppress(minSdkVersion = 34)
class CheckInLocationDeviceTest {
  @get:Rule(order = 0)
  val permission = GrantPermissionRule.grant(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)

  @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Test
  fun injectedFusedFixShowsTooFarThenConfirmsAtTheVenue() {
    val now = Instant.parse("2026-11-25T20:08:00Z")
    val event = validEvent(startTime = now.minusSeconds(480)).copy(id = "venue-test")
    val uid = "test-student"
    val repository =
        FakeCheckInRepository(currentUid = uid, now = now).apply {
          readableEvents += event
          seedRegistration(event.id, uid)
        }
    val entry =
        CheckInEntry(event, CheckInTarget(event, uid), CheckInAvailability.OPEN, ZoneOffset.UTC)
    val vm =
        CheckInViewModel(
            AndroidLocationService(instrumentation.targetContext),
            repository,
            FakeNetworkMonitor(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
    shell("appops set com.android.shell android:mock_location allow")
    try {
      shell("cmd location providers add-test-provider fused")
      try {
        shell("cmd location providers set-test-provider-enabled fused true")
        compose.setContent { PolySocialTheme { CheckInRoute(entry, {}, {}, viewModel = vm) } }
        compose.onNodeWithTag(CheckInTags.LOCATION).assertIsDisplayed()
        assertNull(repository.checkInOf(event.id, uid))

        injectFix(0.0, 0.0)
        compose.onNodeWithTag(CheckInTags.LOCATION).performClick()
        compose.waitUntil(timeoutMillis = 20_000) { vm.state.value == CheckInState.Stage.TOO_FAR }
        compose.onNodeWithText("You’re too far from the venue").assertIsDisplayed()
        assertNull(repository.checkInOf(event.id, uid))

        injectFix(event.location.latitude, event.location.longitude)
        compose.onNodeWithTag(CheckInTags.RETRY).performClick()
        compose.waitUntil(timeoutMillis = 20_000) { vm.state.value is CheckInState.Confirmed }
        compose.onNodeWithText("You’re checked in").assertIsDisplayed()
        compose
            .onNodeWithText("Checked in at 20:08. Your group can see that you’ve arrived.")
            .assertIsDisplayed()
        assertEquals(now, repository.checkInOf(event.id, uid))
      } finally {
        shell("cmd location providers remove-test-provider fused")
      }
    } finally {
      shell("appops set com.android.shell android:mock_location default")
    }
  }

  private fun injectFix(latitude: Double, longitude: Double) {
    shell(
        "cmd location providers set-test-provider-location fused --location $latitude,$longitude --accuracy 5"
    )
  }

  private fun shell(command: String) {
    val (stdout, stdin, stderr) = instrumentation.uiAutomation.executeShellCommandRwe(command)
    stdin.close()
    val out = ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
    val err = ParcelFileDescriptor.AutoCloseInputStream(stderr).use { it.readBytes() }
    assertEquals("Shell command failed: $command", "", (out + err).decodeToString().trim())
  }
}
