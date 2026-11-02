// Contributors: Claude (the association's manage event from the Figma "Association · manage event"
// frame, #131).
package com.polysocial.ui.event.detail

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.InfoSoft
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.PlusJakartaSans
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.SuccessSoft
import com.polysocial.ui.theme.Surface
import com.polysocial.ui.theme.Warning
import com.polysocial.ui.theme.WarningSoft
import java.time.format.DateTimeFormatter

/** Test tags of the association's manage event (#131). */
object ManageEventTags {
  const val SCREEN = "manage_event_screen"
  const val BACK = "manage_event_back"
  const val EDIT = "manage_event_edit"
  const val LIVE = "manage_event_live"
  const val VISIBILITY = "manage_event_visibility"
  const val TITLE = "manage_event_title"
  const val WHEN_WHERE = "manage_event_when_where"
  const val GOING = "manage_event_going"
  const val GOING_COUNT = "manage_event_going_count"
  const val LOOKING_FOR_GROUP = "manage_event_looking_for_group"
  const val GROUPS_FORMED = "manage_event_groups_formed"
  const val CHECKED_IN = "manage_event_checked_in"
  const val SHOW_FULL_SCREEN = "manage_event_show_full_screen"
  const val DOWNLOAD_PDF = "manage_event_download_pdf"
  const val ALBUM = "manage_event_album"
  const val OFFLINE_BANNER = "manage_event_offline_banner"
  const val CANCEL_EVENT = "manage_event_cancel_event"
}

/**
 * Figma's "Association · manage event" (#131), what an association sees on an event it organizes
 * instead of the students' detail: Edit, the counts, the Entrance QR, the album and Cancel event.
 *
 * Only "Going" is built: it shows [EventDetailUiState.Loaded.going], and tapping it when it
 * couldn't be read calls [onRetryGoing]. The other counts, the QR's buttons and the album aren't
 * built yet, so they show "—" or a "not available yet" Snackbar, which [onUnavailable] shows.
 */
@Composable
internal fun ManageEventContent(
    state: EventDetailUiState.Loaded,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    organizer: EventOrganizerActions,
    onRetryGoing: () -> Unit,
    onUnavailable: () -> Unit,
) {
  Column(Modifier.fillMaxSize().background(Surface).testTag(ManageEventTags.SCREEN)) {
    ManageHeader(onBack, organizer.onEdit)
    Box(Modifier.weight(1f)) {
      Column(
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 16.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        SummaryCard(state, onRetryGoing, onUnavailable)
        EntranceQrCard()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          QrButton(
              R.string.manage_event_show_full_screen,
              filled = true,
              onClick = onUnavailable,
              tag = ManageEventTags.SHOW_FULL_SCREEN,
          )
          QrButton(
              R.string.manage_event_download_pdf,
              filled = false,
              onClick = onUnavailable,
              tag = ManageEventTags.DOWNLOAD_PDF,
          )
        }
        AlbumRow(onUnavailable)
      }
      SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
    Column(
        Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (state.isOffline) DetailOfflineBanner(tag = ManageEventTags.OFFLINE_BANNER)
      CancelEventButton(organizer.onCancelEvent, tag = ManageEventTags.CANCEL_EVENT)
    }
  }
}

/** The white top bar: Back, "Manage event" and the Edit pill, below the status bar. */
@Composable
private fun ManageHeader(onBack: () -> Unit, onEdit: () -> Unit) {
  Box(
      Modifier.fillMaxWidth()
          .background(Bg)
          .windowInsetsPadding(WindowInsets.statusBars)
          .height(60.dp)
  ) {
    // Figma's 22 dp arrow at x = 19 dp, in a 48 dp touch area.
    Box(
        modifier =
            Modifier.align(Alignment.CenterStart)
                .padding(start = 6.dp)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack)
                .testTag(ManageEventTags.BACK),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(R.drawable.ic_back),
          contentDescription = stringResource(R.string.event_detail_navigate_back),
          tint = Ink,
          modifier = Modifier.size(22.dp),
      )
    }
    Text(
        text = stringResource(R.string.manage_event_title),
        style = HEADER_STYLE,
        color = Ink,
        modifier =
            Modifier.align(Alignment.CenterStart).padding(start = 56.dp).semantics { heading() },
    )
    // Figma's 36 dp pill, 16 dp from the edge, in a 48 dp touch area.
    Box(
        modifier =
            Modifier.align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClick = onEdit)
                .padding(horizontal = 4.dp)
                .testTag(ManageEventTags.EDIT),
        contentAlignment = Alignment.Center,
    ) {
      Box(
          Modifier.height(36.dp)
              .background(Bg, RoundedCornerShape(18.dp))
              .border(1.dp, Border, RoundedCornerShape(18.dp))
              .padding(horizontal = 14.dp),
          contentAlignment = Alignment.Center,
      ) {
        Text(stringResource(R.string.event_detail_edit), style = BUTTON_SMALL_STYLE, color = Ink)
      }
    }
  }
}

/** The event's chips, title, day and place, and its four counts. */
@Composable
private fun SummaryCard(
    state: EventDetailUiState.Loaded,
    onRetryGoing: () -> Unit,
    onUnavailable: () -> Unit,
) {
  val details = state.details
  val event = details.event
  val day = details.date.format(DAY_FORMAT)
  val time = timeText(details)
  Column(
      Modifier.fillMaxWidth().background(Bg, RoundedCornerShape(18.dp)).padding(16.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      if (state.isLive) {
        Chip(R.string.manage_event_live, Success, SuccessSoft, ManageEventTags.LIVE)
      }
      if (event.isPrivate) {
        Chip(R.string.association_events_private, Warning, WarningSoft, ManageEventTags.VISIBILITY)
      } else {
        Chip(R.string.association_events_public, Info, InfoSoft, ManageEventTags.VISIBILITY)
      }
    }
    Spacer(Modifier.height(9.dp))
    Text(
        text = event.title,
        style = TITLE_STYLE,
        color = Ink,
        modifier = Modifier.semantics { heading() }.testTag(ManageEventTags.TITLE),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text =
            if (event.locationName.isBlank()) stringResource(R.string.manage_event_when, day, time)
            else stringResource(R.string.manage_event_when_where, day, time, event.locationName),
        style = BODY_STYLE,
        color = Ink2,
        modifier = Modifier.testTag(ManageEventTags.WHEN_WHERE),
    )
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      GoingTile(state.going ?: GoingCount.Loading, onRetryGoing)
      CountTile(
          R.string.manage_event_looking_for_group,
          onUnavailable,
          ManageEventTags.LOOKING_FOR_GROUP,
      )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      CountTile(R.string.manage_event_groups_formed, onUnavailable, ManageEventTags.GROUPS_FORMED)
      CountTile(R.string.manage_event_checked_in, onUnavailable, ManageEventTags.CHECKED_IN)
    }
    Spacer(Modifier.height(9.dp))
    Text(
        stringResource(R.string.manage_event_counts_only),
        style = CAPTION_STYLE,
        color = Ink3,
    )
  }
}

/** "Going": the count, "—" while it loads, or "—" with a way to count again when it failed. */
@Composable
private fun RowScope.GoingTile(going: GoingCount, onRetry: () -> Unit) {
  val unavailable = stringResource(R.string.manage_event_count_unavailable)
  val value =
      when (going) {
        is GoingCount.Count -> going.count.toString()
        GoingCount.Loading,
        GoingCount.Unavailable -> stringResource(R.string.manage_event_no_count)
      }
  Tile(
      value = value,
      label = stringResource(R.string.manage_event_going),
      onClick = onRetry.takeIf { going == GoingCount.Unavailable },
      tag = ManageEventTags.GOING,
      valueTag = ManageEventTags.GOING_COUNT,
      valueDescription = unavailable.takeIf { going == GoingCount.Unavailable },
  )
}

/** A count that isn't built yet: "—", and the "not available yet" Snackbar when tapped. */
@Composable
private fun RowScope.CountTile(@StringRes label: Int, onClick: () -> Unit, tag: String) {
  Tile(
      value = stringResource(R.string.manage_event_no_count),
      label = stringResource(label),
      onClick = onClick,
      tag = tag,
  )
}

@Composable
private fun RowScope.Tile(
    value: String,
    label: String,
    onClick: (() -> Unit)?,
    tag: String,
    valueTag: String? = null,
    valueDescription: String? = null,
) {
  Column(
      Modifier.weight(1f)
          .height(66.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(Surface)
          .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
          .padding(horizontal = 12.dp, vertical = 11.dp)
          .testTag(tag),
  ) {
    Text(
        value,
        style = COUNT_STYLE,
        color = Ink,
        modifier =
            Modifier.let { if (valueTag != null) it.testTag(valueTag) else it }
                .let {
                  if (valueDescription != null)
                      it.semantics { contentDescription = valueDescription }
                  else it
                },
    )
    Spacer(Modifier.height(2.dp))
    Text(label, style = CAPTION_STYLE, color = Ink2, maxLines = 1)
  }
}

/** The Entrance QR card: the QR tile, its title and what it is for. */
@Composable
private fun EntranceQrCard() {
  Row(
      Modifier.fillMaxWidth()
          .background(Bg, RoundedCornerShape(18.dp))
          .padding(horizontal = 16.dp, vertical = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        Modifier.size(72.dp).background(Ink, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
      // Decorative: the title next to it says what it is.
      Icon(
          painterResource(R.drawable.ic_qr),
          contentDescription = null,
          tint = Bg,
          modifier = Modifier.size(40.dp),
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
      Text(
          stringResource(R.string.manage_event_entrance_qr),
          style = ROW_TITLE_STYLE,
          color = Ink,
      )
      Text(
          stringResource(R.string.manage_event_entrance_qr_body),
          style = QR_BODY_STYLE,
          color = Ink2,
      )
    }
  }
}

/** One of the two QR buttons: filled Ink ("Show full screen") or outlined ("Download PDF"). */
@Composable
private fun RowScope.QrButton(
    @StringRes label: Int,
    filled: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
  val shape = RoundedCornerShape(22.dp)
  Box(
      Modifier.weight(1f)
          .height(48.dp)
          .clip(shape)
          .background(if (filled) Ink else Bg)
          .let { if (filled) it else it.border(1.dp, Border, shape) }
          .clickable(role = Role.Button, onClick = onClick)
          .testTag(tag),
      contentAlignment = Alignment.Center,
  ) {
    Text(stringResource(label), style = BUTTON_STYLE, color = if (filled) Bg else Ink)
  }
}

/** The album row, with its icon and a chevron. */
@Composable
private fun AlbumRow(onClick: () -> Unit) {
  Row(
      Modifier.fillMaxWidth()
          .height(62.dp)
          .clip(RoundedCornerShape(18.dp))
          .background(Bg)
          .clickable(role = Role.Button, onClick = onClick)
          .padding(horizontal = 16.dp)
          .testTag(ManageEventTags.ALBUM),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    RowIcon(R.drawable.ic_album, 22)
    Spacer(Modifier.width(12.dp))
    Text(
        stringResource(R.string.manage_event_album),
        style = ROW_TITLE_STYLE,
        color = Ink,
        modifier = Modifier.weight(1f),
    )
    RowIcon(R.drawable.ic_chevron_right, 18)
  }
}

/** A decorative icon in its own colors, [size] dp wide. */
@Composable
private fun RowIcon(@DrawableRes icon: Int, size: Int) {
  Icon(
      painterResource(icon),
      contentDescription = null,
      tint = Color.Unspecified,
      modifier = Modifier.size(size.dp),
  )
}

@Composable
private fun Chip(@StringRes label: Int, color: Color, background: Color, tag: String) {
  Box(
      // Read as one label, the text it holds.
      Modifier.semantics(mergeDescendants = true) {}
          .height(24.dp)
          .background(background, RoundedCornerShape(12.dp))
          .padding(horizontal = 10.dp)
          .testTag(tag),
      contentAlignment = Alignment.Center,
  ) {
    Text(stringResource(label), style = CHIP_STYLE, color = color)
  }
}

/** Figma's "Fri 3 Oct". */
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")

private fun jakarta(weight: FontWeight, size: Float, lineHeight: Float) =
    TextStyle(
            fontFamily = PlusJakartaSans,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
        )
        .figmaExact()

// Figma's text styles on this frame.
private val HEADER_STYLE = jakarta(FontWeight.Bold, 17f, 21.4f)
private val TITLE_STYLE = jakarta(FontWeight.ExtraBold, 20f, 26f)
private val COUNT_STYLE = jakarta(FontWeight.ExtraBold, 20f, 25.2f)
private val ROW_TITLE_STYLE = jakarta(FontWeight.Bold, 15f, 18.9f)
private val BUTTON_STYLE = jakarta(FontWeight.Bold, 14f, 17.6f)
private val BUTTON_SMALL_STYLE = jakarta(FontWeight.Bold, 13f, 16.4f)
private val CHIP_STYLE = jakarta(FontWeight.Bold, 12f, 15.1f)
private val BODY_STYLE = jakarta(FontWeight.Normal, 13f, 16.4f)
private val QR_BODY_STYLE = jakarta(FontWeight.Normal, 13f, 18f)
private val CAPTION_STYLE = jakarta(FontWeight.Normal, 12f, 15.1f)
