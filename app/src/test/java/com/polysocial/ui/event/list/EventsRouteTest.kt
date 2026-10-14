// Contributors: Claude (the distance filter's location permission on the Events tab, #115).
package com.polysocial.ui.event.list

import android.Manifest
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.location.LocationResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class EventsRouteTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val repository = FakeEventRepository()
  private val tomorrow = repository.now.plusSeconds(24 * 3600)

  /** At the Rolex Learning Center, like the student below. */
  private val onCampus =
      validEvent(title = "Climbing session", startTime = tomorrow)
          .copy(id = "onCampus", category = EventCategory.SPORTS)

  /** At the Flon, about 4.8 km away. */
  private val inTown =
      validEvent(title = "Flon apéro", startTime = tomorrow.plusSeconds(3600))
          .copy(
              id = "inTown",
              category = EventCategory.PARTY,
              location = Coordinates(latitude = 46.5210, longitude = 6.6290),
          )

  private val location = FakeLocationService(permissionGranted = false)
  private val lifecycle = ControlledLifecycleOwner()
  private val registry = RecordingPermissionRegistry()
  private var showRationale = false
  private lateinit var viewModel: EventsViewModel

  private fun show() {
    repository.seed(onCampus, inTown)
    viewModel =
        EventsViewModel(
            repository,
            object : NetworkMonitor {
              override fun isOnline() = true
            },
            location,
            Clock.fixed(repository.now, ZoneId.of("Europe/Zurich")),
            SavedStateHandle(),
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
        PolySocialTheme { EventsRoute(viewModel = viewModel, showRationale = { showRationale }) }
      }
    }
    settle()
    composeTestRule.onNodeWithTag(EventsTags.FILTERS_BUTTON).performClick()
  }

  private fun settle() {
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeTestRule.waitForIdle()
  }

  private fun sheetChip(key: String) = composeTestRule.onNodeWithTag(EventsTags.sheetChip(key))

  @Test
  fun aDeniedPermission_disablesTheDistanceFilter_andTheOtherFiltersStillWork() {
    show()

    composeTestRule.onNodeWithTag(EventsTags.LOCATION_OFF).assertIsDisplayed()
    sheetChip("DISTANCE_ANY").assertIsNotEnabled().assertIsNotSelected()
    sheetChip("DISTANCE_1000").assertIsNotEnabled().performClick()
    assertNull(viewModel.uiState.value.selection.maxDistanceMeters)
    assertEquals(0, location.requestCount)

    sheetChip("SPORTS").assertIsEnabled().performClick()
    composeTestRule.onNodeWithTag(EventsTags.FILTERS_SHOW).performClick()
    composeTestRule.onNodeWithTag(EventsTags.card("onCampus")).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.card("inTown")).assertCountEquals(0)
  }

  @Test
  fun turnOn_asksForApproximateLocation_andAGrantEnablesTheDistances() {
    show()

    composeTestRule.onNodeWithTag(EventsTags.TURN_ON_LOCATION).performClick()

    assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), registry.launches)
    assertTrue(location.permissionRequested)
    location.permissionGranted = true
    location.result = LocationResult.Available(onCampus.location)
    composeTestRule.runOnIdle { registry.respond(granted = true) }
    settle()
    composeTestRule.onAllNodesWithTag(EventsTags.LOCATION_OFF).assertCountEquals(0)

    sheetChip("DISTANCE_1000").assertIsEnabled().performClick()
    settle()

    assertEquals(1, location.requestCount)
    composeTestRule.onNodeWithTag(EventsTags.FILTERS_SHOW).performClick()
    composeTestRule.onNodeWithTag(EventsTags.card("onCampus")).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.card("inTown")).assertCountEquals(0)
  }

  @Test
  fun aRefusedDialog_keepsTheDistancesDisabled() {
    show()

    composeTestRule.onNodeWithTag(EventsTags.TURN_ON_LOCATION).performClick()
    composeTestRule.runOnIdle { registry.respond(granted = false) }
    settle()

    composeTestRule.onNodeWithTag(EventsTags.LOCATION_OFF).assertIsDisplayed()
    sheetChip("DISTANCE_500").assertIsNotEnabled()
  }

  @Test
  fun turnOn_afterAPermanentRefusal_opensTheAppSettingsInstead() {
    location.markPermissionRequested()
    showRationale = false
    show()

    composeTestRule.onNodeWithTag(EventsTags.TURN_ON_LOCATION).performClick()

    assertTrue(registry.launches.isEmpty())
    val intent =
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedActivity
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
    assertEquals("package:com.polysocial", intent.data.toString())
  }

  @Test
  fun turnOn_whenAndroidCanStillAsk_asksAgain() {
    location.markPermissionRequested()
    showRationale = true
    show()

    composeTestRule.onNodeWithTag(EventsTags.TURN_ON_LOCATION).performClick()

    assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), registry.launches)
  }

  @Test
  fun aPermissionGrantedInTheSettings_enablesTheDistancesWhenTheTabResumes() {
    show()
    composeTestRule.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.STARTED }
    settle()

    location.permissionGranted = true
    composeTestRule.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
    settle()

    composeTestRule.onAllNodesWithTag(EventsTags.LOCATION_OFF).assertCountEquals(0)
    sheetChip("DISTANCE_500").assertIsEnabled()
  }

  private class ControlledLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle = registry
  }

  /** Records the permission dialogs instead of showing them; [respond] answers the last one. */
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
}
