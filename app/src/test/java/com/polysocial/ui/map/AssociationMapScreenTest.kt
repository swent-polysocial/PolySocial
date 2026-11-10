// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests of an association's own map, its
// preview and empty state, #133.
package com.polysocial.ui.map

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.event.Event
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A verified association's map (#133), with its own events from a fake source. */
@RunWith(AndroidJUnit4::class)
class AssociationMapScreenTest {
  @get:Rule val composeRule = createComposeRule()
  @get:Rule val dispatcherRule = MainDispatcherRule()
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val public =
      validEvent(title = "Ciné-club")
          .copy(id = "public", createdBy = CLUB, isAssociationEvent = true)
  private val private = public.copy(id = "private", title = "Committee meeting", isPrivate = true)
  private val events =
      MutableStateFlow<MapEventResult>(MapEventResult.Events(listOf(public, private)))
  private var detailId: String? = null

  /** The association's source: its own events, flagged as such. */
  private fun ownSource(result: Flow<MapEventResult>) =
      object : MapEventSource {
        override val ownEvents = true

        override fun observeEvents() = result
      }

  private fun viewModel(source: MapEventSource) =
      MapViewModel(source, FakeLocationService(), MAP_TEST_CLOCK, FakeAssociationNames())

  private fun show(vm: MapViewModel) {
    composeRule.setContent {
      val state by vm.uiState.collectAsState()
      PolySocialTheme {
        MapScreen(
            state,
            vm::selectEvent,
            vm::closePreview,
            { detailId = it },
            vm::retry,
            vm::onRenderStatus,
            tokenConfigured = true,
            renderer = { shown, select, _, _ ->
              Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.Center)) {
                  shown.forEach { event ->
                    TextButton({ select(event.id) }, Modifier.testTag(MapTags.marker(event.id))) {
                      Text(event.title)
                    }
                  }
                }
              }
            },
        )
      }
    }
    vm.onRenderStatus(MapRenderStatus.READY)
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeRule.waitForIdle()
  }

  private fun markers(vm: MapViewModel): List<Event> = vm.uiState.value.events

  @Test
  fun theAssociationsMap_keepsItsPrivateEvents_whileTheStudentsMapDropsThem() {
    val own = viewModel(ownSource(events))
    val students = viewModel(MapEventSource { events })
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(public, private), markers(own))
    assertTrue(own.uiState.value.ownEvents)
    assertEquals(listOf(public), markers(students))
    assertEquals(false, students.uiState.value.ownEvents)
  }

  @Test
  fun aPublicEventsPreview_hasDetailsOnly_withoutFindAGroupOrPrivate() {
    show(viewModel(ownSource(events)))

    composeRule.onNodeWithTag(MapTags.marker(public.id)).performClick()

    composeRule.onNodeWithTag(MapTags.TITLE).assertTextEquals(public.title)
    composeRule.onNodeWithTag(MapTags.FIND_GROUP).assertDoesNotExist()
    composeRule.onNodeWithTag(MapTags.PRIVATE).assertDoesNotExist()
    composeRule.onNodeWithTag(MapTags.DETAILS).assertIsDisplayed().performClick()
    assertEquals(public.id, detailId)
  }

  @Test
  fun aPrivateEventsPreview_saysItIsPrivate() {
    show(viewModel(ownSource(events)))

    composeRule.onNodeWithTag(MapTags.marker(private.id)).performClick()

    composeRule.onNodeWithTag(MapTags.TITLE).assertTextEquals(private.title)
    composeRule
        .onNodeWithTag(MapTags.PRIVATE)
        .assertIsDisplayed()
        .assertTextEquals(context.getString(R.string.map_private))
    composeRule.onNodeWithTag(MapTags.FIND_GROUP).assertDoesNotExist()
  }

  @Test
  fun noUpcomingEvents_showsTheAssociationsEmptyState() {
    events.value = MapEventResult.Events(emptyList())
    show(viewModel(ownSource(events)))

    composeRule.onNodeWithTag(MapTags.EMPTY).assertIsDisplayed()
    composeRule.onNodeWithText("No upcoming events").assertIsDisplayed()
    composeRule
        .onNodeWithText("Your association has no upcoming events yet. Create one with +.")
        .assertIsDisplayed()
    composeRule.onNodeWithText(context.getString(R.string.map_empty_title)).assertDoesNotExist()
  }

  @Test
  fun eventsThatCantBeRead_offerRetry() {
    show(viewModel(ownSource(MutableStateFlow(MapEventResult.Error))))

    composeRule.onNodeWithTag(MapTags.ERROR).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.RETRY).assertIsDisplayed()
  }

  private companion object {
    const val CLUB = "club"
  }
}
