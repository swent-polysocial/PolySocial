// Contributors: Claude Opus 5.5 (medium) (My events from the Figma section 12 frames, #204).
package com.polysocial.ui.event.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.model.event.Event
import com.polysocial.model.event.isLive
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.common.OfflineBanner
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.event.style
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.SuccessSoft
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MyEventsTags {
  const val SCREEN = "my_events_screen"
  const val LIST = "my_events_list"
  const val EMPTY = "my_events_empty"
  const val BROWSE = "my_events_browse"
  const val OFFLINE_BANNER = "my_events_offline_banner"
  const val UPCOMING_HEADER = "my_events_upcoming_header"
  const val PAST_HEADER = "my_events_past_header"
  const val MISSING = "my_events_missing"

  fun card(eventId: String) = "my_events_card_$eventId"

  fun details(eventId: String) = "my_events_card_details_$eventId"

  fun live(eventId: String) = "my_events_card_live_$eventId"

  fun unavailable(eventId: String) = "my_events_unavailable_$eventId"

  fun remove(eventId: String) = "my_events_remove_$eventId"
}

/**
 * Draws My events (Figma section 12): the chosen events under Upcoming and Past, the ones no longer
 * available, and the loading, empty, offline and error states. Dates and times are shown in [zone];
 * [today] names today's and tomorrow's dates. Tapping a card calls [onEventClick], Remove on an
 * unavailable event [onRemove], and "Browse upcoming events" [onBrowseUpcoming]. A
 * [MyEventsUiState.message] is shown once in a Snackbar, then [onMessageShown] is called.
 */
@Composable
fun MyEventsScreen(
    state: MyEventsUiState,
    zone: ZoneId,
    today: LocalDate,
    onRetry: () -> Unit,
    onEventClick: (eventId: String) -> Unit,
    onRemove: (eventId: String) -> Unit,
    onBrowseUpcoming: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val message =
      when (state.message) {
        MyEventsMessage.REMOVE_OFFLINE -> stringResource(R.string.my_events_remove_offline)
        MyEventsMessage.REMOVE_FAILED -> stringResource(R.string.my_events_remove_failed)
        null -> null
      }
  // Keyed on the text and the callback, so a new owner of the same message still dismisses it.
  LaunchedEffect(message, onMessageShown) {
    if (message != null) {
      snackbarHostState.showSnackbar(message)
      onMessageShown()
    }
  }
  Box(modifier.fillMaxSize().testTag(MyEventsTags.SCREEN)) {
    when (val list = state.list) {
      MyEventsList.Loading -> LoadingState(stringResource(R.string.my_events_loading))
      MyEventsList.Empty -> NothingPlanned(onBrowseUpcoming)
      MyEventsList.OfflineNothingSaved ->
          ErrorState(
              title = stringResource(R.string.my_events_offline_title),
              message = stringResource(R.string.my_events_offline_message),
              onRetry = onRetry,
          )
      MyEventsList.Error ->
          ErrorState(
              title = stringResource(R.string.my_events_error_title),
              message = stringResource(R.string.my_events_error_message),
              onRetry = onRetry,
          )
      is MyEventsList.Events ->
          ChosenEventList(list, state.removing, zone, today, onEventClick, onRemove)
    }
    SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
  }
}

@Composable
private fun NothingPlanned(onBrowseUpcoming: () -> Unit) {
  EmptyState(
      title = stringResource(R.string.my_events_empty_title),
      body = stringResource(R.string.my_events_empty_body),
      tag = MyEventsTags.EMPTY,
  ) {
    Spacer(Modifier.height(20.dp))
    PrimaryButton(
        text = stringResource(R.string.my_events_browse),
        enabled = true,
        tag = MyEventsTags.BROWSE,
        onClick = onBrowseUpcoming,
    )
  }
}

@Composable
private fun ChosenEventList(
    list: MyEventsList.Events,
    removing: Set<String>,
    zone: ZoneId,
    today: LocalDate,
    onEventClick: (eventId: String) -> Unit,
    onRemove: (eventId: String) -> Unit,
) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(MyEventsTags.LIST),
      // The bottom padding keeps the last row clear of the "+" button.
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (list.isOffline) {
      item(key = "offline") {
        OfflineBanner(
            stringResource(R.string.my_events_offline_banner),
            MyEventsTags.OFFLINE_BANNER,
        )
      }
    }
    if (list.upcoming.isNotEmpty()) {
      item(key = "upcoming") {
        SectionHeader(R.string.my_events_upcoming, MyEventsTags.UPCOMING_HEADER)
      }
      items(list.upcoming, key = { it.id }) { event ->
        ChosenEventCard(event, list.now, zone, today, onClick = { onEventClick(event.id) })
      }
    }
    if (list.past.isNotEmpty()) {
      item(key = "past") { SectionHeader(R.string.my_events_past, MyEventsTags.PAST_HEADER) }
      items(list.past, key = { it.id }) { event ->
        ChosenEventCard(event, list.now, zone, today, onClick = { onEventClick(event.id) })
      }
    }
    if (list.missingCount > 0) {
      item(key = "missing") {
        DashedRow(
            title =
                pluralStringResource(
                    if (list.isOffline) R.plurals.my_events_not_saved
                    else R.plurals.my_events_not_loaded,
                    list.missingCount,
                    list.missingCount,
                ),
            body =
                stringResource(
                    if (list.isOffline) R.string.my_events_not_saved_body
                    else R.string.my_events_not_loaded_body
                ),
            tag = MyEventsTags.MISSING,
        )
      }
    }
    items(list.unavailableIds, key = { "unavailable-$it" }) { eventId ->
      DashedRow(
          title = stringResource(R.string.my_events_unavailable_title),
          body = stringResource(R.string.my_events_unavailable_body),
          tag = MyEventsTags.unavailable(eventId),
      ) {
        RemoveLink(
            enabled = eventId !in removing,
            onClick = { onRemove(eventId) },
            tag = MyEventsTags.remove(eventId),
        )
      }
    }
  }
}

@Composable
private fun SectionHeader(text: Int, tag: String) {
  Text(
      text = stringResource(text),
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
      color = Ink2,
      modifier = Modifier.padding(top = 3.5.dp).testTag(tag),
  )
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM")
private val dateWithYearFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

/**
 * A chosen event as a Figma card: the category tile, the title, then the day, the time (with its
 * end when known) and the place, and "Live" while it runs.
 */
@Composable
private fun ChosenEventCard(
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
  val time =
      event.endTime?.let {
        stringResource(
            R.string.events_time_range,
            start.format(timeFormat),
            it.atZone(zone).format(timeFormat),
        )
      } ?: start.format(timeFormat)
  val details =
      if (event.locationName.isBlank()) stringResource(R.string.events_card_details, day, time)
      else stringResource(R.string.association_events_card_details, day, time, event.locationName)
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(18.dp))
              .clickable(onClick = onClick)
              .border(1.dp, Border, RoundedCornerShape(18.dp))
              .padding(14.dp)
              .testTag(MyEventsTags.card(event.id)),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        modifier = Modifier.size(52.dp).background(category.tileColor, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
      // The details line has no category, so the icon names it (Figma: never color alone).
      Icon(
          painterResource(category.icon),
          contentDescription = stringResource(category.label),
          tint = category.color,
      )
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
          text = event.title,
          style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp),
          color = Ink,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          text = details,
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
          color = Ink2,
          modifier = Modifier.testTag(MyEventsTags.details(event.id)),
      )
    }
    if (event.isLive(now)) {
      Text(
          text = stringResource(R.string.association_events_live),
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
          color = Success,
          modifier =
              Modifier.background(SuccessSoft, RoundedCornerShape(12.dp))
                  .padding(horizontal = 10.dp, vertical = 4.dp)
                  .testTag(MyEventsTags.live(event.id)),
      )
    }
  }
}

/**
 * Figma's dashed row for a chosen event that can't be shown: [title] and [body], never the event's
 * own details, and an optional [action] on the right.
 */
@Composable
private fun DashedRow(
    title: String,
    body: String,
    tag: String,
    action: @Composable () -> Unit = {},
) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .drawBehind {
                drawRoundRect(
                    color = Border,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style =
                        Stroke(
                            width = 1.dp.toPx(),
                            pathEffect =
                                PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                        ),
                )
              }
              .padding(horizontal = 16.dp, vertical = 12.dp)
              .testTag(tag),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          text = title,
          style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
          color = Ink,
      )
      Text(
          text = body,
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
          color = Ink2,
      )
    }
    action()
  }
}

/** Figma's underlined "Remove", with a 48 dp touch target. */
@Composable
private fun RemoveLink(enabled: Boolean, onClick: () -> Unit, tag: String) {
  Box(
      modifier =
          Modifier.heightIn(min = 48.dp)
              .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
              .padding(start = 12.dp)
              .testTag(tag),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = stringResource(R.string.my_events_remove),
        style =
            MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
            ),
        color = if (enabled) Ink else Ink2,
    )
  }
}
