// Contributors: OpenAI Codex (repository-to-map UI tests for #50; denied-location fake for #51).
package com.polysocial.ui.map

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.CreateEventResult
import com.polysocial.model.event.Event
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.map.RepositoryMapEventSource
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapRepositoryRouteTest {
  @get:Rule(order = 0) val dispatcherRule = MainDispatcherRule()
  @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
  private var detailId: String? = null

  private fun model(repository: FakeEventRepository) =
      MapViewModel(
          RepositoryMapEventSource(repository),
          FakeLocationService().apply { markPermissionRequested() },
          MAP_TEST_CLOCK,
      )

  private fun show(repository: FakeEventRepository, tokenConfigured: Boolean = true) {
    val model = model(repository)
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    compose.setContent {
      PolySocialTheme {
        MapRoute(
            onViewDetails = { detailId = it },
            viewModel = model,
            tokenConfigured = tokenConfigured,
            renderer = { events, select, status, _ -> FakeRenderer(events, select, status) },
        )
      }
    }
    settle()
    if (tokenConfigured) compose.onNodeWithTag("fake_renderer_ready").performClick()
    settle()
  }

  private fun settle() {
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    compose.waitForIdle()
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    compose.waitForIdle()
  }

  @Composable
  private fun FakeRenderer(
      events: List<Event>,
      select: (String) -> Unit,
      status: (MapRenderStatus) -> Unit,
  ) {
    // Keep physical click targets clear of the permission notice at the top of the map.
    Box(Modifier.fillMaxSize().testTag(MapTags.CANVAS)) {
      TextButton(
          { status(MapRenderStatus.READY) },
          Modifier.align(Alignment.TopStart).testTag("fake_renderer_ready"),
      ) {
        Text("Map loaded")
      }
      Column(Modifier.align(Alignment.CenterStart)) {
        events.forEach { event ->
          TextButton({ select(event.id) }, Modifier.testTag(MapTags.marker(event.id))) {
            Text(event.title)
          }
        }
      }
    }
  }

  @Test
  fun cachedPublicEventsAndNewEventsReachMarkersAndPreviewActions() = runTest {
    val repository = FakeEventRepository().apply { readFromCache = true }
    val cached = validEvent(title = "Cached meetup").copy(id = "cached")
    repository.seed(cached, cached.copy(id = "private", isPrivate = true))
    show(repository)
    compose.onNodeWithTag(MapTags.marker("cached")).assertIsDisplayed().performClick()
    settle()
    compose.onNodeWithTag(MapTags.marker("private")).assertDoesNotExist()
    compose.onNodeWithTag(MapTags.TITLE).assertTextEquals(cached.title)
    compose.onNodeWithTag(MapTags.DISTANCE).assertDoesNotExist()
    compose.onNodeWithTag(MapTags.DETAILS).performClick()
    assertEquals(cached.id, detailId)
    compose.onNodeWithTag(MapTags.CLOSE).performClick()
    settle()
    compose.onNodeWithTag(MapTags.PREVIEW).assertDoesNotExist()

    val created =
        repository.createEvent(validEvent(title = "New meetup")) as CreateEventResult.Created
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    compose.onNodeWithTag(MapTags.marker(created.eventId)).assertIsDisplayed().performClick()
    settle()
    compose.onNodeWithTag(MapTags.TITLE).assertTextEquals("New meetup")
    compose.onNodeWithTag(MapTags.marker("cached")).assertIsDisplayed()
  }

  @Test
  fun retryReconnectsAnErroredRepositoryAndDisplaysItsEvents() {
    val repository = FakeEventRepository().apply { readFailure = true }
    show(repository)
    compose.onNodeWithTag(MapTags.ERROR).assertIsDisplayed()
    repository.seed(validEvent(title = "Recovered meetup").copy(id = "recovered"))
    repository.readFailure = false

    compose.onNodeWithTag(MapTags.RETRY).performClick()
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    compose.onNodeWithTag("fake_renderer_ready").performClick()

    compose.onNodeWithTag(MapTags.ERROR).assertDoesNotExist()
    compose.onNodeWithTag(MapTags.marker("recovered")).assertIsDisplayed().performClick()
    settle()
    compose.onNodeWithTag(MapTags.TITLE).assertTextEquals("Recovered meetup")
  }

  @Test
  fun missingTokenKeepsSetupVisibleWithLoadedEvents() {
    val repository = FakeEventRepository()
    repository.seed(validEvent().copy(id = "configured-data"))
    show(repository, tokenConfigured = false)
    compose.onNodeWithTag(MapTags.SETUP).assertIsDisplayed()
    compose.onNodeWithTag(MapTags.CANVAS).assertDoesNotExist()
    compose.onNodeWithTag("map_privacy").assertDoesNotExist()
  }

  @Test
  fun replacingTheViewModelRebindsSelectionCloseRetryAndRendererCallbacks() {
    val firstEvent = validEvent(title = "First model").copy(id = "first")
    val firstRepository = FakeEventRepository().apply { seed(firstEvent) }
    val firstModel = model(firstRepository)
    val secondEvent = validEvent(title = "Second model").copy(id = "second")
    val secondRepository =
        FakeEventRepository().apply {
          seed(secondEvent)
          readFailure = true
        }
    val secondModel = model(secondRepository)
    var currentModel by mutableStateOf(firstModel)
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    compose.setContent {
      PolySocialTheme {
        MapRoute(
            onViewDetails = { detailId = it },
            viewModel = currentModel,
            tokenConfigured = true,
            renderer = { events, select, status, _ -> FakeRenderer(events, select, status) },
        )
      }
    }
    settle()
    compose.onNodeWithTag("fake_renderer_ready").performClick()
    compose.onNodeWithTag(MapTags.marker(firstEvent.id)).performClick()
    settle()
    compose.onNodeWithTag(MapTags.TITLE).assertTextEquals(firstEvent.title)

    compose.runOnIdle { currentModel = secondModel }
    settle()
    compose.onNodeWithTag(MapTags.ERROR).assertIsDisplayed()
    compose.onNodeWithTag(MapTags.PREVIEW).assertDoesNotExist()
    secondRepository.readFailure = false
    compose.onNodeWithTag(MapTags.RETRY).performClick()
    settle()
    compose.onNodeWithTag("fake_renderer_ready").performClick()
    settle()
    assertEquals(MapRenderStatus.READY, secondModel.uiState.value.renderStatus)
    compose.onNodeWithTag(MapTags.marker(secondEvent.id)).performClick()
    settle()
    compose.onNodeWithTag(MapTags.TITLE).assertTextEquals(secondEvent.title)
    compose.onNodeWithTag(MapTags.DETAILS).performClick()
    assertEquals(secondEvent.id, detailId)
    compose.onNodeWithTag(MapTags.CLOSE).performClick()
    settle()
    compose.onNodeWithTag(MapTags.PREVIEW).assertDoesNotExist()
    assertEquals(firstEvent, firstModel.uiState.value.selectedEvent)
  }

  @Test
  fun configuredTokenIsReadByTheRouteAndOnlyPublicTokensEnableTheRenderer() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val resources = spyk(context.resources)
    var token by mutableStateOf("")
    every { resources.getString(R.string.mapbox_access_token) } answers { token }
    val event = validEvent().copy(id = "token-event")
    val viewModel = model(FakeEventRepository().apply { seed(event) })
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    compose.setContent {
      CompositionLocalProvider(LocalResources provides resources) {
        PolySocialTheme {
          MapRoute(
              onViewDetails = { detailId = it },
              viewModel = viewModel,
              renderer = { events, select, status, _ -> FakeRenderer(events, select, status) },
          )
        }
      }
    }
    settle()
    compose.onNodeWithTag(MapTags.SETUP).assertIsDisplayed()
    compose.onNodeWithTag(MapTags.CANVAS).assertDoesNotExist()

    compose.runOnIdle { token = "pk.hermetic-route-test" }
    settle()
    compose.onNodeWithTag(MapTags.CANVAS).assertIsDisplayed()
    compose.onNodeWithTag(MapTags.SETUP).assertDoesNotExist()
    compose.onNodeWithTag("fake_renderer_ready").performClick()
    settle()
    compose.onNodeWithTag(MapTags.marker(event.id)).assertIsDisplayed()

    compose.runOnIdle { token = "sk.not-an-app-token" }
    settle()
    compose.onNodeWithTag(MapTags.SETUP).assertIsDisplayed()
    compose.onNodeWithTag(MapTags.CANVAS).assertDoesNotExist()
  }
}
