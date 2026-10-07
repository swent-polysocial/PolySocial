// Contributors: OpenAI Codex (map overlays and event preview for #50;
// optional location notices and straight-line badges for #51).
package com.polysocial.ui.map

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.resources.C
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.Warning
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

object MapTags {
  const val CANVAS = "map_canvas"
  const val PREVIEW = "map_preview"
  const val TITLE = "map_preview_title"
  const val TIME = "map_preview_time"
  const val CLOSE = "map_preview_close"
  const val DETAILS = "map_preview_details"
  const val FIND_GROUP = "map_preview_find_group"
  const val CATEGORY = "map_preview_category"
  const val CATEGORY_ICON = "map_preview_category_icon"
  const val EMPTY_ICON = "map_empty_icon"
  const val LOADING = "map_loading"
  const val LOADING_INDICATOR = "map_loading_indicator"
  const val LOADING_LABEL = "map_loading_label"
  const val EMPTY = "map_empty"
  const val ERROR = "map_error"
  const val RETRY = "map_retry"
  const val SETUP = "map_setup"
  const val LOCATION_NOTICE = "map_location_notice"
  const val LOCATION_ACTION = "map_location_action"
  const val DISTANCE = "map_preview_distance"

  fun marker(id: String) = "map_marker_$id"
}

internal val defaultMapRenderer:
    @Composable
    (List<Event>, (String) -> Unit, (MapRenderStatus) -> Unit, Dp) -> Unit =
    { events, select, status, inset ->
      MapboxRenderer(events, select, status, inset)
    }

@Composable
fun MapScreen(
    state: MapUiState,
    onSelectEvent: (String) -> Unit,
    onClosePreview: () -> Unit,
    onViewDetails: (String) -> Unit,
    onRetry: () -> Unit,
    onRenderStatus: (MapRenderStatus) -> Unit,
    modifier: Modifier = Modifier,
    onTurnOnLocation: () -> Unit = {},
    tokenConfigured: Boolean = stringResource(R.string.mapbox_access_token).startsWith("pk."),
    renderer: @Composable (List<Event>, (String) -> Unit, (MapRenderStatus) -> Unit, Dp) -> Unit =
        defaultMapRenderer,
) {
  var previewHeight by remember { mutableIntStateOf(0) }
  var locationNoticeHeight by remember { mutableIntStateOf(0) }
  var showPrivacy by rememberSaveable { mutableStateOf(false) }
  val hasLocationNotice =
      state.locationState == MapLocationState.Denied ||
          state.locationState == MapLocationState.Unavailable
  val noticeTop =
      with(LocalDensity.current) {
        if (hasLocationNotice) locationNoticeHeight.toDp() + 76.dp else 64.dp
      }
  // Card height excludes padding: reserve its 12 dp bottom margin and a 12 dp ornament gap.
  val bottomInset =
      with(LocalDensity.current) {
        if (state.selectedEvent == null) 12.dp else previewHeight.toDp() + 24.dp
      }
  Box(
      modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag(C.Tag.screen_map)
  ) {
    if (tokenConfigured) {
      key(state.renderGeneration) {
        renderer(state.events, onSelectEvent, onRenderStatus, bottomInset)
      }
    } else {
      MapNotice(
          R.string.map_setup_title,
          R.string.map_setup_message,
          MapTags.SETUP,
          Modifier.align(Alignment.Center).padding(24.dp),
      )
    }
    when {
      tokenConfigured && state.renderStatus == MapRenderStatus.ERROR ->
          MapError(
              R.string.map_tiles_error,
              onRetry,
              Modifier.align(Alignment.TopCenter)
                  .padding(start = 12.dp, top = noticeTop, end = 12.dp),
          )
      state.status == MapContentStatus.ERROR ->
          MapError(
              R.string.map_events_error,
              onRetry,
              Modifier.align(Alignment.TopCenter)
                  .padding(start = 12.dp, top = noticeTop, end = 12.dp),
          )
      tokenConfigured &&
          (state.status == MapContentStatus.LOADING ||
              state.renderStatus == MapRenderStatus.LOADING) ->
          Card(
              Modifier.align(Alignment.Center)
                  .padding(24.dp)
                  .fillMaxWidth()
                  .testTag(MapTags.LOADING),
              shape = RoundedCornerShape(20.dp),
          ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              CircularProgressIndicator(
                  Modifier.size(28.dp).testTag(MapTags.LOADING_INDICATOR),
                  color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                  stringResource(
                      if (state.status == MapContentStatus.LOADING) R.string.map_loading
                      else R.string.map_render_loading
                  ),
                  modifier = Modifier.testTag(MapTags.LOADING_LABEL),
                  textAlign = TextAlign.Center,
              )
            }
          }
      tokenConfigured && state.status == MapContentStatus.EMPTY ->
          MapNotice(
              R.string.map_empty_title,
              R.string.map_empty_message,
              MapTags.EMPTY,
              Modifier.align(Alignment.Center).padding(24.dp),
          )
    }
    if (hasLocationNotice) {
      Card(
          Modifier.align(Alignment.TopCenter)
              .padding(start = 12.dp, top = 64.dp, end = 12.dp)
              .onSizeChanged { locationNoticeHeight = it.height }
              .testTag(MapTags.LOCATION_NOTICE),
          shape = RoundedCornerShape(20.dp),
      ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(
              stringResource(
                  if (state.locationState == MapLocationState.Denied) R.string.map_location_denied
                  else R.string.map_location_unavailable
              ),
              modifier = Modifier.weight(1f),
              style = MaterialTheme.typography.bodyMedium,
          )
          TextButton(onTurnOnLocation, Modifier.testTag(MapTags.LOCATION_ACTION)) {
            Text(
                stringResource(
                    if (state.locationState == MapLocationState.Denied)
                        R.string.map_location_turn_on
                    else R.string.map_location_refresh
                )
            )
          }
        }
      }
    }
    state.selectedEvent?.let { event ->
      EventPreview(
          event,
          onClosePreview,
          { onViewDetails(event.id) },
          state.selectedEventIsTonight,
          state.selectedDistanceMeters,          Modifier.align(Alignment.BottomCenter).padding(12.dp).onSizeChanged {
            previewHeight = it.height
          },
      )
    }
  }
}

@Composable
private fun MapNotice(
    @StringRes title: Int,
    @StringRes message: Int,
    tag: String,
    modifier: Modifier,
) {
  Card(
      modifier.testTag(tag),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    val empty = tag == MapTags.EMPTY
    Column(
        Modifier.padding(if (empty) 24.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (empty) 12.dp else 8.dp),
        horizontalAlignment = if (empty) Alignment.CenterHorizontally else Alignment.Start,
    ) {
      if (tag == MapTags.EMPTY) {
        Box(
            Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
          Icon(
              painterResource(R.drawable.ic_tab_events),
              null,
              Modifier.size(28.dp).testTag(MapTags.EMPTY_ICON),
          )
        }
      }
      Text(
          stringResource(title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = if (empty) FontWeight.Bold else null,
          textAlign = if (empty) TextAlign.Center else TextAlign.Start,
      )
      Text(
          stringResource(message),
          style = MaterialTheme.typography.bodyMedium,
          textAlign = if (empty) TextAlign.Center else TextAlign.Start,
      )
    }
  }
}

@Composable
private fun MapError(@StringRes message: Int, onRetry: () -> Unit, modifier: Modifier) {
  Card(modifier.testTag(MapTags.ERROR), shape = RoundedCornerShape(20.dp)) {
    Column(Modifier.padding(16.dp)) {
      Text(stringResource(message))
      TextButton(onRetry, Modifier.testTag(MapTags.RETRY)) {
        Text(stringResource(R.string.map_retry))
      }
    }
  }
}

@Composable
private fun EventPreview(
    event: Event,
    onClose: () -> Unit,
    onDetails: () -> Unit,
    isToday: Boolean,
    distanceMeters: Double?,
    modifier: Modifier,
) {
  Card(
      modifier.fillMaxWidth().testTag(MapTags.PREVIEW),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(52.dp)
                .background(
                    categoryColor(event.category).copy(alpha = 0.1f),
                    RoundedCornerShape(16.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
          Icon(
              painterResource(categoryIcon(event.category)),
              null,
              Modifier.size(28.dp).testTag(MapTags.CATEGORY_ICON),
              tint = categoryColor(event.category),
          )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
          Text(
              stringResource(
                  R.string.map_category_day,
                  stringResource(categoryLabel(event.category)),
                  if (isToday) stringResource(R.string.map_tonight)
                  else previewDateFormatter.format(event.startTime),
              ),
              color = categoryColor(event.category),
              style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.testTag(MapTags.CATEGORY),
          )
          Text(
              event.title,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.testTag(MapTags.TITLE),
          )
        }
        IconButton(
            onClose,
            Modifier.background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(50),
                )
                .testTag(MapTags.CLOSE),
        ) {
          Icon(painterResource(R.drawable.ic_close), stringResource(R.string.map_close))
        }
      }
      val time = previewTimeFormatter.format(event.startTime)
      Text(
          if (event.endTime == null) time
          else
              stringResource(
                  R.string.map_time_range,
                  time,
                  previewTimeFormatter.format(event.endTime),
              ),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.testTag(MapTags.TIME),
      )
      distanceMeters?.let { meters ->
        Text(
            if (meters < 1000) stringResource(R.string.map_distance_meters, meters.roundToInt())
            else stringResource(R.string.map_distance_kilometers, meters / 1000),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag(MapTags.DISTANCE),
        )
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onDetails, Modifier.weight(1f).testTag(MapTags.DETAILS)) {
          Text(stringResource(R.string.map_view_details))
        }
        Button(
            {},
            Modifier.weight(1.4f).testTag(MapTags.FIND_GROUP),
            enabled = false,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
          Text(stringResource(R.string.map_find_group))
        }
      }
    }
  }
}

private val previewTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(MAP_TIME_ZONE)
private val previewDateFormatter = DateTimeFormatter.ofPattern("EEE d MMM").withZone(MAP_TIME_ZONE)

internal fun categoryIcon(category: EventCategory): Int =
    when (category) {
      EventCategory.STUDY -> R.drawable.ic_map_study
      EventCategory.SPORTS -> R.drawable.ic_map_sports
      EventCategory.CULTURE -> R.drawable.ic_map_culture
      EventCategory.PARTY -> R.drawable.ic_map_party
      EventCategory.OTHER -> R.drawable.ic_tab_events
    }

@StringRes
internal fun categoryLabel(category: EventCategory): Int =
    when (category) {
      EventCategory.STUDY -> R.string.map_category_study
      EventCategory.SPORTS -> R.string.map_category_sports
      EventCategory.CULTURE -> R.string.map_category_culture
      EventCategory.PARTY -> R.string.map_category_party
      EventCategory.OTHER -> R.string.map_category_other
    }

internal fun categoryColor(category: EventCategory): Color =
    when (category) {
      EventCategory.STUDY -> Warning
      EventCategory.SPORTS -> Success
      EventCategory.CULTURE -> Info
      EventCategory.PARTY -> Accent
      EventCategory.OTHER -> Ink3
    }
