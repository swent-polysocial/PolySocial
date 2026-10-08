// Contributors: OpenAI Codex (permission launcher and foreground lifecycle integration tests, #51).
package com.polysocial.ui.map

import android.Manifest
import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.location.LocationResult
import com.polysocial.model.location.LocationService
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class MapRouteTest {
  @get:Rule val composeRule = createComposeRule()
  @get:Rule val dispatcherRule = MainDispatcherRule()
  private val event = validEvent(title = "Campus study session").copy(id = "event")
  private val location = FakeLocationService()
  private val owner = ControlledLifecycleOwner()
  private val registry = RecordingPermissionRegistry { location.permissionRequested }
  private val registryOwner =
      object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
      }
  private var routeVisible by mutableStateOf(true)
  private var detailId: String? = null
  private lateinit var viewModel: MapViewModel
  private lateinit var resources: Resources
  private var showRationale: Boolean? = true
  private lateinit var routeContext: Context

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // Force the setup UI even when a developer's build contains a public token: no native renderer
    // or live Mapbox request is allowed in these permission/lifecycle tests.
    resources = spyk(context.resources)
    every { resources.getString(R.string.mapbox_access_token) } returns ""
    viewModel = createViewModel(location)
  }

  private fun createViewModel(service: LocationService) =
      MapViewModel(
          MapEventSource { flowOf(MapEventResult.Events(listOf(event))) },
          service,
      )

  private fun show(initialState: Lifecycle.State = Lifecycle.State.RESUMED) {
    owner.registry.currentState = initialState
    composeRule.setContent {
      CompositionLocalProvider(
          LocalActivityResultRegistryOwner provides registryOwner,
          LocalLifecycleOwner provides owner,
          LocalResources provides resources,
      ) {
        PolySocialTheme {
          routeContext = LocalContext.current
          if (routeVisible)
              MapRoute(
                  onViewDetails = { detailId = it },
                  viewModel = viewModel,
                  permissionRationale =
                      if (showRationale == null) null
                      else {
                        { showRationale == true }
                      },
              )
        }
      }
    }
    settle()
  }

  private fun settle() {
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeRule.waitForIdle()
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeRule.waitForIdle()
  }

  private fun moveTo(state: Lifecycle.State) {
    composeRule.runOnIdle { owner.registry.currentState = state }
    settle()
  }

  @Test
  fun firstResumedEntry_launchesOnlyApproximatePermissionOnceAfterSavingPromptFlag() {
    show()
    assertEquals(
        listOf(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        ),
        registry.launches,
    )
    assertEquals(listOf(true), registry.promptFlagsAtLaunch)
    assertEquals(0, location.requestCount)
    composeRule.onNodeWithTag(MapTags.SETUP).assertIsDisplayed()
    moveTo(Lifecycle.State.STARTED)
    moveTo(Lifecycle.State.RESUMED)
    assertEquals(1, registry.launches.size)
  }

  @Test
  fun startedRoute_waitsForResumeBeforeLaunchingPermissionDialog() {
    show(initialState = Lifecycle.State.STARTED)
    assertTrue(registry.launches.isEmpty())
    assertEquals(0, location.requestCount)
    moveTo(Lifecycle.State.RESUMED)
    assertEquals(1, registry.launches.size)
  }

  @Test
  fun deniedCallback_keepsPreviewAndDetailsUsableAndRemountDoesNotPromptAgain() {
    show()
    composeRule.runOnIdle { registry.respond(grantedCoarse = false) }
    settle()
    assertEquals(0, location.requestCount)
    assertEquals(listOf(event), viewModel.uiState.value.events)
    composeRule.onNodeWithTag(MapTags.LOCATION_NOTICE).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.SETUP).assertIsDisplayed()
    composeRule.runOnIdle { viewModel.selectEvent(event.id) }
    composeRule.onNodeWithTag(MapTags.TITLE).assertTextEquals(event.title)
    composeRule.onNodeWithTag(MapTags.DISTANCE).assertDoesNotExist()
    composeRule.onNodeWithTag(MapTags.DETAILS).performClick()
    assertEquals(event.id, detailId)
    composeRule.runOnIdle { routeVisible = false }
    settle()
    composeRule.runOnIdle { routeVisible = true }
    settle()
    assertEquals(1, registry.launches.size)
    assertEquals(0, location.requestCount)
    composeRule.onNodeWithTag(MapTags.LOCATION_NOTICE).assertIsDisplayed()
  }

  @Test
  fun approximateGrantWhilePaused_waitsForResumeAndNextPauseDropsDistance() {
    show()
    moveTo(Lifecycle.State.STARTED)
    composeRule.runOnIdle {
      location.permissionGranted = true
      location.result = LocationResult.Available(event.location)
      registry.respond(grantedCoarse = true)
    }
    settle()
    assertEquals(0, location.requestCount)
    assertEquals(MapLocationState.Idle, viewModel.uiState.value.locationState)
    moveTo(Lifecycle.State.RESUMED)
    assertEquals(1, location.requestCount)
    composeRule.runOnIdle { viewModel.selectEvent(event.id) }
    composeRule
        .onNodeWithTag(MapTags.DISTANCE)
        .assertTextEquals(resources.getString(R.string.map_distance_meters, 0))
    moveTo(Lifecycle.State.STARTED)
    assertNull(viewModel.uiState.value.selectedDistanceMeters)
    assertEquals(MapLocationState.Idle, viewModel.uiState.value.locationState)
    composeRule.onNodeWithTag(MapTags.DISTANCE).assertDoesNotExist()
    moveTo(Lifecycle.State.RESUMED)
    assertEquals(1, location.requestCount)
    assertEquals(1, registry.launches.size)
  }

  @Test
  fun pausingRoute_cancelsPendingFixAndReturningDoesNotReadAgain() {
    location.permissionGranted = true
    var requestStarts = 0
    var requestCancelled = false
    val pending =
        object : LocationService by location {
          override suspend fun currentLocation(): LocationResult {
            requestStarts++
            try {
              awaitCancellation()
            } finally {
              requestCancelled = true
            }
          }
        }
    viewModel = createViewModel(pending)
    show()
    assertEquals(1, requestStarts)
    assertTrue(registry.launches.isEmpty())
    moveTo(Lifecycle.State.STARTED)
    assertTrue(requestCancelled)
    assertNull(viewModel.uiState.value.selectedDistanceMeters)
    assertEquals(listOf(event), viewModel.uiState.value.events)
    moveTo(Lifecycle.State.RESUMED)
    assertEquals(1, requestStarts)
    composeRule.onNodeWithTag(MapTags.LOCATION_NOTICE).assertDoesNotExist()
  }

  @Test
  fun permanentDenialTurnOnOpensThisAppsSettingsWithoutAnotherDialog() {
    showRationale = null
    show()
    composeRule.runOnIdle { registry.respond(grantedCoarse = false) }
    settle()
    composeRule.onNodeWithTag(MapTags.LOCATION_ACTION).performClick()
    settle()
    val intent =
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedActivity
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
    assertEquals("package:${routeContext.packageName}", intent.data.toString())
    assertEquals(1, registry.launches.size)
    assertEquals(MapLocationState.Denied, viewModel.uiState.value.locationState)
  }

  @Test
  fun recoverableDenialTurnOnRequestsApproximatePermissionAgain() {
    show()
    composeRule.runOnIdle { registry.respond(grantedCoarse = false) }
    settle()
    composeRule.onNodeWithTag(MapTags.LOCATION_ACTION).performClick()
    settle()
    assertEquals(2, registry.launches.size)
    assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), registry.launches.last())
  }

  private class ControlledLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle = registry
  }

  private class RecordingPermissionRegistry(private val promptRequested: () -> Boolean) :
      ActivityResultRegistry() {
    val launches = mutableListOf<List<String>>()
    val promptFlagsAtLaunch = mutableListOf<Boolean>()
    private var requestCode: Int? = null

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
      check(contract is ActivityResultContracts.RequestMultiplePermissions)
      launches += (input as Array<*>).map { it as String }
      promptFlagsAtLaunch += promptRequested()
      this.requestCode = requestCode
    }

    fun respond(grantedCoarse: Boolean) {
      val code = checkNotNull(requestCode) { "No permission request was launched." }
      dispatchResult(
          code,
          mapOf(
              Manifest.permission.ACCESS_COARSE_LOCATION to grantedCoarse,
              Manifest.permission.ACCESS_FINE_LOCATION to false,
          ),
      )
    }
  }
}
