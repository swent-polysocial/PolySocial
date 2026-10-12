// Contributors: Claude (event detail screen from the Figma "Event detail" Sprint 2 frames, #112).
package com.polysocial.ui.event.detail

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.event.EventCategory
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.event.style
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.InfoSoft
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.PlusJakartaSans
import com.polysocial.ui.theme.Surface
import com.polysocial.ui.theme.Warning
import com.polysocial.ui.theme.WarningSoft
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** Test tags of the event detail screen. */
object EventDetailTags {
  const val SCREEN = "event_detail_screen"
  const val BACK = "event_detail_back"
  const val CATEGORY = "event_detail_category"
  const val TITLE = "event_detail_title"
  const val VERIFIED_BADGE = "event_detail_verified_badge"
  const val DATE = "event_detail_date"
  const val TIME = "event_detail_time"
  const val LOCATION = "event_detail_location"
  const val CAPACITY = "event_detail_capacity"
  const val DESCRIPTION = "event_detail_description"
  const val OFFLINE_BANNER = "event_detail_offline_banner"
  const val FIND_GROUP = "event_detail_find_group"
  const val NOT_FOUND = "event_detail_not_found"
  const val NOT_FOUND_BACK = "event_detail_not_found_back"
  const val OFFLINE_NOT_SAVED = "event_detail_offline_not_saved"
}

/**
 * The event detail screen for the event in the navigation arguments (see [EventDetailViewModel]).
 */
@Composable
fun EventDetailRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventDetailViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  EventDetailScreen(state, onBack, modifier)
}

/**
 * Draws [state] as in the Figma "Event detail" Sprint 2 frames (section 03): the event, or its
 * loading, not-found and offline states. Every state keeps the Back button. "Find a group" isn't
 * built yet (matching comes later), so it shows a "not available yet" Snackbar.
 */
@Composable
fun EventDetailScreen(
    state: EventDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val unavailable = stringResource(R.string.event_detail_find_group_unavailable)
  Box(modifier.fillMaxSize().background(Bg).testTag(EventDetailTags.SCREEN)) {
    when (state) {
      EventDetailUiState.Loading -> {
        // Centred on the whole screen like the app's other loading states, under the header.
        LoadingState(stringResource(R.string.event_detail_loading))
        Header(Surface, onBack)
      }
      is EventDetailUiState.Loaded ->
          EventContent(
              state,
              onBack,
              snackbarHostState,
              onFindGroup = {
                // Replace a message already shown instead of queueing one per tap.
                snackbarHostState.currentSnackbarData?.dismiss()
                scope.launch { snackbarHostState.showSnackbar(unavailable) }
              },
          )
      EventDetailUiState.NotFound ->
          MessageState(
              icon = R.drawable.ic_tab_events,
              iconTint = Ink2,
              title = stringResource(R.string.event_detail_not_found_title),
              body = stringResource(R.string.event_detail_not_found_body),
              onBack = onBack,
              showBackButton = true,
              tag = EventDetailTags.NOT_FOUND,
          )
      EventDetailUiState.OfflineNotSaved ->
          MessageState(
              icon = R.drawable.ic_offline,
              iconTint = AccentText,
              title = stringResource(R.string.event_detail_offline_not_saved_title),
              body = stringResource(R.string.event_detail_offline_not_saved_body),
              onBack = onBack,
              showBackButton = false,
              tag = EventDetailTags.OFFLINE_NOT_SAVED,
          )
    }
  }
}

@Composable
private fun EventContent(
    state: EventDetailUiState.Loaded,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onFindGroup: () -> Unit,
) {
  val details = state.details
  val event = details.event
  val category = event.category.style
  Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // The category tile overlaps the bottom of the header, as in Figma.
        Box {
          Header(category.tileColor, onBack)
          CategoryTile(
              event.category,
              Modifier.windowInsetsPadding(WindowInsets.statusBars)
                  .padding(start = 20.dp, top = 88.dp),
          )
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
          Spacer(Modifier.height(16.dp))
          Pill(
              text = stringResource(category.label),
              color = category.color,
              background = category.tileColor,
              height = 24.dp,
              horizontalPadding = 10.dp,
              modifier = Modifier.testTag(EventDetailTags.CATEGORY),
          )
          Spacer(Modifier.height(14.dp))
          Text(
              text = event.title,
              style = TITLE_STYLE,
              color = Ink,
              modifier = Modifier.semantics { heading() }.testTag(EventDetailTags.TITLE),
          )
          if (event.isAssociationEvent) {
            Spacer(Modifier.height(6.dp))
            Pill(
                text = stringResource(R.string.event_detail_verified),
                color = Info,
                background = InfoSoft,
                height = 22.dp,
                horizontalPadding = 8.dp,
                modifier = Modifier.testTag(EventDetailTags.VERIFIED_BADGE),
            )
          }
          Spacer(Modifier.height(14.dp))
          InfoRow(
              icon = R.drawable.ic_tab_events,
              title = details.date.format(DATE_FORMAT),
              subtitle = timeText(details),
              titleTag = EventDetailTags.DATE,
              subtitleTag = EventDetailTags.TIME,
          )
          Spacer(Modifier.height(12.dp))
          InfoRow(
              icon = R.drawable.ic_pin,
              title =
                  event.locationName.ifBlank {
                    stringResource(R.string.event_detail_unnamed_location)
                  },
              titleTag = EventDetailTags.LOCATION,
          )
          event.capacity?.let {
            Spacer(Modifier.height(12.dp))
            InfoRow(
                icon = R.drawable.ic_people,
                title = pluralStringResource(R.plurals.event_detail_capacity, it, it),
                titleTag = EventDetailTags.CAPACITY,
            )
          }
          if (event.description.isNotBlank()) {
            Spacer(Modifier.height(17.dp))
            Text(
                text = event.description,
                style = MaterialTheme.typography.bodyLarge.figmaExact(),
                color = Ink2,
                modifier = Modifier.testTag(EventDetailTags.DESCRIPTION),
            )
          }
          Spacer(Modifier.height(20.dp))
        }
      }
      SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
    if (state.isOffline) OfflineBanner(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
    ActionBar(onFindGroup)
  }
}

/**
 * The colored top of the screen with the round Back button. Its color runs behind the status bar,
 * and its Figma height starts below it. The Back button is Figma's 44 dp circle inside a 48 dp
 * touch area, Android's minimum.
 */
@Composable
private fun Header(color: Color, onBack: () -> Unit) {
  Box(
      Modifier.fillMaxWidth()
          .background(color)
          .windowInsetsPadding(WindowInsets.statusBars)
          .height(HEADER_HEIGHT)
  ) {
    Box(
        modifier =
            Modifier.padding(10.dp)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack)
                .testTag(EventDetailTags.BACK),
        contentAlignment = Alignment.Center,
    ) {
      Box(Modifier.size(44.dp).background(Bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_back),
            contentDescription = stringResource(R.string.event_detail_navigate_back),
            tint = Ink,
            modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}

/**
 * The large category tile: the category's icon in white on a saturated tile with a white border.
 * Figma's detail frame uses the accent red for parties; the other categories have no detail frame,
 * so they use their category color.
 */
@Composable
private fun CategoryTile(category: EventCategory, modifier: Modifier = Modifier) {
  val fill = if (category == EventCategory.PARTY) Accent else category.style.color
  Box(
      modifier =
          modifier
              .size(72.dp)
              .clip(RoundedCornerShape(18.dp))
              .background(fill)
              .border(4.dp, Bg, RoundedCornerShape(18.dp)),
      contentAlignment = Alignment.Center,
  ) {
    // The category is read from the label below, so the icon is decorative.
    Icon(
        painterResource(category.style.icon),
        contentDescription = null,
        tint = Bg,
        modifier = Modifier.size(28.dp),
    )
  }
}

/** A rounded label of Figma's fixed [height], with its text centred. */
@Composable
private fun Pill(
    text: String,
    color: Color,
    background: Color,
    height: Dp,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
  Box(
      modifier =
          modifier
              // Read as one label, the text it holds.
              .semantics(mergeDescendants = true) {}
              .height(height)
              .background(background, RoundedCornerShape(50))
              .padding(horizontal = horizontalPadding),
      contentAlignment = Alignment.Center,
  ) {
    Text(text = text, style = PILL_STYLE, color = color)
  }
}

/** A row of the details: an icon tile, a bold line and an optional second line. */
@Composable
private fun InfoRow(
    @DrawableRes icon: Int,
    title: String,
    titleTag: String,
    subtitle: String? = null,
    subtitleTag: String? = null,
) {
  Row(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        modifier = Modifier.size(40.dp).background(Surface, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(icon),
          contentDescription = null,
          tint = Ink,
          modifier = Modifier.size(18.dp),
      )
    }
    Column {
      Text(title, style = ROW_TITLE_STYLE, color = Ink, modifier = Modifier.testTag(titleTag))
      if (subtitle != null) {
        Text(
            subtitle,
            style = ROW_SUBTITLE_STYLE,
            color = Ink2,
            modifier = subtitleTag?.let { Modifier.testTag(it) } ?: Modifier,
        )
      }
    }
  }
}

/** "20:00", "20:00 – 23:00", or "20:00 – 01:00 (+1 day)" when it ends on a later day. */
@Composable
private fun timeText(details: EventDetails): String {
  val start = details.startTime.format(TIME_FORMAT)
  val end: LocalTime = details.endTime ?: return start
  val endText =
      if (details.endDayOffset > 0) {
        pluralStringResource(
            R.plurals.event_detail_end_later,
            details.endDayOffset,
            end.format(TIME_FORMAT),
            details.endDayOffset,
        )
      } else {
        end.format(TIME_FORMAT)
      }
  return stringResource(R.string.events_time_range, start, endText)
}

@Composable
private fun OfflineBanner(modifier: Modifier = Modifier) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .background(WarningSoft, RoundedCornerShape(14.dp))
              .padding(horizontal = 14.dp, vertical = 13.dp)
              .testTag(EventDetailTags.OFFLINE_BANNER),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      // Figma starts the icon and the text on the same line, so a two-line text keeps the icon at
      // the top.
      verticalAlignment = Alignment.Top,
  ) {
    Icon(painterResource(R.drawable.ic_offline), contentDescription = null, tint = Warning)
    Text(
        text = stringResource(R.string.event_detail_offline),
        style = MaterialTheme.typography.labelMedium.figmaExact(),
        color = Warning,
    )
  }
}

/**
 * The bottom bar, with a top border: "Find a group" across the whole width, above the navigation
 * bar.
 */
@Composable
private fun ActionBar(onFindGroup: () -> Unit) {
  Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
    HorizontalDivider(color = Border)
    Box(
        modifier =
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Accent)
                .clickable(role = Role.Button, onClick = onFindGroup)
                .testTag(EventDetailTags.FIND_GROUP),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          stringResource(R.string.event_detail_find_group),
          style = MaterialTheme.typography.titleSmall,
          color = Bg,
      )
    }
  }
}

/**
 * A full-screen message centred on the screen, with a neutral header, as in the Figma "Event detail
 * · not found" and "· offline, not saved" frames: an icon in a round tile, a title, a short text,
 * and optionally a Back button.
 */
@Composable
private fun MessageState(
    @DrawableRes icon: Int,
    iconTint: Color,
    title: String,
    body: String,
    onBack: () -> Unit,
    showBackButton: Boolean,
    tag: String,
) {
  Box(Modifier.fillMaxSize().testTag(tag)) {
    // Centred on the whole screen like the app's other full-screen states. The same padding above
    // and below keeps it centred while leaving room for the header on short screens.
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = HEADER_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
          Modifier.size(72.dp).background(Surface, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(30.dp),
        )
      }
      // Figma's texts here use the font's own line height ("auto"), like ErrorState's.
      Text(
          text = title,
          style =
              MaterialTheme.typography.titleMedium
                  .copy(lineHeight = TextUnit.Unspecified)
                  .figmaExact(),
          color = Ink,
          textAlign = TextAlign.Center,
          modifier = Modifier.widthIn(max = 280.dp).semantics { heading() },
      )
      Text(
          text = body,
          style =
              MaterialTheme.typography.bodyLarge
                  .copy(lineHeight = TextUnit.Unspecified)
                  .figmaExact(),
          color = Ink2,
          textAlign = TextAlign.Center,
          modifier = Modifier.widthIn(max = 280.dp),
      )
      if (showBackButton) {
        Box(
            modifier =
                Modifier.size(width = 160.dp, height = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Ink)
                    .clickable(role = Role.Button, onClick = onBack)
                    .testTag(EventDetailTags.NOT_FOUND_BACK),
            contentAlignment = Alignment.Center,
        ) {
          Text(
              stringResource(R.string.event_detail_navigate_back),
              style = MaterialTheme.typography.titleSmall,
              color = Bg,
          )
        }
      }
    }
    Header(Surface, onBack)
  }
}

/** Height of the colored header with the Back button. */
private val HEADER_HEIGHT = 132.dp

/**
 * Draws this style like Figma (see "Matching Figma in Compose" in CONTEXT.md): glyphs aren't
 * rounded to whole pixels, and the first and last lines of a multi-line text aren't trimmed.
 */
private fun TextStyle.figmaExact(): TextStyle =
    copy(
        textMotion = TextMotion.Animated,
        lineHeightStyle =
            LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

// Figma's text styles on this screen; the shared type scale has no 24 sp title or 12 sp bold label.
private val TITLE_STYLE =
    TextStyle(
            fontFamily = PlusJakartaSans,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
        )
        .figmaExact()
private val PILL_STYLE =
    TextStyle(
            fontFamily = PlusJakartaSans,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            lineHeight = 15.1.sp,
        )
        .figmaExact()
private val ROW_TITLE_STYLE =
    TextStyle(
            fontFamily = PlusJakartaSans,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 18.9.sp,
        )
        .figmaExact()
private val ROW_SUBTITLE_STYLE =
    TextStyle(
            fontFamily = PlusJakartaSans,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 16.4.sp,
        )
        .figmaExact()
