// Contributors: OpenAI Codex (fake-renderer map UI tests for #50).
package com.polysocial.ui.map

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.validEvent
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapScreenTest {
  @get:Rule val composeRule = createComposeRule()
  @get:Rule val dispatcherRule = MainDispatcherRule()
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val first = validEvent(title = "Study afternoon").copy(id = "one")
  private val second =
      validEvent(title = "Sports afternoon", endTime = null)
          .copy(id = "two", category = EventCategory.SPORTS)
  private val source =
      MutableStateFlow<MapEventResult>(MapEventResult.Events(listOf(first, second)))
  private lateinit var vm: MapViewModel
  private var detailId: String? = null
  private var rendererCalled = false
  private var renderedEvents = emptyList<com.polysocial.model.event.Event>()

  @Before
  fun setUp() {
    vm = MapViewModel(MapEventSource { source })
  }

  private fun show(token: Boolean = true, renderStatus: MapRenderStatus = MapRenderStatus.READY) {
    vm.onRenderStatus(renderStatus)
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
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
            tokenConfigured = token,
            renderer = { events, select, _, _ ->
              rendererCalled = true
              renderedEvents = events
              Column(Modifier.fillMaxSize().testTag(MapTags.CANVAS)) {
                events.forEach { event ->
                  TextButton({ select(event.id) }, Modifier.testTag(MapTags.marker(event.id))) {
                    Text(event.title)
                  }
                }
              }
            },
        )
      }
    }
  }

  @Test
  fun fakeRepositoryRecordsCreateOneMarkerEachAtTheirCoordinates() {
    show()
    composeRule.onNodeWithTag(MapTags.marker(first.id)).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.marker(second.id)).assertIsDisplayed()
    composeRule.onAllNodesWithTag(MapTags.marker(first.id)).assertCountEquals(1)
    assertEquals(listOf(first, second), renderedEvents)
    assertEquals(first.location, renderedEvents[0].location)
    assertEquals(second.location, renderedEvents[1].location)
    composeRule.onNodeWithTag(MapTags.PREVIEW).assertDoesNotExist()
  }

  @Test
  fun tappingMarkerShowsCorrectTitleDetailsAndClose() {
    show()
    composeRule.onNodeWithTag(MapTags.marker(second.id)).performClick()
    composeRule.onNodeWithTag(MapTags.PREVIEW).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.TITLE).assertTextEquals(second.title)
    composeRule.onNodeWithTag(MapTags.TIME).assertTextEquals(formattedTime(second.startTime))
    composeRule.onNodeWithText(context.getString(R.string.map_category_sports)).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.DETAILS).performClick()
    assertEquals(second.id, detailId)
    composeRule.onNodeWithTag(MapTags.CLOSE).performClick()
    composeRule.onNodeWithTag(MapTags.PREVIEW).assertDoesNotExist()
  }

  @Test
  fun previewShowsBothStartAndOptionalEnd() {
    show()
    composeRule.onNodeWithTag(MapTags.marker(first.id)).performClick()
    composeRule.onNodeWithTag(MapTags.TITLE).assertTextEquals(first.title)
    composeRule
        .onNodeWithTag(MapTags.TIME)
        .assertTextEquals(
            context.getString(
                R.string.map_time_range,
                formattedTime(first.startTime),
                formattedTime(first.endTime!!),
            )
        )
    composeRule.onNodeWithText(context.getString(R.string.map_category_study)).assertIsDisplayed()
  }

  @Test
  fun emptyShowsFigmaNoticeWithoutMarkers() {
    source.value = MapEventResult.Events(emptyList())
    show()
    composeRule.onNodeWithTag(MapTags.EMPTY).assertIsDisplayed()
    composeRule.onNodeWithText(context.getString(R.string.map_empty_title)).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.marker(first.id)).assertDoesNotExist()
  }

  @Test
  fun loadingShowsProgressWithoutEmptyNotice() {
    source.value = MapEventResult.Loading
    show()
    composeRule.onNodeWithTag(MapTags.LOADING).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.EMPTY).assertDoesNotExist()
  }

  @Test
  fun mapLoadingShowsProgressWhileEventsAreReady() {
    show(renderStatus = MapRenderStatus.LOADING)
    composeRule.onNodeWithTag(MapTags.LOADING).assertIsDisplayed()
  }

  @Test
  fun eventErrorOffersRetryAndLeavesRenderedMapVisible() {
    source.value = MapEventResult.Error
    show()
    composeRule.onNodeWithTag(MapTags.ERROR).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.CANVAS).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.RETRY).performClick()
    assertEquals(1, vm.uiState.value.renderGeneration)
  }

  @Test
  fun tileOrRejectedTokenErrorShowsMapFailure() {
    show(renderStatus = MapRenderStatus.ERROR)
    composeRule.onNodeWithText(context.getString(R.string.map_tiles_error)).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.RETRY).assertIsDisplayed()
  }

  @Test
  fun missingTokenSkipsNativeRendererAndShowsSetup() {
    show(token = false)
    composeRule.onNodeWithTag(MapTags.SETUP).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.CANVAS).assertDoesNotExist()
    assertTrue(!rendererCalled)
  }

  @Test
  fun unavailableSourceIsHonestAndMapStillWorks() {
    source.value = MapEventResult.Unavailable
    show()
    composeRule.onNodeWithTag(MapTags.SOURCE_UNAVAILABLE).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.EMPTY).assertDoesNotExist()
    composeRule.onNodeWithTag(MapTags.CANVAS).assertIsDisplayed()
  }

  @Test
  fun privacyNoticeCanOpenAndCloseEvenWithoutToken() {
    show(token = false)
    composeRule.onNodeWithTag(MapTags.PRIVACY).performClick()
    composeRule.onNodeWithTag(MapTags.PRIVACY_DIALOG).assertIsDisplayed()
    composeRule.onNodeWithText(context.getString(R.string.map_privacy_message)).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.PRIVACY_CLOSE).performClick()
    composeRule.onNodeWithTag(MapTags.PRIVACY_DIALOG).assertDoesNotExist()
  }

  private fun formattedTime(instant: java.time.Instant): String =
      java.time.format.DateTimeFormatter.ofLocalizedDateTime(
              java.time.format.FormatStyle.MEDIUM,
              java.time.format.FormatStyle.SHORT,
          )
          .withZone(java.time.ZoneId.of("Europe/Zurich"))
          .format(instant)

  @Test
  fun everyCategoryHasDistinctColorAndLocalizedLabel() {
    assertEquals(
        EventCategory.entries.size,
        EventCategory.entries.map(::categoryColor).distinct().size,
    )
    EventCategory.entries.forEach { assertTrue(context.getString(categoryLabel(it)).isNotBlank()) }
  }
}
