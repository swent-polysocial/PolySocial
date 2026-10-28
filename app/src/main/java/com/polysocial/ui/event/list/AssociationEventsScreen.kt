// Contributors: Claude (the association's Events tab from the Figma "Association · events tab"
// frame, #132; keeping the split current and the year on other years' dates, #132 review).
// Contributors: Claude (the shared offline banner, #251).
package com.polysocial.ui.event.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.polysocial.R
import com.polysocial.model.event.Event
import com.polysocial.model.event.isLive
import com.polysocial.resources.C
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.event.style
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.InfoSoft
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.SuccessSoft
import com.polysocial.ui.theme.Warning
import com.polysocial.ui.theme.WarningSoft
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

object AssociationEventsTags {
  const val UPCOMING_TAB = "association_events_upcoming_tab"
  const val DRAFTS_TAB = "association_events_drafts_tab"
  const val PAST_TAB = "association_events_past_tab"
  const val LIST = "association_events_list"
  const val EMPTY = "association_events_empty"

  fun card(eventId: String) = "association_events_card_$eventId"

  /** The chip on an event's card: `LIVE`, `PUBLIC` or `PRIVATE`. */
  fun chip(eventId: String, kind: String) = "association_events_chip_${eventId}_$kind"
}

/** The tabs of the association's Events tab. Drafts aren't in the event model yet. */
private enum class OwnEventsTab {
  UPCOMING,
  PAST,
}

/** The association's Events tab, with its own [AssociationEventsViewModel]. */
@Composable
fun AssociationEventsRoute(
    onEventClick: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AssociationEventsViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(viewModel, lifecycleOwner) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.keepSplitCurrent() }
  }
  AssociationEventsScreen(
      state,
      zone = viewModel.zone,
      today = viewModel.today,
      onRetry = viewModel::retry,
      onEventClick = onEventClick,
      modifier = modifier,
  )
}

/**
 * Draws [state] as the Figma "Association · events tab": Upcoming, Drafts and Past, then the events
 * of the selected tab as cards. Drafts aren't in the event model yet, so that tab shows a "not
 * available yet" Snackbar and keeps the current one. The cards leave out Figma's Going, Groups and
 * Checked-in counts until registrations (#121), matching and check-in exist, and the header keeps
 * the app bar until the association's name is stored (#126, #140). Dates and times are shown in
 * [zone]; the selected tab is kept across rotation.
 */
@Composable
fun AssociationEventsScreen(
    state: AssociationEventsState,
    zone: ZoneId,
    today: LocalDate,
    onRetry: () -> Unit,
    onEventClick: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
  if (state == AssociationEventsState.Loading) {
    LoadingState(stringResource(R.string.loading_events), modifier)
    return
  }
  var selected by rememberSaveable { mutableStateOf(OwnEventsTab.UPCOMING) }
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val draftsUnavailable = stringResource(R.string.association_events_drafts_unavailable)
  Box(modifier.fillMaxSize().testTag(C.Tag.screen_events)) {
    Column(Modifier.fillMaxSize()) {
      when (state) {
        AssociationEventsState.Loading -> Unit
        AssociationEventsState.Error -> EventsErrorState(onRetry)
        is AssociationEventsState.Events -> {
          TabSwitcher(
              selected = selected,
              upcomingCount = state.upcoming.size,
              pastCount = state.past.size,
              onSelect = { selected = it },
              onDrafts = {
                snackbarHostState.currentSnackbarData?.dismiss()
                scope.launch { snackbarHostState.showSnackbar(draftsUnavailable) }
              },
          )
          val events = if (selected == OwnEventsTab.UPCOMING) state.upcoming else state.past
          if (state.isOffline) EventsOfflineBanner(Modifier.padding(horizontal = 20.dp))
          if (events.isEmpty()) {
            EmptyState(
                title =
                    stringResource(
                        if (selected == OwnEventsTab.UPCOMING)
                            R.string.association_events_no_upcoming_title
                        else R.string.association_events_no_past_title
                    ),
                body =
                    stringResource(
                        if (selected == OwnEventsTab.UPCOMING)
                            R.string.association_events_no_upcoming_body
                        else R.string.association_events_no_past_body
                    ),
                tag = AssociationEventsTags.EMPTY,
            )
          } else {
            OwnEventList(events, state.now, zone, today, onEventClick)
          }
        }
      }
    }
    SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
  }
}

/** Figma's segmented control: Upcoming and Past with their counts, and Drafts. */
@Composable
private fun TabSwitcher(
    selected: OwnEventsTab,
    upcomingCount: Int,
    pastCount: Int,
    onSelect: (OwnEventsTab) -> Unit,
    onDrafts: () -> Unit,
) {
  Row(
      modifier =
          Modifier.padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 14.dp)
              .fillMaxWidth()
              .background(Disabled, RoundedCornerShape(22.dp))
              .padding(4.dp),
  ) {
    Segment(
        stringResource(R.string.association_events_upcoming, upcomingCount),
        isSelected = selected == OwnEventsTab.UPCOMING,
        onClick = { onSelect(OwnEventsTab.UPCOMING) },
        tag = AssociationEventsTags.UPCOMING_TAB,
    )
    Segment(
        stringResource(R.string.association_events_drafts),
        isSelected = false,
        onClick = onDrafts,
        tag = AssociationEventsTags.DRAFTS_TAB,
    )
    Segment(
        stringResource(R.string.association_events_past, pastCount),
        isSelected = selected == OwnEventsTab.PAST,
        onClick = { onSelect(OwnEventsTab.PAST) },
        tag = AssociationEventsTags.PAST_TAB,
    )
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Segment(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
  Box(
      modifier =
          Modifier.weight(1f)
              .heightIn(min = 36.dp)
              .clip(RoundedCornerShape(18.dp))
              .background(if (isSelected) Bg else Color.Transparent)
              .clickable(role = Role.Tab, onClick = onClick)
              .semantics { selected = isSelected }
              .testTag(tag),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = label,
        style =
            MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
            ),
        color = if (isSelected) Ink else Ink2,
    )
  }
}

@Composable
private fun OwnEventList(
    events: List<Event>,
    now: Instant,
    zone: ZoneId,
    today: LocalDate,
    onEventClick: (eventId: String) -> Unit,
) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(AssociationEventsTags.LIST),
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    items(events, key = { it.id }) { event ->
      OwnEventCard(event, now, zone, today, onClick = { onEventClick(event.id) })
    }
  }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM")
private val dateWithYearFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

/**
 * An event as a Figma card: the category tile, the title, then the day, start time and place, and a
 * chip: "Live" while it runs, otherwise its visibility.
 */
@Composable
private fun OwnEventCard(
    event: Event,
    now: Instant,
    zone: ZoneId,
    today: LocalDate,
    onClick: () -> Unit,
) {
  val category = event.category.style
  val start = event.startTime.atZone(zone)
  val day =
      when (start.toLocalDate()) {
        today -> stringResource(R.string.events_today)
        today.plusDays(1) -> stringResource(R.string.events_tomorrow)
        else -> start.format(if (start.year == today.year) dateFormat else dateWithYearFormat)
      }
  val time = start.format(timeFormat)
  val details =
      if (event.locationName.isBlank()) stringResource(R.string.events_card_details, day, time)
      else stringResource(R.string.association_events_card_details, day, time, event.locationName)
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(18.dp))
              .clickable(onClick = onClick)
              .border(1.dp, Border, RoundedCornerShape(18.dp))
              .padding(17.dp)
              .testTag(AssociationEventsTags.card(event.id)),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        modifier = Modifier.size(44.dp).background(category.tileColor, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
      // The chip and the details describe the event; the category icon is decorative here.
      Icon(painterResource(category.icon), contentDescription = null, tint = category.color)
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          text = event.title,
          style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
          color = Ink,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          text = details,
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
          color = Ink2,
      )
    }
    when {
      event.isLive(now) ->
          Chip(R.string.association_events_live, Success, SuccessSoft, event.id, "LIVE")
      event.isPrivate ->
          Chip(R.string.association_events_private, Warning, WarningSoft, event.id, "PRIVATE")
      else -> Chip(R.string.association_events_public, Info, InfoSoft, event.id, "PUBLIC")
    }
  }
}

@Composable
private fun Chip(label: Int, color: Color, background: Color, eventId: String, kind: String) {
  Text(
      text = stringResource(label),
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
      color = color,
      modifier =
          Modifier.background(background, RoundedCornerShape(12.dp))
              .padding(horizontal = 10.dp, vertical = 4.dp)
              .testTag(AssociationEventsTags.chip(eventId, kind)),
  )
}
