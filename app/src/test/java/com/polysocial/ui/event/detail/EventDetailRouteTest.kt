// Contributors: Claude Sonnet 5 (wrote these tests of the reminder permission flow on
// EventDetailRoute, which only EventDetailViewModel drives EventDetailScreen can't reach, after a
// SonarCloud new-code-coverage gap on the second PR of #123).
package com.polysocial.ui.event.detail

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The reminder permission flow that only [EventDetailRoute] itself drives (second PR of #123, like
 * [com.polysocial.ui.map.MapRouteTest] for location): the fast path when notifications are already
 * granted, the explanation sheet and system prompt otherwise, "Open settings", and rechecking the
 * grant on resume. [EventDetailReminderScreenTest] covers the row and sheet from state alone.
 */
@RunWith(AndroidJUnit4::class)
class EventDetailRouteTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)
  private val events = FakeEventRepository()
  private val registrations =
      FakeRegistrationRepository(currentUid = STUDENT).apply {
        readableEventIds += EVENT_ID
        seedRegistration(EVENT_ID, STUDENT)
      }
  private val auth = FakeAuthRepository(AuthUser(STUDENT, "student@example.com", true))
  private val reminders = FakeReminderScheduler(clock)
  private val lifecycle = ControlledLifecycleOwner()
  private val registry = RecordingPermissionRegistry()
  private val application = ApplicationProvider.getApplicationContext<Application>()

  private val event =
      validEvent(startTime = Instant.parse("2026-10-10T13:00:00Z"))
          .copy(id = EVENT_ID, createdBy = "organizer", organizerIds = listOf("organizer"))

  private fun show() {
    events.seed(event)
    val viewModel =
        EventDetailViewModel(
            SavedStateHandle(mapOf("eventId" to EVENT_ID)),
            events,
            object : NetworkMonitor {
              override fun isOnline() = true
            },
            auth,
            clock,
            FakeAssociationNames(),
            registrations,
            FakeGroupRepository(),
            FakePublicProfileRepository(),
            reminders,
        )
    lifecycle.registry.currentState = Lifecycle.State.RESUMED
    val registryOwner =
        object : ActivityResultRegistryOwner {
          override val activityResultRegistry: ActivityResultRegistry = registry
        }
    composeTestRule.setContent {
      CompositionLocalProvider(
          LocalActivityResultRegistryOwner provides registryOwner,
          LocalLifecycleOwner provides lifecycle,
      ) {
        PolySocialTheme { EventDetailRoute(onBack = {}, viewModel = viewModel) }
      }
    }
    settle()
  }

  private fun settle() {
    repeat(2) {
      composeTestRule.waitForIdle()
      mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }
    composeTestRule.waitForIdle()
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  private fun moveTo(state: Lifecycle.State) {
    composeTestRule.runOnIdle { lifecycle.registry.currentState = state }
    settle()
  }

  @Test
  fun permissionAlreadyGranted_turningOnSchedulesDirectly_withNoSheetOrPrompt() {
    shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    show()

    node(EventDetailTags.REMINDER_SWITCH).performClick()
    settle()

    node(EventDetailTags.REMINDER_SWITCH).assertIsOn()
    node(EventDetailTags.REMINDER_EXPLANATION_SHEET).assertDoesNotExist()
    assertTrue(registry.launches.isEmpty())
  }

  @Test
  fun permissionNotGranted_turningOnShowsTheExplanationSheetFirst_withoutSchedulingYet() {
    shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    show()

    node(EventDetailTags.REMINDER_SWITCH).performClick()
    settle()

    node(EventDetailTags.REMINDER_EXPLANATION_SHEET).assertIsDisplayed()
    node(EventDetailTags.REMINDER_SWITCH).assertIsOff()
    assertTrue(registry.launches.isEmpty())
  }

  @Test
  fun continuingPastTheSheet_launchesTheSystemPromptOnce_andAGrantTurnsTheReminderOn() {
    shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    show()
    node(EventDetailTags.REMINDER_SWITCH).performClick()
    settle()

    node(EventDetailTags.REMINDER_EXPLANATION_CONTINUE).performClick()
    settle()

    assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), registry.launches)
    composeTestRule.runOnIdle { registry.respond(granted = true) }
    settle()

    node(EventDetailTags.REMINDER_SWITCH).assertIsOn()
    node(EventDetailTags.REMINDER_DENIED_NOTE).assertDoesNotExist()
  }

  @Test
  fun aDeniedPrompt_showsTheDeniedNote_whoseOpenSettingsOpensTheAppsSettings() {
    shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    show()
    node(EventDetailTags.REMINDER_SWITCH).performClick()
    settle()
    node(EventDetailTags.REMINDER_EXPLANATION_CONTINUE).performClick()
    settle()
    composeTestRule.runOnIdle { registry.respond(granted = false) }
    settle()

    node(EventDetailTags.REMINDER_DENIED_NOTE).assertIsDisplayed()
    node(EventDetailTags.REMINDER_OPEN_SETTINGS).performClick()
    settle()

    val intent = shadowOf(application).nextStartedActivity
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
    assertEquals("package:${application.packageName}", intent.data.toString())
  }

  @Test
  fun resumingOnceGrantedElsewhere_clearsTheBlockedStateWithoutAnotherPrompt() {
    shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    show()
    node(EventDetailTags.REMINDER_SWITCH).performClick()
    settle()
    node(EventDetailTags.REMINDER_EXPLANATION_CONTINUE).performClick()
    settle()
    composeTestRule.runOnIdle { registry.respond(granted = false) }
    settle()
    node(EventDetailTags.REMINDER_DENIED_NOTE).assertIsDisplayed()

    // The student granted it from the app's settings page; the screen rechecks on resume.
    shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    moveTo(Lifecycle.State.STARTED)
    moveTo(Lifecycle.State.RESUMED)

    node(EventDetailTags.REMINDER_DENIED_NOTE).assertDoesNotExist()
    node(EventDetailTags.REMINDER_SWITCH).assertIsOn()
    // Rechecked itself, rather than asking the system again.
    assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), registry.launches)
  }

  private class ControlledLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle = registry
  }

  private class RecordingPermissionRegistry : ActivityResultRegistry() {
    val launches = mutableListOf<String>()
    private var requestCode: Int? = null

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
      check(contract is ActivityResultContracts.RequestPermission)
      launches += input as String
      this.requestCode = requestCode
    }

    fun respond(granted: Boolean) {
      val code = checkNotNull(requestCode) { "No permission request was launched." }
      dispatchResult(code, granted)
    }
  }

  private companion object {
    const val EVENT_ID = "event-1"
    const val STUDENT = "student-1"
  }
}
