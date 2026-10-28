// Contributors: Claude (Events tab list from the Figma "Discover events" frames, #105; no matching
// event state, search field, filter chips, Filters sheet and its location permission, #115;
// opening an event's detail, #112; banner and states shared with the association tab, #132; an
// association's badge and name on its cards, #128).
// Contributors: Claude (the shared offline banner, #251).
package com.polysocial.ui.event.list

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.resources.C
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.common.OfflineBanner
import com.polysocial.ui.event.VerifiedPill
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
  const val CHIPS = "events_chips"
  const val FILTERS_BUTTON = "events_filters_button"
  const val FILTERS_SHEET = "events_filters_sheet"
  const val FILTERS_CLEAR = "events_filters_clear"
  const val FILTERS_SHOW = "events_filters_show"
  const val DAY_STRIP = "events_day_strip"
  const val LOCATION_OFF = "events_location_off"
  const val TURN_ON_LOCATION = "events_turn_on_location"
  const val LOCATING = "events_locating"
  const val OFFLINE_BANNER = "events_offline_banner"
  const val EMPTY = "events_empty"
  const val NO_MATCH = "events_no_match"
  const val CLEAR_FILTERS = "events_clear_filters"
  const val ERROR = "events_error"
  const val RETRY = "events_retry"

  fun dayHeader(index: Int) = "events_day_header_$index"

  fun card(eventId: String) = "events_card_$eventId"

  /** The details line of the card of [eventId]: category, time and an association's name. */
  fun details(eventId: String) = "events_card_details_$eventId"

  /** The Verified badge on the card of an association's event [eventId] (#128). */
  fun verified(eventId: String) = "events_card_verified_$eventId"

  /**
   * A filter chip: `ALL`, `TODAY`, `THIS_WEEK`, `DAY` (a picked day), `DISTANCE` or an
   * `EventCategory` name.
   */
  fun chip(key: String) = "events_chip_$key"

  /**
   * A chip of the Filters sheet: `DATE_ANY`, `TODAY`, `THIS_WEEK`, `PICK_DATE`, `DISTANCE_ANY`,
   * `DISTANCE_<meters>` or an `EventCategory` name.
   */
  fun sheetChip(key: String) = "events_sheet_chip_$key"

  /** A day of the Filters sheet's 14-day strip. */
  fun day(date: LocalDate) = "events_day_$date"
}

/**
 * The Events tab's root: the upcoming public events from [EventsViewModel]. It is also the Android
 * boundary for the distance filter's location permission: it shows the permission dialog or opens
 * the app's settings when *Turn on* asks for it, and tells the ViewModel when the tab resumes and
 * pauses.
 *
 * @param showRationale Android's `shouldShowRequestPermissionRationale` for approximate location;
 *   tests replace it.
 */
@Composable
fun EventsRoute(
    onEventClick: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventsViewModel = hiltViewModel(),
    showRationale: (() -> Boolean)? = null,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val activity = LocalActivity.current
  val context = LocalContext.current
  val permissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onPermissionResult()
      }
  LifecycleResumeEffect(viewModel) {
    viewModel.onResume()
    onPauseOrDispose { viewModel.onPause() }
  }
  EventsScreen(
      state,
      zone = viewModel.zone,
      today = viewModel.today,
      onRetry = viewModel::retry,
      onClearFilters = viewModel::clearSearchAndFilters,
      onSearchChange = viewModel::setSearch,
      onSelectDate = viewModel::selectDate,
      onToggleCategory = viewModel::toggleCategory,
      onClearSheetFilters = viewModel::clearFilters,
      onSelectDistance = viewModel::selectDistance,
      onTurnOnLocation = {
        val rationale =
            showRationale?.invoke()
                ?: (activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
        when (viewModel.turnOnLocation(rationale)) {
          LocationAction.NONE -> Unit
          LocationAction.REQUEST_PERMISSION ->
              permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
          LocationAction.OPEN_SETTINGS ->
              context.startActivity(
                  Intent(
                      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                      Uri.fromParts("package", context.packageName, null),
                  )
              )
        }
      },
      modifier = modifier,
      onEventClick = onEventClick,
  )
}

/**
 * Draws [state]; start times and dates are shown in [zone]. The Filters sheet lists the 14 days
 * from [today]; whether it is open is kept across rotation. Tapping a card calls [onEventClick].
 */
@Composable
fun EventsScreen(
    state: EventsUiState,
    zone: ZoneId,
    today: LocalDate,
    onRetry: () -> Unit,
    onClearFilters: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSelectDate: (DateChoice) -> Unit,
    onToggleCategory: (EventCategory) -> Unit,
    onClearSheetFilters: () -> Unit,
    onSelectDistance: (Int?) -> Unit,
    onTurnOnLocation: () -> Unit,
    onEventClick: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val list = state.list
  var showFilters by rememberSaveable { mutableStateOf(false) }
  if (showFilters) {
    EventsFiltersSheet(
        selection = state.selection,
        today = today,
        shownCount = (list as? EventsListState.Events)?.sections?.sumOf { it.events.size } ?: 0,
        location = state.location,
        onSelectDate = onSelectDate,
        onSelectDistance = onSelectDistance,
        onTurnOnLocation = onTurnOnLocation,
        onToggleCategory = onToggleCategory,
        onClearFilters = onClearSheetFilters,
        onDismiss = { showFilters = false },
    )
  }
  if (list == EventsListState.Loading) {
    LoadingState(stringResource(R.string.loading_events), modifier)
    return
  }
  ScreenContent(modifier) {
    // One header for both states, so the search field keeps its focus and cursor when typing
    // switches the list between matching events and none.
    if (list is EventsListState.Events || list is EventsListState.NoMatch) {
      Header(
          state.selection,
          onSearchChange,
          onOpenFilters = { showFilters = true },
          onSelectDate,
          onToggleCategory,
          onClearFilters,
          onSelectDistance,
      )
    }
    when (list) {
      EventsListState.Loading -> Unit
      is EventsListState.Empty -> {
        if (list.isOffline) EventsOfflineBanner(Modifier.padding(horizontal = 20.dp))
        EmptyState(
            title = stringResource(R.string.events_empty_title),
            body = stringResource(R.string.events_empty_body),
            tag = EventsTags.EMPTY,
        )
      }
      is EventsListState.NoMatch -> {
        if (list.isOffline) EventsOfflineBanner(Modifier.padding(horizontal = 20.dp))
        EmptyState(
            title = stringResource(R.string.events_no_match_title),
            body = stringResource(R.string.events_no_match_body),
            tag = EventsTags.NO_MATCH,
        ) {
          Spacer(Modifier.height(20.dp))
          ClearFiltersLink(onClearFilters)
        }
      }
      is EventsListState.Events -> EventList(list, zone, state.associationNames, onEventClick)
      EventsListState.Error -> EventsErrorState(onRetry)
    }
  }
}

/** The tab's root once loaded; its own node, so its tag doesn't replace the tags of its content. */
@Composable
private fun ScreenContent(modifier: Modifier, content: @Composable () -> Unit) {
  Column(modifier.fillMaxSize().testTag(C.Tag.screen_events)) { content() }
}

/**
 * The search and the filter chips above the list, shown while there are events to search. The
 * chips' 48 dp touch targets add 6 dp above and below, so the gaps around them are 6 dp smaller
 * than Figma's 14 dp.
 */
@Composable
private fun Header(
    selection: EventSelection,
    onSearchChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    onSelectDate: (DateChoice) -> Unit,
    onToggleCategory: (EventCategory) -> Unit,
    onClearFilters: () -> Unit,
    onSelectDistance: (Int?) -> Unit,
) {
  EventsSearchField(
      selection.search,
      onSearchChange,
      selection.activeFilterCount,
      onOpenFilters,
      Modifier.padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 8.dp),
  )
  EventsChipRow(
      selection,
      onSelectDate,
      onToggleCategory,
      onClearFilters,
      onSelectDistance,
      Modifier.padding(bottom = 8.dp),
  )
}

@Composable
private fun EventList(
    state: EventsListState.Events,
    zone: ZoneId,
    associationNames: Map<String, String>,
    onEventClick: (eventId: String) -> Unit,
) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(EventsTags.LIST),
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
  ) {
    if (state.isOffline) {
      item { EventsOfflineBanner(Modifier.padding(bottom = 14.dp)) }
    }
    state.sections.forEachIndexed { index, section ->
      item(key = "day-$index") { DayHeader(section.date, state.today, index) }
      items(section.events, key = { it.id }) { event ->
        EventCard(
            event,
            zone,
            associationName = associationNames[event.createdBy],
            onClick = { onEventClick(event.id) },
            Modifier.padding(bottom = 14.dp),
        )
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
private fun EventCard(
    event: Event,
    zone: ZoneId,
    associationName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
              .clip(RoundedCornerShape(18.dp))
              .clickable(onClick = onClick)
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
      // An association's event (#128, Figma "Events · association event"): the Verified badge
      // 8 dp after the title, on its first line, and its name, once read, at the end of the
      // details.
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = event.title,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp),
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (event.isAssociationEvent) {
          VerifiedPill(
              stringResource(R.string.event_verified_badge),
              Modifier.testTag(EventsTags.verified(event.id)),
          )
        }
      }
      val label = stringResource(category.label)
      val name = associationName?.takeIf { event.isAssociationEvent }
      Text(
          text =
              if (name == null) stringResource(R.string.events_card_details, label, time)
              else stringResource(R.string.events_card_details_by, label, time, name),
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
          color = Ink2,
          modifier = Modifier.testTag(EventsTags.details(event.id)),
      )
    }
  }
}

/** The Events list's offline banner (Figma "Events · offline"). */
@Composable
internal fun EventsOfflineBanner(modifier: Modifier = Modifier) {
  OfflineBanner(stringResource(R.string.events_offline), EventsTags.OFFLINE_BANNER, modifier)
}

/** The Figma empty state: the events icon, [title] and [body], then an optional [action]. */
@Composable
internal fun EmptyState(
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

/** The events couldn't be loaded: the message and Retry. */
@Composable
internal fun EventsErrorState(onRetry: () -> Unit) {
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
