// Contributors: Claude (UI tests of the Map tab's search and filters, #116; review fixes).
package com.polysocial.ui.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.map.MapEventSource
import com.polysocial.model.map.RepositoryMapEventSource
import com.polysocial.ui.event.list.EventsTags
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapFiltersScreenTest {
  @get:Rule(order = 0) val dispatcherRule = MainDispatcherRule()
  @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

  private val study = validEvent(title = "Study session").copy(id = "study")
  private val party =
      validEvent(title = "Warm-up").copy(id = "party", category = EventCategory.PARTY)
  private val sports =
      validEvent(title = "Climbing").copy(id = "sports", category = EventCategory.SPORTS)
  private val repository = FakeEventRepository().apply { seed(study, party, sports) }
  private var reads = 0

  private fun show() {
    val model =
        MapViewModel(
            MapEventSource {
              reads++
              RepositoryMapEventSource(repository).observePublicUpcomingEvents()
            },
            FakeLocationService().apply { markPermissionRequested() },
            MAP_TEST_CLOCK,
            FakeAssociationNames(),
        )
    compose.setContent {
      PolySocialTheme {
        MapRoute(
            onViewDetails = {},
            viewModel = model,
            tokenConfigured = true,
            renderer = { events, select, status, _ -> FakeRenderer(events, select, status) },
        )
      }
    }
    settle()
  }

  private fun settle() {
    dispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    compose.waitForIdle()
  }

  @Composable
  private fun FakeRenderer(
      events: List<Event>,
      select: (String) -> Unit,
      status: (MapRenderStatus) -> Unit,
  ) {
    LaunchedEffect(Unit) { status(MapRenderStatus.READY) }
    // Centered, below the search and chips at the top of the map.
    Column(
        Modifier.fillMaxSize().testTag(MapTags.CANVAS),
        verticalArrangement = Arrangement.Center,
    ) {
      events.forEach { event ->
        TextButton({ select(event.id) }, Modifier.testTag(MapTags.marker(event.id))) {
          Text(event.title)
        }
      }
    }
  }

  private fun assertMarkers(vararg shown: String) {
    for (event in listOf(study, party, sports)) {
      val marker = compose.onNodeWithTag(MapTags.marker(event.id))
      if (event.id in shown) marker.assertExists() else marker.assertDoesNotExist()
    }
  }

  @Test
  fun aCategoryChipHidesTheOtherCategoriesMarkers() {
    show()
    assertMarkers("study", "party", "sports")

    compose.onNodeWithTag(EventsTags.chip(EventCategory.PARTY.name)).performClick()
    settle()
    assertMarkers("party")
    // The filter is local: the events were read once.
    assertEquals(1, reads)
  }

  @Test
  fun clearFiltersShowsEveryMarkerAgain() {
    show()
    compose.onNodeWithTag(EventsTags.chip(EventCategory.SPORTS.name)).performClick()
    compose.onNodeWithTag(EventsTags.SEARCH).performTextInput("study")
    settle()
    assertMarkers()
    compose.onNodeWithTag(MapTags.NO_MATCH).assertIsDisplayed()
    compose
        .onNode(hasText("No matching events") and hasAnyAncestor(hasTestTag(MapTags.NO_MATCH)))
        .assertIsDisplayed()
    // The map's own body (Figma "Map · no matching event"), not the Events tab's.
    compose
        .onNode(
            hasText(compose.activity.getString(R.string.map_no_match_body)) and
                hasAnyAncestor(hasTestTag(MapTags.NO_MATCH))
        )
        .assertIsDisplayed()

    compose.onNodeWithTag(MapTags.CLEAR_FILTERS).performClick()
    settle()
    compose.onNodeWithTag(MapTags.NO_MATCH).assertDoesNotExist()
    assertMarkers("study", "party", "sports")
  }

  @Test
  fun theClearFiltersChipShowsOnlyWhileSomethingIsOnAndClearsIt() {
    show()
    // Figma "Map · filters": no first chip, and never *All*.
    compose.onNodeWithTag(EventsTags.chip(CLEAR_CHIP)).assertDoesNotExist()
    compose.onNodeWithTag(EventsTags.chip(ALL_CHIP)).assertDoesNotExist()

    compose.onNodeWithTag(EventsTags.SEARCH).performTextInput("study")
    settle()
    compose.onNodeWithTag(EventsTags.chip(CLEAR_CHIP)).assertIsDisplayed()

    compose.onNodeWithTag(EventsTags.chip(CLEAR_CHIP)).performClick()
    compose.onNodeWithTag(EventsTags.chip(EventCategory.PARTY.name)).performClick()
    settle()
    // Clearing dropped the search: the party marker is back with the category alone.
    assertMarkers("party")
    compose.onNodeWithTag(EventsTags.chip(CLEAR_CHIP)).assertIsDisplayed().performClick()
    settle()
    assertMarkers("study", "party", "sports")
    compose.onNodeWithTag(EventsTags.chip(CLEAR_CHIP)).assertDoesNotExist()
  }

  @Test
  fun theFiltersSheetOpensWithTheDistanceOffWithoutLocation() {
    show()
    compose.onNodeWithTag(EventsTags.FILTERS_BUTTON).performClick()
    settle()
    compose.onNodeWithTag(EventsTags.FILTERS_SHEET).assertIsDisplayed()
    compose.onNodeWithTag(EventsTags.TURN_ON_LOCATION).assertExists()
  }

  private companion object {
    // The keys EventsChipRow tags its *Clear filters* and *All* chips with.
    const val CLEAR_CHIP = "CLEAR"
    const val ALL_CHIP = "ALL"
  }
}
