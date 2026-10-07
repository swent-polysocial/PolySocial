// Contributors: OpenAI Codex (map overlays and event preview for #50).
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.resources.C
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.Warning
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object MapTags {
  const val CANVAS = "map_canvas"
  const val PREVIEW = "map_preview"
  const val TITLE = "map_preview_title"
  const val TIME = "map_preview_time"
  const val CLOSE = "map_preview_close"
  const val DETAILS = "map_preview_details"
  const val LOADING = "map_loading"
  const val EMPTY = "map_empty"
  const val ERROR = "map_error"
  const val RETRY = "map_retry"
  const val SETUP = "map_setup"
  const val SOURCE_UNAVAILABLE = "map_source_unavailable"

  const val PRIVACY = "map_privacy"
  const val PRIVACY_DIALOG = "map_privacy_dialog"
  const val PRIVACY_CLOSE = "map_privacy_close"

  fun marker(id: String) = "map_marker_$id"
}

/** Hilt boundary; tests exercise [MapScreen] with a fake event stream and renderer. */
@Composable
fun MapRoute(onViewDetails: (String) -> Unit, viewModel: MapViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  MapScreen(
      state = state,
      onSelectEvent = viewModel::selectEvent,
      onClosePreview = viewModel::closePreview,
      onViewDetails = onViewDetails,
      onRetry = viewModel::retry,
      onRenderStatus = viewModel::onRenderStatus,
  )
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
    tokenConfigured: Boolean = stringResource(R.string.mapbox_access_token).startsWith("pk."),
    renderer: @Composable (List<Event>, (String) -> Unit, (MapRenderStatus) -> Unit, Dp) -> Unit =
        { events, select, status, inset ->
          MapboxRenderer(events, select, status, inset)
        },
) {
  var previewHeight by remember { mutableStateOf(0) }
  var showPrivacy by rememberSaveable { mutableStateOf(false) }
  val bottomInset =
      with(LocalDensity.current) {
        if (state.selectedEvent == null) 12.dp else previewHeight.toDp() + 12.dp
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
              Modifier.align(Alignment.TopCenter).padding(start = 12.dp, top = 64.dp, end = 12.dp),
          )
      state.status == MapContentStatus.ERROR ->
          MapError(
              R.string.map_events_error,
              onRetry,
              Modifier.align(Alignment.TopCenter).padding(start = 12.dp, top = 64.dp, end = 12.dp),
          )
      state.status == MapContentStatus.UNAVAILABLE ->
          MapNotice(
              R.string.map_source_title,
              R.string.map_source_message,
              MapTags.SOURCE_UNAVAILABLE,
              Modifier.align(Alignment.TopCenter).padding(start = 12.dp, top = 64.dp, end = 12.dp),
          )
      tokenConfigured &&
          (state.status == MapContentStatus.LOADING ||
              state.renderStatus == MapRenderStatus.LOADING) ->
          Card(
              Modifier.align(Alignment.Center).testTag(MapTags.LOADING),
              shape = RoundedCornerShape(20.dp),
          ) {
            Row(
                Modifier.padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              CircularProgressIndicator()
              Text(stringResource(R.string.map_loading))
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
    TextButton(
        onClick = { showPrivacy = true },
        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).testTag(MapTags.PRIVACY),
    ) {
      Text(stringResource(R.string.map_privacy_title))
    }
    state.selectedEvent?.let { event ->
      EventPreview(
          event,
          onClosePreview,
          { onViewDetails(event.id) },
          Modifier.align(Alignment.BottomCenter)
              .onSizeChanged { previewHeight = it.height }
              .padding(12.dp),
      )
    }
  }
  if (showPrivacy) {
    AlertDialog(
        onDismissRequest = { showPrivacy = false },
        modifier = Modifier.testTag(MapTags.PRIVACY_DIALOG),
        title = { Text(stringResource(R.string.map_privacy_title)) },
        text = { Text(stringResource(R.string.map_privacy_message)) },
        confirmButton = {
          TextButton(
              onClick = { showPrivacy = false },
              modifier = Modifier.testTag(MapTags.PRIVACY_CLOSE),
          ) {
            Text(stringResource(R.string.map_close))
          }
        },
    )
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
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
      Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
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
    modifier: Modifier,
) {
  Card(
      modifier.fillMaxWidth().testTag(MapTags.PREVIEW),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(categoryLabel(event.category)),
            color = categoryColor(event.category),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClose, Modifier.testTag(MapTags.CLOSE)) {
          Text(stringResource(R.string.map_close))
        }
      }
      Text(
          event.title,
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.testTag(MapTags.TITLE),
      )
      val formatter =
          DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
              .withZone(ZoneId.of("Europe/Zurich"))
      val time = formatter.format(event.startTime)
      Text(
          if (event.endTime == null) time
          else
              stringResource(
                  R.string.map_time_range,
                  time,
                  formatter.format(event.endTime),
              ),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.testTag(MapTags.TIME),
      )
      Button(onDetails, Modifier.fillMaxWidth().testTag(MapTags.DETAILS)) {
        Text(stringResource(R.string.map_view_details))
      }
    }
  }
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
