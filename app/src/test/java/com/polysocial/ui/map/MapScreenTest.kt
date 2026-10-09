// Contributors: OpenAI Codex (fake-renderer map UI tests for #50).
package com.polysocial.ui.map

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
  private var rendererDensity = 1f
  private val textScale = mutableFloatStateOf(1f)

  @Before
  fun setUp() {
    vm = MapViewModel(MapEventSource { source }, MAP_TEST_CLOCK)
  }

  private fun show(
      token: Boolean = true,
      renderStatus: MapRenderStatus = MapRenderStatus.READY,
      modifier: Modifier = Modifier,
      loadingEvents: Boolean = false,
      selectedIsTonight: Boolean = false,
  ) {
    vm.onRenderStatus(renderStatus)
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeRule.setContent {
      val state by vm.uiState.collectAsState()
      val density = LocalDensity.current
      CompositionLocalProvider(
          LocalDensity provides Density(density.density, textScale.floatValue)
      ) {
        PolySocialTheme {
          MapScreen(
              if (loadingEvents) state.copy(status = MapContentStatus.LOADING)
              else state.copy(selectedEventIsTonight = selectedIsTonight),
              vm::selectEvent,
              vm::closePreview,
              { detailId = it },
              vm::retry,
              vm::onRenderStatus,
              modifier = modifier,
              tokenConfigured = token,
              renderer = { events, select, _, inset ->
                rendererCalled = true
                renderedEvents = events
                rendererDensity = LocalDensity.current.density
                Box(Modifier.fillMaxSize().testTag(MapTags.CANVAS)) {
                  Column {
                    events.forEach { event ->
                      TextButton({ select(event.id) }, Modifier.testTag(MapTags.marker(event.id))) {
                        Text(event.title)
                      }
                    }
                  }
                  Box(Modifier.align(Alignment.BottomStart).padding(bottom = inset)) {
                    Text("Synthetic attribution", Modifier.testTag("fake_attribution"))
                  }
                }
              },
          )
        }
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
  fun attributionStaysTwelveDpAboveThePreviewWhenTextSizeChanges() {
    show(modifier = Modifier.requiredWidth(320.dp))
    composeRule.onNodeWithTag(MapTags.marker(first.id)).performClick()
    fun assertGap() {
      val card = composeRule.onNodeWithTag(MapTags.PREVIEW).fetchSemanticsNode().boundsInRoot
      val ornament = composeRule.onNodeWithTag("fake_attribution").fetchSemanticsNode().boundsInRoot
      assertEquals(12.0 * rendererDensity, (card.top - ornament.bottom).toDouble(), 1.0)
    }
    assertGap()
    val initialHeight =
        composeRule.onNodeWithTag(MapTags.PREVIEW).fetchSemanticsNode().boundsInRoot.height
    composeRule.runOnIdle {
      textScale.floatValue = 2f
      source.value =
          MapEventResult.Events(
              listOf(
                  first.copy(
                      title = "An evening meetup with a longer title to wrap onto another line"
                  )
              )
          )
    }
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeRule.waitForIdle()
    composeRule
        .onNodeWithTag(MapTags.TITLE)
        .assertTextEquals("An evening meetup with a longer title to wrap onto another line")
    val updatedCard = composeRule.onNodeWithTag(MapTags.PREVIEW).fetchSemanticsNode().boundsInRoot
    assertTrue(
        "Preview width=${updatedCard.width}, height=$initialHeight -> ${updatedCard.height}",
        updatedCard.height > initialHeight,
    )
    assertGap()
    composeRule.onNodeWithTag(MapTags.CLOSE).performClick()
    val canvas = composeRule.onNodeWithTag(MapTags.CANVAS).fetchSemanticsNode().boundsInRoot
    val ornament = composeRule.onNodeWithTag("fake_attribution").fetchSemanticsNode().boundsInRoot
    assertEquals(12.0 * rendererDensity, (canvas.bottom - ornament.bottom).toDouble(), 1.0)
  }

  @Test
  fun rendererRetryKeepsReadyEventsAndShowsOnlyMapLoading() {
    show(renderStatus = MapRenderStatus.ERROR)
    composeRule.onNodeWithTag(MapTags.RETRY).performClick()
    assertEquals(MapContentStatus.READY, vm.uiState.value.status)
    assertEquals(listOf(first, second), renderedEvents)
    composeRule
        .onNodeWithTag(MapTags.LOADING_LABEL)
        .assertTextEquals(context.getString(R.string.map_render_loading))
    composeRule.runOnIdle { vm.onRenderStatus(MapRenderStatus.READY) }
    composeRule.onNodeWithTag(MapTags.LOADING).assertDoesNotExist()
    composeRule.onNodeWithTag(MapTags.marker(first.id)).assertIsDisplayed()
  }

  @Test
  fun callerAccessibilityModifierReachesTheMapContainer() {
    show(modifier = Modifier.semantics { contentDescription = "Public event map" })
    composeRule.onNodeWithContentDescription("Public event map").assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.marker(first.id)).assertIsDisplayed()
  }

  @Test
  fun tappingMarkerShowsCorrectTitleDetailsAndClose() {
    show()
    composeRule.onNodeWithTag(MapTags.marker(second.id)).performClick()
    composeRule.onNodeWithTag(MapTags.PREVIEW).assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.TITLE).assertTextEquals(second.title)
    composeRule.onNodeWithTag(MapTags.TIME).assertTextEquals(formattedTime(second.startTime))
    composeRule.onNodeWithTag(MapTags.CATEGORY_ICON).assertIsDisplayed()
    composeRule
        .onNodeWithTag(MapTags.CATEGORY)
        .assertTextEquals(
            context.getString(R.string.map_category_day, "Sports", formattedDate(second.startTime))
        )
    composeRule.onNodeWithTag(MapTags.FIND_GROUP).assertIsDisplayed().assertIsNotEnabled()
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
    composeRule
        .onNodeWithTag(MapTags.CATEGORY)
        .assertTextEquals(
            context.getString(R.string.map_category_day, "Study", formattedDate(first.startTime))
        )
  }

  @Test
  fun emptyShowsFigmaNoticeWithoutMarkers() {
    source.value = MapEventResult.Events(emptyList())
    show()
    composeRule.onNodeWithTag(MapTags.EMPTY).assertIsDisplayed()
    composeRule.onNodeWithText(context.getString(R.string.map_empty_title)).assertIsDisplayed()
    composeRule
        .onNodeWithText(
            "No public events in the next two weeks around here. Try another filter, or create one with +."
        )
        .assertIsDisplayed()
    composeRule.onNodeWithTag(MapTags.EMPTY_ICON).assertIsDisplayed()
    val card = composeRule.onNodeWithTag(MapTags.EMPTY).fetchSemanticsNode().boundsInRoot
    val icon = composeRule.onNodeWithTag(MapTags.EMPTY_ICON).fetchSemanticsNode().boundsInRoot
    assertTrue(kotlin.math.abs(card.center.x - icon.center.x) < 1f)
    composeRule.onNodeWithTag(MapTags.marker(first.id)).assertDoesNotExist()
  }

  @Test
  fun loadingShowsProgressWithoutEmptyNotice() {
    show(loadingEvents = true)
    composeRule.onNodeWithTag(MapTags.LOADING).assertIsDisplayed()
    val card = composeRule.onNodeWithTag(MapTags.LOADING).fetchSemanticsNode().boundsInRoot
    val indicator =
        composeRule.onNodeWithTag(MapTags.LOADING_INDICATOR).fetchSemanticsNode().boundsInRoot
    val label = composeRule.onNodeWithTag(MapTags.LOADING_LABEL).fetchSemanticsNode().boundsInRoot
    assertTrue(indicator.bottom < label.top)
    assertTrue(kotlin.math.abs(card.center.x - indicator.center.x) < 1f)
    assertTrue(kotlin.math.abs(card.center.x - label.center.x) < 1f)
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
    assertEquals(0, vm.uiState.value.renderGeneration)
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
  fun missingTokenDoesNotAddAnExtraPrivacyControl() {
    show(token = false)
    composeRule.onNodeWithTag("map_privacy").assertDoesNotExist()
    composeRule.onNodeWithTag(MapTags.SETUP).assertIsDisplayed()
  }

  private fun formattedTime(instant: java.time.Instant): String =
      java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
          .withZone(java.time.ZoneId.of("Europe/Zurich"))
          .format(instant)

  private fun formattedDate(instant: java.time.Instant): String =
      java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
          .withZone(MAP_TIME_ZONE)
          .format(instant)

  @Test
  fun everyCategoryHasDistinctColorAndLocalizedLabel() {
    assertEquals(
        EventCategory.entries.size,
        EventCategory.entries.map(::categoryColor).distinct().size,
    )
    EventCategory.entries.forEach { assertTrue(context.getString(categoryLabel(it)).isNotBlank()) }
  }

  @Test
  fun eventTodayShowsFigmaCategoryAndTonightLabel() {
    show(selectedIsTonight = true)
    composeRule.onNodeWithTag(MapTags.marker(first.id)).performClick()
    composeRule.onNodeWithTag(MapTags.CATEGORY).assertTextEquals("Study · Tonight")
    composeRule.onNodeWithTag(MapTags.FIND_GROUP).assertIsNotEnabled()
  }

  @Test
  fun eachCategoryShowsItsIconLabelAndDisabledMatchingAction() {
    val events =
        EventCategory.entries.map {
          first.copy(id = it.name, category = it, title = "${it.name} meetup")
        }
    source.value = MapEventResult.Events(events)
    show()
    events.forEach { event ->
      composeRule.onNodeWithTag(MapTags.marker(event.id)).performClick()
      composeRule.onNodeWithTag(MapTags.TITLE).assertTextEquals(event.title)
      composeRule.onNodeWithTag(MapTags.CATEGORY_ICON).assertIsDisplayed()
      composeRule
          .onNodeWithTag(MapTags.CATEGORY)
          .assertTextEquals(
              context.getString(
                  R.string.map_category_day,
                  context.getString(categoryLabel(event.category)),
                  formattedDate(event.startTime),
              )
          )
      composeRule.onNodeWithTag(MapTags.FIND_GROUP).assertIsNotEnabled()
      composeRule.onNodeWithTag(MapTags.CLOSE).performClick()
    }
  }
}
