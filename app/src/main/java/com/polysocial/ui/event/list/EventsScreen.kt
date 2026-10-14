// Contributors: Claude (Events tab list from the Figma "Discover events" frames, #105; no matching
// event state and search field, #115).
package com.polysocial.ui.event.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.event.Event
import com.polysocial.resources.C
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.event.style
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Warning
import com.polysocial.ui.theme.WarningSoft
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object EventsTags {
  const val LIST = "events_list"
  const val SEARCH = "events_search"
  const val OFFLINE_BANNER = "events_offline_banner"
  const val EMPTY = "events_empty"
  const val NO_MATCH = "events_no_match"
  const val CLEAR_FILTERS = "events_clear_filters"
  const val ERROR = "events_error"
  const val RETRY = "events_retry"

  fun dayHeader(index: Int) = "events_day_header_$index"

  fun card(eventId: String) = "events_card_$eventId"
}

/** The Events tab's root: the upcoming public events from [EventsViewModel]. */
@Composable
fun EventsRoute(modifier: Modifier = Modifier, viewModel: EventsViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  EventsScreen(
      state,
      zone = viewModel.zone,
      onRetry = viewModel::retry,
      onClearFilters = viewModel::clearSearchAndFilters,
      onSearchChange = viewModel::setSearch,
      modifier = modifier,
  )
}

/** Draws [state]; start times and dates are shown in [zone]. */
@Composable
fun EventsScreen(
    state: EventsUiState,
    zone: ZoneId,
    onRetry: () -> Unit,
    onClearFilters: () -> Unit,
    onSearchChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val list = state.list
  if (list == EventsListState.Loading) {
    LoadingState(stringResource(R.string.loading_events), modifier)
    return
  }
  ScreenContent(modifier) {
    // One header for both states, so the search field keeps its focus and cursor when typing
    // switches the list between matching events and none.
    if (list is EventsListState.Events || list is EventsListState.NoMatch) {
      Header(state.selection, onSearchChange)
    }
    when (list) {
      EventsListState.Loading -> Unit
      is EventsListState.Empty -> {
        if (list.isOffline) OfflineBanner(Modifier.padding(horizontal = 20.dp))
        EmptyState(
            title = stringResource(R.string.events_empty_title),
            body = stringResource(R.string.events_empty_body),
            tag = EventsTags.EMPTY,
        )
      }
      is EventsListState.NoMatch -> {
        if (list.isOffline) OfflineBanner(Modifier.padding(horizontal = 20.dp))
        EmptyState(
            title = stringResource(R.string.events_no_match_title),
            body = stringResource(R.string.events_no_match_body),
            tag = EventsTags.NO_MATCH,
        ) {
          Spacer(Modifier.height(20.dp))
          ClearFiltersLink(onClearFilters)
        }
      }
      is EventsListState.Events -> EventList(list, zone)
      EventsListState.Error -> ErrorState(onRetry)
    }
  }
}

/** The tab's root once loaded; its own node, so its tag doesn't replace the tags of its content. */
@Composable
private fun ScreenContent(modifier: Modifier, content: @Composable () -> Unit) {
  Column(modifier.fillMaxSize().testTag(C.Tag.screen_events)) { content() }
}

/** The search above the list, shown while there are events to search. */
@Composable
private fun Header(selection: EventSelection, onSearchChange: (String) -> Unit) {
  EventsSearchField(
      selection.search,
      onSearchChange,
      Modifier.padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 14.dp),
  )
}

@Composable
private fun EventList(state: EventsListState.Events, zone: ZoneId) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(EventsTags.LIST),
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
  ) {
    if (state.isOffline) {
      item { OfflineBanner(Modifier.padding(bottom = 14.dp)) }
    }
    state.sections.forEachIndexed { index, section ->
      item(key = "day-$index") { DayHeader(section.date, state.today, index) }
      items(section.events, key = { it.id }) { event ->
        EventCard(event, zone, Modifier.padding(bottom = 14.dp))
      }
    }
  }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate, index: Int) {
  val text =
      when (date) {
        today -> stringResource(R.string.events_today)
        today.plusDays(1) -> stringResource(R.string.events_tomorrow)
        else -> date.format(dateFormat)
      }
  Text(
      text = text,
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
      color = Ink2,
      modifier =
          Modifier.padding(top = 3.5.dp, bottom = 14.dp).testTag(EventsTags.dayHeader(index)),
  )
}

/** An event as a Figma card: the category tile, the title, then the category and time. */
@Composable
private fun EventCard(event: Event, zone: ZoneId, modifier: Modifier = Modifier) {
  val category = event.category.style
  val start = event.startTime.atZone(zone).format(timeFormat)
  val time =
      event.endTime?.let {
        stringResource(R.string.events_time_range, start, it.atZone(zone).format(timeFormat))
      } ?: start
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .border(1.dp, Border, RoundedCornerShape(18.dp))
              .padding(14.dp)
              .testTag(EventsTags.card(event.id)),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        modifier = Modifier.size(52.dp).background(category.tileColor, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
      // The category is read from the details line below, so the icon itself is decorative.
      Icon(painterResource(category.icon), contentDescription = null, tint = category.color)
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
          text = event.title,
          style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp),
          color = Ink,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          text = stringResource(R.string.events_card_details, stringResource(category.label), time),
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
          color = Ink2,
      )
    }
  }
}

@Composable
private fun OfflineBanner(modifier: Modifier = Modifier) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .background(WarningSoft, RoundedCornerShape(14.dp))
              .padding(horizontal = 14.dp, vertical = 13.dp)
              .testTag(EventsTags.OFFLINE_BANNER),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(painterResource(R.drawable.ic_offline), contentDescription = null, tint = Warning)
    Text(
        text = stringResource(R.string.events_offline),
        style = MaterialTheme.typography.labelMedium,
        color = Warning,
    )
  }
}

/** The Figma empty state: the events icon, [title] and [body], then an optional [action]. */
@Composable
private fun EmptyState(
    title: String,
    body: String,
    tag: String,
    action: @Composable ColumnScope.() -> Unit = {},
) {
  Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp).testTag(tag),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
        modifier = Modifier.size(80.dp).background(WarningSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(R.drawable.ic_tab_events),
          contentDescription = null,
          tint = Warning,
          modifier = Modifier.size(34.dp),
      )
    }
    Spacer(Modifier.height(14.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
        color = Ink,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = Ink2,
        textAlign = TextAlign.Center,
    )
    action()
  }
}

/** Figma's underlined "Clear filters" text, with a 48 dp touch target. */
@Composable
private fun ClearFiltersLink(onClick: () -> Unit) {
  Box(
      modifier =
          Modifier.heightIn(min = 48.dp)
              .clickable(role = Role.Button, onClick = onClick)
              .padding(horizontal = 12.dp)
              .testTag(EventsTags.CLEAR_FILTERS),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = stringResource(R.string.events_clear_filters),
        style =
            MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
            ),
        color = Ink,
    )
  }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
  Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp).testTag(EventsTags.ERROR),
      verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
        text = stringResource(R.string.events_error),
        style = MaterialTheme.typography.bodyLarge,
        color = Ink2,
        textAlign = TextAlign.Center,
    )
    Button(
        onClick = onRetry,
        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Bg),
        modifier = Modifier.height(48.dp).testTag(EventsTags.RETRY),
    ) {
      Text(stringResource(R.string.events_retry), style = MaterialTheme.typography.titleSmall)
    }
  }
}
