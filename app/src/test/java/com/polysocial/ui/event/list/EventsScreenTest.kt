// Contributors: Claude (screen tests for the Events tab list, #105; no matching event, search and
// filter chips and Filters sheet, #115).
package com.polysocial.ui.event.list

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventsScreenTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val lausanne = ZoneId.of("Europe/Zurich")

  /** Sunday 11 October 2026. */
  private val today = LocalDate.of(2026, 10, 11)

  private fun string(id: Int) = context.getString(id)

  private fun event(
      id: String,
      title: String,
      start: LocalDateTime,
      end: LocalDateTime? = null,
      category: EventCategory = EventCategory.PARTY,
  ) =
      validEvent(
              title = title,
              startTime = start.atZone(lausanne).toInstant(),
              endTime = end?.atZone(lausanne)?.toInstant(),
          )
          .copy(id = id, category = category)

  private var retries = 0
  private var clears = 0
  private val searches = mutableListOf<String>()
  private val dates = mutableListOf<DateChoice>()
  private val toggled = mutableListOf<EventCategory>()
  private var sheetClears = 0
  private val distances = mutableListOf<Int?>()

  private fun show(list: EventsListState, selection: EventSelection = EventSelection()) {
    composeTestRule.setContent {
      PolySocialTheme {
        EventsScreen(
            EventsUiState(list, selection),
            zone = lausanne,
            today = today,
            onRetry = { retries++ },
            onClearFilters = { clears++ },
            onSearchChange = { searches += it },
            onSelectDate = { dates += it },
            onToggleCategory = { toggled += it },
            onClearSheetFilters = { sheetClears++ },
            onSelectDistance = { distances += it },
        )
      }
    }
  }

  @Test
  fun loading_showsTheSharedLoadingState() {
    show(EventsListState.Loading)

    composeTestRule.onNodeWithTag(C.Tag.loading_state).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.loading_events)).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(C.Tag.screen_events).assertCountEquals(0)
  }

  @Test
  fun events_areListedUnderTheirDayHeadings() {
    val party = event("party", "Balélec warm-up", LocalDateTime.of(2026, 10, 11, 20, 0))
    val climbing = event("climbing", "Climbing", LocalDateTime.of(2026, 10, 12, 10, 0))
    val film = event("film", "Ciné-club: Dune", LocalDateTime.of(2026, 10, 14, 19, 30))
    show(
        EventsListState.Events(
            listOf(
                EventDaySection(today, listOf(party)),
                EventDaySection(today.plusDays(1), listOf(climbing)),
                EventDaySection(LocalDate.of(2026, 10, 14), listOf(film)),
            ),
            today = today,
            isOffline = false,
        )
    )

    composeTestRule.onNodeWithTag(C.Tag.screen_events).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.dayHeader(0)).assertTextEquals("Today")
    composeTestRule.onNodeWithTag(EventsTags.dayHeader(1)).assertTextEquals("Tomorrow")
    composeTestRule.onNodeWithTag(EventsTags.dayHeader(2)).assertTextEquals("Wed 14 Oct")
    composeTestRule.onNodeWithTag(EventsTags.card("party")).assertIsDisplayed()
    composeTestRule.onNodeWithText("Balélec warm-up").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.card("climbing")).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.card("film")).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.OFFLINE_BANNER).assertCountEquals(0)
  }

  @Test
  fun tomorrow_followsTheCalendarAcrossMonths() {
    val halloween = LocalDate.of(2026, 10, 31)
    val brunch = event("brunch", "Brunch", LocalDateTime.of(2026, 11, 1, 11, 0))
    val lecture = event("lecture", "Lecture", LocalDateTime.of(2026, 11, 2, 8, 15))
    show(
        EventsListState.Events(
            listOf(
                EventDaySection(LocalDate.of(2026, 11, 1), listOf(brunch)),
                EventDaySection(LocalDate.of(2026, 11, 2), listOf(lecture)),
            ),
            today = halloween,
            isOffline = false,
        )
    )

    composeTestRule.onNodeWithTag(EventsTags.dayHeader(0)).assertTextEquals("Tomorrow")
    composeTestRule.onNodeWithTag(EventsTags.dayHeader(1)).assertTextEquals("Mon 2 Nov")
  }

  @Test
  fun aCard_showsItsCategoryAndStartTime_andTheEndTimeWhenThereIsOne() {
    val withEnd =
        event(
            "with-end",
            "Party",
            LocalDateTime.of(2026, 10, 11, 20, 0),
            end = LocalDateTime.of(2026, 10, 11, 23, 30),
        )
    val withoutEnd =
        event(
            "without-end",
            "Climbing",
            LocalDateTime.of(2026, 10, 11, 9, 5),
            category = EventCategory.SPORTS,
        )
    show(
        EventsListState.Events(
            listOf(EventDaySection(today, listOf(withoutEnd, withEnd))),
            today = today,
            isOffline = false,
        )
    )

    composeTestRule.onNodeWithText("Party · 20:00 – 23:30").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sports · 09:05").assertIsDisplayed()
  }

  @Test
  fun startTimes_areShownInTheGivenTimeZone() {
    // 18:00 UTC is 20:00 in Lausanne in October (summer time).
    val event =
        validEvent(
                startTime =
                    LocalDateTime.of(2026, 10, 11, 18, 0).atZone(ZoneId.of("UTC")).toInstant(),
                endTime = null,
            )
            .copy(id = "utc", category = EventCategory.STUDY)
    show(
        EventsListState.Events(
            listOf(EventDaySection(today, listOf(event))),
            today = today,
            isOffline = false,
        )
    )

    composeTestRule.onNodeWithText("Study · 20:00").assertIsDisplayed()
  }

  @Test
  fun everyCategory_isNamedOnItsCard() {
    val start = LocalDateTime.of(2026, 10, 11, 18, 0)
    val events =
        EventCategory.entries.map { category ->
          event(category.name, category.name, start, category = category)
        }
    show(
        EventsListState.Events(
            listOf(EventDaySection(today, events)),
            today = today,
            isOffline = false,
        )
    )

    listOf(
            R.string.event_category_party,
            R.string.event_category_sports,
            R.string.event_category_culture,
            R.string.event_category_study,
            R.string.event_category_other,
        )
        .forEach { label ->
          composeTestRule.onNode(hasText("${string(label)} · 18:00")).assertExists()
        }
  }

  @Test
  fun offlineEvents_showTheOfflineBannerAboveTheList() {
    val event = event("party", "Party", LocalDateTime.of(2026, 10, 11, 20, 0))
    show(
        EventsListState.Events(
            listOf(EventDaySection(today, listOf(event))),
            today = today,
            isOffline = true,
        )
    )

    composeTestRule.onNodeWithTag(EventsTags.OFFLINE_BANNER).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.events_offline)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.card("party")).assertIsDisplayed()
  }

  @Test
  fun noEvents_showTheEmptyState() {
    show(EventsListState.Empty(isOffline = false))

    composeTestRule.onNodeWithTag(EventsTags.EMPTY).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.events_empty_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.events_empty_body)).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.OFFLINE_BANNER).assertCountEquals(0)
    composeTestRule.onAllNodesWithTag(EventsTags.LIST).assertCountEquals(0)
  }

  @Test
  fun noEventsWhileOffline_showTheBannerAboveTheEmptyState() {
    show(EventsListState.Empty(isOffline = true))

    composeTestRule.onNodeWithTag(EventsTags.OFFLINE_BANNER).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.EMPTY).assertIsDisplayed()
  }

  @Test
  fun noMatchingEvent_showsItsStateAndClearFiltersCallsBack() {
    show(EventsListState.NoMatch(isOffline = false))

    composeTestRule.onNodeWithTag(EventsTags.NO_MATCH).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.events_no_match_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.events_no_match_body)).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.EMPTY).assertCountEquals(0)
    composeTestRule.onAllNodesWithTag(EventsTags.OFFLINE_BANNER).assertCountEquals(0)
    composeTestRule.onNodeWithTag(EventsTags.CLEAR_FILTERS).performClick()

    assertEquals(1, clears)
  }

  @Test
  fun noMatchingEventWhileOffline_showsTheBannerAboveIt() {
    show(EventsListState.NoMatch(isOffline = true))

    composeTestRule.onNodeWithTag(EventsTags.OFFLINE_BANNER).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.NO_MATCH).assertIsDisplayed()
  }

  @Test
  fun anError_showsTheMessageAndRetryCallsBack() {
    show(EventsListState.Error)

    composeTestRule.onNodeWithTag(EventsTags.ERROR).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.events_error)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.RETRY).performClick()

    assertEquals(1, retries)
  }

  @Test
  fun theRoute_showsTheViewModelsEvents_andRetryReloadsThemAfterAnError() {
    val repository = FakeEventRepository()
    val clock = Clock.fixed(repository.now, lausanne)
    val tomorrow =
        validEvent(title = "Tomorrow's event", startTime = repository.now.plusSeconds(24 * 3600))
            .copy(id = "tomorrow")
    repository.seed(tomorrow)
    repository.readFailure = true
    val network =
        object : NetworkMonitor {
          override fun isOnline() = true
        }
    val viewModel =
        EventsViewModel(repository, network, FakeLocationService(), clock, SavedStateHandle())
    composeTestRule.setContent { PolySocialTheme { EventsRoute(viewModel = viewModel) } }
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeTestRule.onNodeWithTag(EventsTags.ERROR).assertIsDisplayed()

    repository.readFailure = false
    composeTestRule.onNodeWithTag(EventsTags.RETRY).performClick()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    composeTestRule.onNodeWithTag(EventsTags.card("tomorrow")).assertIsDisplayed()
    composeTestRule.onNodeWithText("Tomorrow's event").assertIsDisplayed()
  }

  // ---- Search ----

  private fun someEvents() =
      EventsListState.Events(
          listOf(
              EventDaySection(
                  today,
                  listOf(event("party", "Balélec warm-up", today.atTime(20, 0))),
              )
          ),
          today = today,
          isOffline = false,
      )

  @Test
  fun theSearchField_showsThePlaceholder_andTypingCallsBack() {
    show(someEvents())

    composeTestRule.onNodeWithTag(EventsTags.SEARCH).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.events_search_placeholder)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.SEARCH).performTextInput("climb")

    assertEquals(listOf("climb"), searches)
  }

  @Test
  fun theSearchField_showsTheCurrentSearch() {
    show(someEvents(), EventSelection(search = "warm"))

    composeTestRule.onNodeWithTag(EventsTags.SEARCH).assertTextEquals("warm")
  }

  @Test
  fun theSearchField_staysWhenNothingMatches_soTheSearchCanBeChanged() {
    show(EventsListState.NoMatch(isOffline = false), EventSelection(search = "hackathon"))

    composeTestRule.onNodeWithTag(EventsTags.SEARCH).assertTextEquals("hackathon")
  }

  @Test
  fun theSearchField_isHidden_whenThereIsNothingToSearch() {
    show(EventsListState.Empty(isOffline = false))
    composeTestRule.onAllNodesWithTag(EventsTags.SEARCH).assertCountEquals(0)
  }

  @Test
  fun theSearchField_isHidden_afterAnError() {
    show(EventsListState.Error)
    composeTestRule.onAllNodesWithTag(EventsTags.SEARCH).assertCountEquals(0)
  }

  @Test
  fun aSearch_narrowsTheList_andClearingItRestoresTheFullList() {
    val repository = FakeEventRepository()
    val tomorrow = repository.now.plusSeconds(24 * 3600)
    repository.seed(
        validEvent(title = "Climbing session", startTime = tomorrow).copy(id = "climbing"),
        validEvent(title = "Balélec warm-up", startTime = tomorrow.plusSeconds(3600))
            .copy(id = "party"),
    )
    val network =
        object : NetworkMonitor {
          override fun isOnline() = true
        }
    val viewModel =
        EventsViewModel(
            repository,
            network,
            FakeLocationService(),
            Clock.fixed(repository.now, lausanne),
            SavedStateHandle(),
        )
    composeTestRule.setContent { PolySocialTheme { EventsRoute(viewModel = viewModel) } }
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    composeTestRule.onNodeWithTag(EventsTags.SEARCH).performTextInput("climb")

    composeTestRule.onNodeWithTag(EventsTags.card("climbing")).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.card("party")).assertCountEquals(0)

    composeTestRule.onNodeWithTag(EventsTags.SEARCH).performTextClearance()

    composeTestRule.onNodeWithTag(EventsTags.card("climbing")).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.card("party")).assertIsDisplayed()
  }

  @Test
  fun noMatch_clearFiltersRestoresTheFullList() {
    val repository = FakeEventRepository()
    val tomorrow = repository.now.plusSeconds(24 * 3600)
    repository.seed(
        validEvent(title = "Climbing session", startTime = tomorrow)
            .copy(id = "climbing", category = EventCategory.SPORTS),
        validEvent(title = "Balélec warm-up", startTime = tomorrow.plusSeconds(3600))
            .copy(id = "party", category = EventCategory.PARTY),
    )
    val network =
        object : NetworkMonitor {
          override fun isOnline() = true
        }
    val viewModel =
        EventsViewModel(
            repository,
            network,
            FakeLocationService(),
            Clock.fixed(repository.now, lausanne),
            SavedStateHandle(),
        )
    composeTestRule.setContent { PolySocialTheme { EventsRoute(viewModel = viewModel) } }
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    // A search and a category that no event has together.
    composeTestRule.onNodeWithTag(EventsTags.SEARCH).performTextInput("climb")
    composeTestRule.onNodeWithTag(EventsTags.chip("PARTY")).performClick()
    composeTestRule.onNodeWithTag(EventsTags.NO_MATCH).assertIsDisplayed()

    composeTestRule.onNodeWithTag(EventsTags.CLEAR_FILTERS).performClick()

    composeTestRule.onNodeWithTag(EventsTags.card("climbing")).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.card("party")).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.NO_MATCH).assertCountEquals(0)
    // The search is empty again, so its placeholder shows.
    composeTestRule
        .onNodeWithTag(EventsTags.SEARCH)
        .assertTextEquals(string(R.string.events_search_placeholder), "")
    composeTestRule.onNodeWithTag(EventsTags.chip("ALL")).assertIsSelected()
  }

  // Typing that leaves no match used to draw the field again in another branch, which lost its
  // focus and put the cursor back at the start, so the next letters went in the wrong place.
  @Test
  fun theSearchField_keepsItsFocus_whenTypingLeavesNoMatch() {
    val repository = FakeEventRepository()
    repository.seed(
        validEvent(title = "Climbing session", startTime = repository.now.plusSeconds(24 * 3600))
            .copy(id = "climbing")
    )
    val network =
        object : NetworkMonitor {
          override fun isOnline() = true
        }
    val viewModel =
        EventsViewModel(
            repository,
            network,
            FakeLocationService(),
            Clock.fixed(repository.now, lausanne),
            SavedStateHandle(),
        )
    composeTestRule.setContent { PolySocialTheme { EventsRoute(viewModel = viewModel) } }
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    val field = composeTestRule.onNodeWithTag(EventsTags.SEARCH)

    field.performClick()
    field.performTextInput("climbx")

    composeTestRule.onNodeWithTag(EventsTags.NO_MATCH).assertIsDisplayed()
    field.assertIsFocused()
    field.performTextInput("y")
    field.assertTextEquals("climbxy")
  }

  // ---- Filter chips ----

  private fun chip(key: String) = composeTestRule.onNodeWithTag(EventsTags.chip(key))

  private fun left(key: String) = chip(key).fetchSemanticsNode().boundsInRoot.left

  @Test
  fun withoutFilters_allIsOn_andTheQuickFiltersFollowInFigmasOrder() {
    show(someEvents())

    chip("ALL").assertIsSelected()
    for (key in listOf("TODAY", "THIS_WEEK", "PARTY", "SPORTS")) chip(key).assertIsNotSelected()
    chip("TODAY").assertTextEquals(string(R.string.events_filter_today))
    val order = listOf("ALL", "TODAY", "THIS_WEEK", "PARTY", "SPORTS").map { left(it) }
    assertEquals(order.sorted(), order)
  }

  @Test
  fun activeFilters_comeRightAfterAll_withTheDateFirst() {
    show(
        someEvents(),
        EventSelection(categories = setOf(EventCategory.SPORTS), date = DateChoice.ThisWeek),
    )

    chip("ALL").assertIsNotSelected()
    chip("THIS_WEEK").assertIsSelected()
    chip("SPORTS").assertIsSelected()
    chip("TODAY").assertIsNotSelected()
    val order = listOf("ALL", "THIS_WEEK", "SPORTS", "TODAY", "PARTY").map { left(it) }
    assertEquals(order.sorted(), order)
  }

  @Test
  fun tappingAQuickFilter_turnsItOn() {
    show(someEvents())

    chip("TODAY").performClick()
    chip("PARTY").performClick()

    assertEquals(listOf(DateChoice.Today), dates)
    assertEquals(listOf(EventCategory.PARTY), toggled)
  }

  @Test
  fun tappingAnActiveFilter_removesIt() {
    show(
        someEvents(),
        EventSelection(categories = setOf(EventCategory.STUDY), date = DateChoice.Today),
    )

    chip("TODAY").performClick()
    chip("STUDY").performClick()

    assertEquals(listOf(DateChoice.Any), dates)
    assertEquals(listOf(EventCategory.STUDY), toggled)
  }

  @Test
  fun tappingAll_clearsTheSearchAndFilters() {
    show(someEvents(), EventSelection(search = "party", categories = setOf(EventCategory.PARTY)))

    chip("ALL").performClick()

    assertEquals(1, clears)
  }

  @Test
  fun theChips_stayWhenNothingMatches_soAFilterCanBeRemoved() {
    show(EventsListState.NoMatch(isOffline = false), EventSelection(date = DateChoice.Today))

    chip("TODAY").assertIsSelected()
  }

  @Test
  fun theChips_areHidden_whenThereIsNothingToFilter() {
    show(EventsListState.Empty(isOffline = false))

    composeTestRule.onAllNodesWithTag(EventsTags.CHIPS).assertCountEquals(0)
  }

  @Test
  fun selectingACategory_showsOnlyItsEvents_andAllShowsThemAgain() {
    val repository = FakeEventRepository()
    val tomorrow = repository.now.plusSeconds(24 * 3600)
    repository.seed(
        validEvent(title = "Climbing session", startTime = tomorrow)
            .copy(id = "climbing", category = EventCategory.SPORTS),
        validEvent(title = "Balélec warm-up", startTime = tomorrow.plusSeconds(3600))
            .copy(id = "party", category = EventCategory.PARTY),
    )
    val network =
        object : NetworkMonitor {
          override fun isOnline() = true
        }
    val viewModel =
        EventsViewModel(
            repository,
            network,
            FakeLocationService(),
            Clock.fixed(repository.now, lausanne),
            SavedStateHandle(),
        )
    composeTestRule.setContent { PolySocialTheme { EventsRoute(viewModel = viewModel) } }
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

    chip("SPORTS").performClick()

    composeTestRule.onNodeWithTag(EventsTags.card("climbing")).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.card("party")).assertCountEquals(0)
    chip("SPORTS").assertIsSelected()

    chip("ALL").performClick()

    composeTestRule.onNodeWithTag(EventsTags.card("climbing")).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.card("party")).assertIsDisplayed()
    chip("ALL").assertIsSelected()
  }

  @Test
  fun all_clearsTheSearchToo() {
    val repository = FakeEventRepository()
    val tomorrow = repository.now.plusSeconds(24 * 3600)
    repository.seed(
        validEvent(title = "Climbing session", startTime = tomorrow)
            .copy(id = "climbing", category = EventCategory.SPORTS),
        validEvent(title = "Balélec warm-up", startTime = tomorrow.plusSeconds(3600))
            .copy(id = "party", category = EventCategory.PARTY),
    )
    val network =
        object : NetworkMonitor {
          override fun isOnline() = true
        }
    val viewModel =
        EventsViewModel(
            repository,
            network,
            FakeLocationService(),
            Clock.fixed(repository.now, lausanne),
            SavedStateHandle(),
        )
    composeTestRule.setContent { PolySocialTheme { EventsRoute(viewModel = viewModel) } }
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeTestRule.onNodeWithTag(EventsTags.SEARCH).performTextInput("climb")
    chip("SPORTS").performClick()

    chip("ALL").performClick()

    composeTestRule.onNodeWithTag(EventsTags.card("climbing")).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.card("party")).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(EventsTags.SEARCH)
        .assertTextEquals(string(R.string.events_search_placeholder), "")
  }

  @Test
  fun aPickedDay_showsAsAnActiveChipAfterAll_andTappingItRemovesIt() {
    show(someEvents(), EventSelection(date = DateChoice.Day(LocalDate.of(2026, 10, 17))))

    chip("DAY").assertIsSelected().assertTextEquals("Sat 17 Oct")
    chip("ALL").assertIsNotSelected()
    chip("TODAY").assertIsNotSelected()
    val order = listOf("ALL", "DAY", "TODAY").map { left(it) }
    assertEquals(order.sorted(), order)

    chip("DAY").performClick()

    assertEquals(listOf<DateChoice>(DateChoice.Any), dates)
  }

  @Test
  fun withoutAPickedDay_thereIsNoDayChip() {
    show(someEvents(), EventSelection(date = DateChoice.Today))

    composeTestRule.onAllNodesWithTag(EventsTags.chip("DAY")).assertCountEquals(0)
  }

  // ---- Filters sheet ----

  private fun sheetChip(key: String) = composeTestRule.onNodeWithTag(EventsTags.sheetChip(key))

  private fun openFilters() =
      composeTestRule.onNodeWithTag(EventsTags.FILTERS_BUTTON).performClick()

  private fun twoEvents() =
      EventsListState.Events(
          listOf(
              EventDaySection(
                  today,
                  listOf(
                      event("party", "Balélec warm-up", today.atTime(20, 0)),
                      event("film", "Ciné-club", today.atTime(21, 0)),
                  ),
              )
          ),
          today = today,
          isOffline = false,
      )

  @Test
  fun theFiltersButton_saysHowManyFiltersAreOn() {
    show(
        someEvents(),
        EventSelection(
            search = "party",
            categories = setOf(EventCategory.PARTY, EventCategory.STUDY),
            date = DateChoice.Today,
        ),
    )

    // The search has its own field, so it doesn't count.
    composeTestRule
        .onNodeWithTag(EventsTags.FILTERS_BUTTON)
        .assertContentDescriptionEquals("Filters, 3 active")
  }

  @Test
  fun withoutFilters_theFiltersButtonHasNoCount() {
    show(someEvents(), EventSelection(search = "party"))

    composeTestRule
        .onNodeWithTag(EventsTags.FILTERS_BUTTON)
        .assertContentDescriptionEquals("Filters")
    composeTestRule.onAllNodesWithTag(EventsTags.FILTERS_SHEET).assertCountEquals(0)
  }

  @Test
  fun theFiltersButton_opensTheSheet_showingTheCurrentSelection() {
    show(
        someEvents(),
        EventSelection(
            categories = setOf(EventCategory.SPORTS),
            date = DateChoice.ThisWeek,
            maxDistanceMeters = 1_000,
        ),
    )

    openFilters()

    composeTestRule.onNodeWithTag(EventsTags.FILTERS_SHEET).assertIsDisplayed()
    sheetChip("THIS_WEEK").assertIsSelected()
    sheetChip("DATE_ANY").assertIsNotSelected()
    sheetChip("DISTANCE_1000").assertIsSelected().assertTextEquals("1 km")
    sheetChip("DISTANCE_500").assertIsNotSelected().assertTextEquals("500 m")
    sheetChip("SPORTS").assertIsSelected()
    sheetChip("PARTY").assertIsNotSelected()
    composeTestRule.onAllNodesWithTag(EventsTags.DAY_STRIP).assertCountEquals(0)
  }

  @Test
  fun theSheetChips_callBackWithTheirChoice() {
    show(someEvents())
    openFilters()

    sheetChip("TODAY").performClick()
    sheetChip("DISTANCE_2000").performClick()
    sheetChip("DISTANCE_ANY").performClick()
    sheetChip("CULTURE").performClick()
    composeTestRule.onNodeWithTag(EventsTags.FILTERS_CLEAR).performClick()

    assertEquals(listOf<DateChoice>(DateChoice.Today), dates)
    assertEquals(listOf(2_000, null), distances)
    assertEquals(listOf(EventCategory.CULTURE), toggled)
    assertEquals(1, sheetClears)
    // Clear keeps the sheet open, so more filters can be picked.
    composeTestRule.onNodeWithTag(EventsTags.FILTERS_SHEET).assertIsDisplayed()
  }

  @Test
  fun pickADate_opensTheNext14Days_andTappingADayPicksIt() {
    show(someEvents())
    openFilters()

    sheetChip("PICK_DATE").performClick()

    sheetChip("PICK_DATE").assertIsSelected()
    sheetChip("DATE_ANY").assertIsNotSelected()
    val strip = composeTestRule.onNodeWithTag(EventsTags.DAY_STRIP)
    composeTestRule.onNodeWithTag(EventsTags.day(today)).assertIsDisplayed()
    strip.performScrollToNode(hasTestTag(EventsTags.day(today.plusDays(13))))
    composeTestRule.onNodeWithTag(EventsTags.day(today.plusDays(13))).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.day(today.plusDays(14))).assertCountEquals(0)
    composeTestRule.onAllNodesWithTag(EventsTags.day(today.minusDays(1))).assertCountEquals(0)

    strip.performScrollToNode(hasTestTag(EventsTags.day(today.plusDays(2))))
    composeTestRule.onNodeWithTag(EventsTags.day(today.plusDays(2))).performClick()

    assertEquals(listOf<DateChoice>(DateChoice.Day(today.plusDays(2))), dates)
  }

  @Test
  fun aPickedDay_isSelectedInTheStrip_andTappingItAgainDropsIt() {
    val tuesday = today.plusDays(2)
    show(someEvents(), EventSelection(date = DateChoice.Day(tuesday)))
    openFilters()

    sheetChip("PICK_DATE").assertIsSelected()
    composeTestRule
        .onNodeWithTag(EventsTags.day(tuesday))
        .assertIsSelected()
        .assertTextEquals("Tue", "13")
    composeTestRule.onNodeWithTag(EventsTags.day(today)).assertIsNotSelected()

    composeTestRule.onNodeWithTag(EventsTags.day(tuesday)).performClick()

    assertEquals(listOf<DateChoice>(DateChoice.Any), dates)
  }

  @Test
  fun tappingPickADateAgain_closesTheStrip() {
    show(someEvents())
    openFilters()

    sheetChip("PICK_DATE").performClick()
    sheetChip("PICK_DATE").performClick()

    composeTestRule.onAllNodesWithTag(EventsTags.DAY_STRIP).assertCountEquals(0)
    sheetChip("PICK_DATE").assertIsNotSelected()
  }

  @Test
  fun theShowButton_countsTheShownEvents_andClosesTheSheet() {
    show(twoEvents(), EventSelection(categories = setOf(EventCategory.PARTY)))
    openFilters()

    composeTestRule.onNodeWithTag(EventsTags.FILTERS_SHOW).assertTextEquals("Show 2 events")
    composeTestRule.onNodeWithTag(EventsTags.FILTERS_SHOW).performClick()

    composeTestRule.onAllNodesWithTag(EventsTags.FILTERS_SHEET).assertCountEquals(0)
  }

  @Test
  fun theShowButton_saysOneEvent_orNoneWhenNothingMatches() {
    var list: EventsListState by mutableStateOf(someEvents())
    composeTestRule.setContent {
      PolySocialTheme {
        EventsScreen(
            EventsUiState(list),
            zone = lausanne,
            today = today,
            onRetry = {},
            onClearFilters = {},
            onSearchChange = {},
            onSelectDate = {},
            onToggleCategory = {},
            onClearSheetFilters = {},
            onSelectDistance = {},
        )
      }
    }
    openFilters()

    composeTestRule.onNodeWithTag(EventsTags.FILTERS_SHOW).assertTextEquals("Show 1 event")
    list = EventsListState.NoMatch(isOffline = false)
    composeTestRule.onNodeWithTag(EventsTags.FILTERS_SHOW).assertTextEquals("Show 0 events")
  }

  @Test
  fun theSheet_staysOpenAcrossRotation() {
    val restoration = StateRestorationTester(composeTestRule)
    restoration.setContent {
      PolySocialTheme {
        EventsScreen(
            EventsUiState(someEvents()),
            zone = lausanne,
            today = today,
            onRetry = {},
            onClearFilters = {},
            onSearchChange = {},
            onSelectDate = {},
            onToggleCategory = {},
            onClearSheetFilters = {},
            onSelectDistance = {},
        )
      }
    }
    openFilters()

    restoration.emulateSavedInstanceStateRestore()

    composeTestRule.onNodeWithTag(EventsTags.FILTERS_SHEET).assertIsDisplayed()
  }

  @Test
  fun aCategoryPickedInTheSheet_narrowsTheListRightAway() {
    val repository = FakeEventRepository()
    val tomorrow = repository.now.plusSeconds(24 * 3600)
    repository.seed(
        validEvent(title = "Climbing session", startTime = tomorrow)
            .copy(id = "climbing", category = EventCategory.SPORTS),
        validEvent(title = "Balélec warm-up", startTime = tomorrow.plusSeconds(3600))
            .copy(id = "party", category = EventCategory.PARTY),
    )
    val network =
        object : NetworkMonitor {
          override fun isOnline() = true
        }
    val viewModel =
        EventsViewModel(
            repository,
            network,
            FakeLocationService(),
            Clock.fixed(repository.now, lausanne),
            SavedStateHandle(),
        )
    composeTestRule.setContent { PolySocialTheme { EventsRoute(viewModel = viewModel) } }
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    openFilters()

    sheetChip("SPORTS").performClick()

    composeTestRule.onNodeWithTag(EventsTags.FILTERS_SHOW).assertTextEquals("Show 1 event")
    composeTestRule.onNodeWithTag(EventsTags.FILTERS_SHOW).performClick()
    composeTestRule.onNodeWithTag(EventsTags.card("climbing")).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventsTags.card("party")).assertCountEquals(0)
    composeTestRule
        .onNodeWithTag(EventsTags.FILTERS_BUTTON)
        .assertContentDescriptionEquals("Filters, 1 active")
  }

  @Test
  fun aDistance_showsAsAnActiveChip_andTappingItRemovesIt() {
    show(
        someEvents(),
        EventSelection(
            categories = setOf(EventCategory.SPORTS),
            date = DateChoice.Day(LocalDate.of(2026, 10, 17)),
            maxDistanceMeters = 2_000,
        ),
    )

    // Figma's order: the day, the distance, then the categories.
    chip("DISTANCE").assertIsSelected().assertTextEquals("≤ 2 km")
    val order = listOf("ALL", "DAY", "DISTANCE", "SPORTS").map { left(it) }
    assertEquals(order.sorted(), order)

    chip("DISTANCE").performClick()

    assertEquals(listOf<Int?>(null), distances)
  }

  @Test
  fun withoutADistance_thereIsNoDistanceChip() {
    show(someEvents())

    composeTestRule.onAllNodesWithTag(EventsTags.chip("DISTANCE")).assertCountEquals(0)
  }
}
