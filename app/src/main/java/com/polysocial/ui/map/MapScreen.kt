// Contributors: OpenAI Codex (map overlays and event preview for #50; reuse shared category
// styles; optional location notices and straight-line badges and review fixes for #51).
// Contributors: Claude (the "+" that opens Create Event among the map overlays, #110; an
// association's badge and name on the event preview, #128; the preview realigned with its Figma
// frames, #222).
package com.polysocial.ui.map

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.resources.C
import com.polysocial.ui.event.VerifiedPill
import com.polysocial.ui.event.create.CreateEventButton
import com.polysocial.ui.event.create.CreateEventButtonSize
import com.polysocial.ui.event.style
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.InfoSoft
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Surface
import com.polysocial.ui.theme.exact
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
  const val LOCATION_REFRESH = "map_location_refresh"
  const val LOCATION_ICON = "map_location_icon"
  const val DISTANCE = "map_preview_distance"
  const val VERIFIED = "map_preview_verified"

  fun marker(id: String) = "map_marker_$id"
}

/** Space between the "+" and the bottom bar, as in the Figma "Map" frames. */
private val createEventBottom = 20.dp

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
    // Opens Create Event from the "+"; null leaves the "+" out.
    onCreateEvent: (() -> Unit)? = null,
    tokenConfigured: Boolean = stringResource(R.string.mapbox_access_token).startsWith("pk."),
    renderer: @Composable (List<Event>, (String) -> Unit, (MapRenderStatus) -> Unit, Dp) -> Unit =
        defaultMapRenderer,
) {
  var previewHeight by remember { mutableIntStateOf(0) }
  var locationNoticeHeight by remember { mutableIntStateOf(0) }
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
  // Figma "Map" frames: no "+" while the event card is open (it would cover "Find a group").
  val showCreateEvent = onCreateEvent != null && state.selectedEvent == null
  // Figma: the location button sits 12 dp above the "+".
  val locationButtonBottom =
      if (showCreateEvent) createEventBottom + CreateEventButtonSize + 12.dp else bottomInset
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
      val denied = state.locationState == MapLocationState.Denied
      val noticeTitle = stringResource(R.string.map_location_off_title)
      val noticeBody = stringResource(R.string.map_location_denied_body)
      val unavailableText = stringResource(R.string.map_location_unavailable)
      Card(
          Modifier.align(Alignment.TopCenter)
              .padding(start = 12.dp, top = 64.dp, end = 12.dp)
              .onSizeChanged { locationNoticeHeight = it.height }
              .testTag(MapTags.LOCATION_NOTICE),
          shape = RoundedCornerShape(20.dp),
      ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
              painterResource(R.drawable.ic_map_location),
              null,
              Modifier.size(32.dp)
                  .background(InfoSoft, RoundedCornerShape(50))
                  .padding(7.dp)
                  .testTag(MapTags.LOCATION_ICON),
              tint = Info,
          )
          Text(
              buildAnnotatedString {
                if (denied) {
                  withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(noticeTitle) }
                  append(" ")
                  append(noticeBody)
                } else append(unavailableText)
              },
              modifier = Modifier.weight(1f),
              style = MaterialTheme.typography.bodyMedium,
          )
          Button(onTurnOnLocation, Modifier.testTag(MapTags.LOCATION_ACTION)) {
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
    IconButton(
        onTurnOnLocation,
        Modifier.align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = locationButtonBottom)
            .size(48.dp)
            .shadow(6.dp, RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
            .testTag(MapTags.LOCATION_REFRESH),
        enabled =
            state.locationState != MapLocationState.Locating &&
                state.locationState != MapLocationState.Waiting,
    ) {
      Icon(
          painterResource(
              if (hasLocationNotice) R.drawable.ic_map_location_off else R.drawable.ic_map_location
          ),
          stringResource(
              if (
                  state.locationState == MapLocationState.Denied ||
                      state.locationState == MapLocationState.OpenSettings
              )
                  R.string.map_location_turn_on
              else R.string.map_location_refresh
          ),
          tint = Ink3,
      )
    }
    if (showCreateEvent) {
      // Figma: 16 dp from the screen edge and 20 dp above the bottom bar, as on the Events tab.
      CreateEventButton(
          onClick = { onCreateEvent?.invoke() },
          modifier =
              Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = createEventBottom),
      )
    }
    state.selectedEvent?.let { event ->
      EventPreview(
          event,
          state.associationNames[event.createdBy],
          onClosePreview,
          { onViewDetails(event.id) },
          state.selectedEventIsToday,
          state.selectedEventIsTonight,
          state.selectedDistanceMeters,
          Modifier.align(Alignment.BottomCenter).padding(12.dp).onSizeChanged {
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

/**
 * The selected event's card, as in the Figma frames "Map · event preview" (132:550) and "Map ·
 * event preview · association" (958:180): the icon tile, then a text column with the category and
 * day, the title and the time line (time, place and, for an association, its name); the
 * straight-line distance in a grey strip; then the two buttons (#222).
 */
@Composable
private fun EventPreview(
    event: Event,
    associationName: String?,
    onClose: () -> Unit,
    onDetails: () -> Unit,
    isToday: Boolean,
    isTonight: Boolean,
    distanceMeters: Double?,
    modifier: Modifier,
) {
  val typography = MaterialTheme.typography
  Box(
      modifier
          .fillMaxWidth()
          .shadow(14.dp, PreviewShape, ambientColor = Ink, spotColor = Ink)
          .background(Bg, PreviewShape)
          .testTag(MapTags.PREVIEW)
  ) {
    Column(Modifier.padding(16.dp)) {
      Row {
        Box(
            Modifier.size(52.dp)
                .background(event.category.style.tileColor, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
          Icon(
              painterResource(categoryIcon(event.category)),
              null,
              Modifier.size(24.dp).testTag(MapTags.CATEGORY_ICON),
              tint = categoryColor(event.category),
          )
        }
        // Figma's text column runs from x = 80 to 12 dp before the close button.
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 44.dp)) {
          // An association's event (#128): the Verified badge 8 dp after the category and day.
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
                stringResource(
                    R.string.map_category_day,
                    stringResource(categoryLabel(event.category)),
                    when {
                      isTonight -> stringResource(R.string.map_tonight)
                      isToday -> stringResource(R.string.events_today)
                      else -> previewDateFormatter.format(event.startTime)
                    },
                ),
                color = categoryColor(event.category),
                style = typography.bodySmall.copy(fontWeight = FontWeight.Bold).figma(15.1f),
                // Measured after the badge, so a larger text wraps instead of crushing it.
                modifier = Modifier.weight(1f, fill = false).testTag(MapTags.CATEGORY),
            )
            if (event.isAssociationEvent) {
              VerifiedPill(
                  stringResource(R.string.event_verified_badge),
                  Modifier.overlappingTheLine().testTag(MapTags.VERIFIED),
              )
            }
          }
          Spacer(Modifier.height(2.dp))
          Text(
              event.title,
              color = Ink,
              style = typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold).figma(21.4f),
              modifier = Modifier.testTag(MapTags.TITLE),
          )
          Spacer(Modifier.height(3.dp))
          Text(
              timeLine(event, associationName),
              color = Ink2,
              style = typography.labelMedium.copy(fontWeight = FontWeight.Normal).figma(16.4f),
              modifier = Modifier.testTag(MapTags.TIME),
          )
        }
      }
      distanceMeters?.let { meters ->
        val roundedMeters = meters.roundToInt()
        Box(
            Modifier.padding(top = 11.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(Surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
          Text(
              if (roundedMeters < 1000) stringResource(R.string.map_distance_meters, roundedMeters)
              else stringResource(R.string.map_distance_kilometers, meters / 1000),
              color = Ink2,
              style = typography.labelMedium.figma(16.4f),
              modifier = Modifier.testTag(MapTags.DISTANCE),
          )
        }
      }
      Row(
          Modifier.padding(top = 12.dp).fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
            onDetails,
            Modifier.weight(123.5f).heightIn(min = 48.dp).testTag(MapTags.DETAILS),
            border = BorderStroke(1.dp, Border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
            contentPadding = PaddingValues(0.dp),
        ) {
          Text(
              stringResource(R.string.map_view_details),
              style = typography.titleSmall.figma(18.9f),
          )
        }
        Button(
            {},
            Modifier.weight(170.5f).heightIn(min = 48.dp).testTag(MapTags.FIND_GROUP),
            enabled = false,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            contentPadding = PaddingValues(0.dp),
        ) {
          Text(stringResource(R.string.map_find_group), style = typography.titleSmall.figma(18.9f))
        }
      }
    }
    // A 36 dp circle 12 dp from the card's top and end; the button keeps a 48 dp touch target.
    IconButton(
        onClose,
        Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).testTag(MapTags.CLOSE),
    ) {
      Box(
          Modifier.size(36.dp).background(Surface, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(R.drawable.ic_close),
            stringResource(R.string.map_close),
            Modifier.size(18.dp),
            tint = Ink,
        )
      }
    }
  }
}

/** "20:00–01:00 · Satellite · by AGEPoly": the place and the association's name when known. */
@Composable
private fun timeLine(event: Event, associationName: String?): String {
  val start = previewTimeFormatter.format(event.startTime)
  var line =
      if (event.endTime == null) start
      else
          stringResource(R.string.map_time_range, start, previewTimeFormatter.format(event.endTime))
  if (event.locationName.isNotBlank()) {
    line = stringResource(R.string.map_time_place, line, event.locationName)
  }
  val name = associationName?.takeIf { event.isAssociationEvent }
  return if (name == null) line else stringResource(R.string.map_time_by, line, name)
}

private val PreviewShape = RoundedCornerShape(24.dp)

/**
 * The preview's text as in Figma: its line height, not trimmed, and glyphs at fractional pixels.
 */
private fun TextStyle.figma(lineHeightSp: Float): TextStyle =
    copy(
            lineHeight = lineHeightSp.sp,
            lineHeightStyle =
                LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
        .exact()

/**
 * Lays this out without any height and draws it centered on the row: like Figma's badge, which is
 * taller than the category line it sits on, it overlaps the line's margins instead of moving the
 * title down (#128).
 */
private fun Modifier.overlappingTheLine(): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints.copy(minHeight = 0))
  layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }
}

private val previewTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(MAP_TIME_ZONE)
private val previewDateFormatter = DateTimeFormatter.ofPattern("EEE d MMM").withZone(MAP_TIME_ZONE)

internal fun categoryIcon(category: EventCategory): Int = category.style.icon

@StringRes internal fun categoryLabel(category: EventCategory): Int = category.style.label

internal fun categoryColor(category: EventCategory): Color = category.style.color
