// Contributors: OpenAI Codex (repository-to-map UI integration and callback tests for #50).
package com.polysocial.ui.map

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.CreateEventResult
import com.polysocial.model.event.Event
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.map.RepositoryMapEventSource
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
class MapRepositoryRouteTest {
  @get:Rule(order = 0) val dispatcherRule = MainDispatcherRule()
  @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
  private var detailId: String? = null

  private fun show(repository: FakeEventRepository, tokenConfigured: Boolean = true) {
    val model = MapViewModel(RepositoryMapEventSource(repository))
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
    Column(Modifier.testTag(MapTags.CANVAS)) {
      TextButton({ status(MapRenderStatus.READY) }, Modifier.testTag("fake_renderer_ready")) {
        Text("Map loaded")
      }
      events.forEach { event ->
        TextButton({ select(event.id) }, Modifier.testTag(MapTags.marker(event.id))) {
          Text(event.title)
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
    compose.onNodeWithTag(MapTags.TITLE).assertTextEquals("Recovered meetup")
  }

  @Test
  fun missingTokenKeepsSetupAndPrivacyDismissalUsableWithLoadedEvents() {
    val repository = FakeEventRepository()
    repository.seed(validEvent().copy(id = "configured-data"))
    show(repository, tokenConfigured = false)
    compose.onNodeWithTag(MapTags.SETUP).assertIsDisplayed()
    compose.onNodeWithTag(MapTags.CANVAS).assertDoesNotExist()
    compose.onNodeWithTag(MapTags.PRIVACY).performClick()
    compose.onNodeWithTag(MapTags.PRIVACY_DIALOG).assertIsDisplayed()

    compose.runOnIdle {
      (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
    }

    compose.onNodeWithTag(MapTags.PRIVACY_DIALOG).assertDoesNotExist()
    compose.onNodeWithTag(MapTags.SETUP).assertIsDisplayed()
  }
}
