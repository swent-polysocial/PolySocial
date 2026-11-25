// Contributors: OpenAI Codex (GPT-6.2 Sol, high; implemented Figma Section 20 for #469 under Lucas
// Pinto Ricardo’s supervision and approval).
package com.polysocial.ui.event.checkin

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.event.checkin.CheckInState.Stage
import com.polysocial.ui.group.GroupHeader
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.SuccessSoft
import com.polysocial.ui.theme.exact
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

internal object CheckInTags {
  const val ENTRY = "venue_check_in_entry"
  const val BACK = "venue_check_in_back"
  const val LOCATION = "venue_check_in_location"
  const val QR = "venue_check_in_qr"
  const val RETRY = "venue_check_in_retry"
  const val SETTINGS = "venue_check_in_settings"
  const val GROUP = "venue_check_in_group"
}

@Composable
internal fun CheckInPrompt(entry: CheckInEntry, onOpen: () -> Unit) {
  SurfaceRow(
      Modifier.padding(top = 20.dp)
          .clip(RoundedCornerShape(14.dp))
          .clickable(role = Role.Button, onClick = onOpen)
          .testTag(CheckInTags.ENTRY)
  ) {
    Icon(painterResource(R.drawable.ic_check_in_pin), null, Modifier.size(20.dp), tint = Ink)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(entry.label(), style = MaterialTheme.typography.titleSmall.exact(), color = Ink)
      if (entry.availability == CheckInAvailability.OPEN) {
        Text(
            stringResource(R.string.check_in_entry_body),
            style = MaterialTheme.typography.bodyMedium.exact(),
            color = CheckInSecondaryInk,
        )
      }
    }
  }
}

@Composable
internal fun CheckInScreen(
    entry: CheckInEntry,
    state: CheckInState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onSettings: () -> Boolean,
    onOpenGroup: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
  val outside = entry.availability != CheckInAvailability.OPEN
  val showingWindow =
      outside && state != Stage.LOADING && state != Stage.SAVING && state !is CheckInState.Confirmed
  val prompt = state == Stage.READY && !showingWindow
  val progress =
      when (state) {
        Stage.LOADING -> R.string.check_in_loading
        Stage.AWAITING_PERMISSION,
        Stage.CHECKING_LOCATION -> R.string.check_in_checking
        Stage.SAVING -> R.string.check_in_saving
        else -> null
      }.takeUnless { showingWindow }
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val qrUnavailable = stringResource(R.string.check_in_qr_unavailable)
  val settingsUnavailable = stringResource(R.string.check_in_settings_unavailable)
  fun showMessage(message: String) {
    snackbar.currentSnackbarData?.dismiss()
    scope.launch { snackbar.showSnackbar(message) }
  }

  Box(modifier.fillMaxSize().background(Bg)) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
      GroupHeader(CheckInTags.BACK, onBack) {
        Text(
            if (prompt || state is CheckInState.Confirmed) entry.event.title
            else stringResource(R.string.check_in_title),
            style = MaterialTheme.typography.titleMedium.exact(),
            color = Ink,
            modifier = Modifier.padding(start = 2.dp).semantics { heading() },
        )
      }
      if (progress != null) {
        LoadingState(stringResource(progress), Modifier.weight(1f))
      } else {
        CheckInContent(
            entry,
            state,
            prompt,
            showingWindow,
            onOpenGroup != null,
            Modifier.weight(1f),
        )
        CheckInButtons(
            state,
            showingWindow,
            onBack,
            onCheck,
            onQr = { showMessage(qrUnavailable) },
            onSettings = { if (!onSettings()) showMessage(settingsUnavailable) },
            onOpenGroup = onOpenGroup,
        )
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
  }
}

@Composable
private fun CheckInContent(
    entry: CheckInEntry,
    state: CheckInState,
    prompt: Boolean,
    showingWindow: Boolean,
    hasGroup: Boolean,
    modifier: Modifier,
) {
  val event = entry.event
  val venue = event.locationName.ifBlank { stringResource(R.string.event_detail_unnamed_location) }
  val eventLabel = stringResource(R.string.check_in_event_and_venue, event.title, venue)
  val contentPosition =
      when {
        prompt -> 0.18f
        state is CheckInState.Confirmed -> 0.245f
        state == Stage.OFFLINE -> 0.35f
        else -> 0.293f
      }
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val top = maxHeight * contentPosition
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = top, bottom = 16.dp),
        horizontalAlignment = if (prompt) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (prompt) 17.dp else 14.dp),
    ) {
      when {
        prompt -> PromptContent(venue)
        state is CheckInState.Confirmed -> {
          Badge(R.drawable.ic_check_in_check, 72, SuccessSoft, CircleShape)
          Heading(stringResource(R.string.check_in_confirmed), large = true)
          EventLabel(eventLabel)
          Description(
              stringResource(
                  if (hasGroup) R.string.check_in_confirmed_body
                  else R.string.check_in_confirmed_without_group,
                  entry.time(state.time),
              )
          )
        }
        showingWindow -> {
          Badge(R.drawable.ic_check_in_pin)
          Heading(entry.label())
          Description(
              if (entry.availability == CheckInAvailability.NOT_OPEN) {
                stringResource(R.string.check_in_not_open_body)
              } else {
                stringResource(
                    R.string.check_in_closed_body,
                    event.title,
                    entry.time(entry.target.window.closesAt),
                )
              }
          )
          EventLabel(eventLabel)
        }
        state is Stage -> {
          val (title, body) = problem(state)
          val icon =
              if (state == Stage.OFFLINE) R.drawable.ic_check_in_offline
              else R.drawable.ic_check_in_pin
          Badge(icon)
          Heading(stringResource(title))
          Description(
              if (state == Stage.TOO_FAR) stringResource(body, venue) else stringResource(body)
          )
          EventLabel(eventLabel)
        }
      }
    }
  }
}

@Composable
private fun CheckInButtons(
    state: CheckInState,
    showingWindow: Boolean,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onQr: () -> Unit,
    onSettings: () -> Unit,
    onOpenGroup: (() -> Unit)?,
) {
  Column(
      Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    val back = stringResource(R.string.check_in_back_to_event)
    val retry = stringResource(R.string.check_in_try_again)
    val scan = stringResource(R.string.check_in_scan_qr)
    when {
      state == Stage.READY && !showingWindow -> {
        val label = stringResource(R.string.check_in_my_location)
        PrimaryButton(label, true, CheckInTags.LOCATION, onClick = onCheck)
        OutlineButton(scan, CheckInTags.QR, onQr)
      }
      state is CheckInState.Confirmed && onOpenGroup != null -> {
        val label = stringResource(R.string.check_in_view_group)
        PrimaryButton(label, true, CheckInTags.GROUP, onClick = onOpenGroup)
      }
      showingWindow ||
          state is CheckInState.Confirmed ||
          state == Stage.EVENT_UNAVAILABLE ||
          state == Stage.REGISTRATION_REQUIRED ||
          state == Stage.SESSION_ENDED -> OutlineButton(back, CheckInTags.BACK + "_footer", onBack)
      state in qrFallback -> QrFallbackButtons(state, onCheck, onQr, onSettings)
      else -> {
        PrimaryButton(retry, true, CheckInTags.RETRY, onClick = onCheck)
        OutlineButton(back, CheckInTags.BACK + "_footer", onBack)
      }
    }
  }
}

@Composable
private fun ColumnScope.QrFallbackButtons(
    state: CheckInState,
    onCheck: () -> Unit,
    onQr: () -> Unit,
    onSettings: () -> Unit,
) {
  val approximate = state == Stage.APPROXIMATE_ONLY
  val retry = stringResource(R.string.check_in_try_again)
  val settings = stringResource(R.string.check_in_open_settings)
  if (approximate || state == Stage.DENIED) {
    val action = if (approximate) onCheck else onSettings
    Text(
        if (approximate) retry else settings,
        Modifier.align(Alignment.CenterHorizontally)
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = action)
            .testTag(if (approximate) CheckInTags.RETRY else CheckInTags.SETTINGS)
            .padding(12.dp),
        style = MaterialTheme.typography.titleSmall.exact(),
        color = Ink,
    )
  }
  val scan = stringResource(R.string.check_in_scan_qr)
  PrimaryButton(scan, true, CheckInTags.QR, onClick = onQr)
  if (approximate) OutlineButton(settings, CheckInTags.SETTINGS, onSettings)
  else OutlineButton(retry, CheckInTags.RETRY, onCheck)
}

private val qrFallback =
    setOf(Stage.DENIED, Stage.APPROXIMATE_ONLY, Stage.TOO_FAR, Stage.NO_FIX, Stage.TIMED_OUT)

private fun problem(stage: Stage): Pair<Int, Int> =
    when (stage) {
      Stage.DENIED -> R.string.check_in_denied to R.string.check_in_denied_body
      Stage.APPROXIMATE_ONLY -> R.string.check_in_approximate to R.string.check_in_approximate_body
      Stage.TOO_FAR -> R.string.check_in_too_far to R.string.check_in_too_far_body
      Stage.NO_FIX -> R.string.check_in_no_fix to R.string.check_in_no_fix_body
      Stage.TIMED_OUT -> R.string.check_in_timed_out to R.string.check_in_timed_out_body
      Stage.OFFLINE -> R.string.check_in_offline to R.string.check_in_offline_body
      Stage.STATUS_ERROR -> R.string.check_in_status_error to R.string.check_in_status_error_body
      Stage.WINDOW_CHANGED ->
          R.string.check_in_window_changed to R.string.check_in_window_changed_body
      Stage.REGISTRATION_REQUIRED ->
          R.string.check_in_registration_required to R.string.check_in_registration_required_body
      Stage.EVENT_UNAVAILABLE ->
          R.string.check_in_event_unavailable to R.string.check_in_event_unavailable_body
      Stage.SESSION_ENDED -> R.string.check_in_session_ended to R.string.check_in_session_ended_body
      else -> R.string.check_in_save_error to R.string.check_in_save_error_body
    }

@Composable
private fun PromptContent(venue: String) {
  Badge(R.drawable.ic_check_in_pin, tint = AccentText, background = Color(0xFFFDEDED))
  Heading(stringResource(R.string.check_in_at_venue, venue), large = true, centered = false)
  Text(
      stringResource(R.string.check_in_prompt_body),
      style = MaterialTheme.typography.bodyLarge.copy(lineHeight = TextUnit.Unspecified).exact(),
      color = CheckInSecondaryInk,
  )
  SurfaceRow(Modifier) {
    Icon(
        painterResource(R.drawable.ic_check_in_lock),
        null,
        Modifier.size(20.dp),
        tint = Color.Unspecified,
    )
    Column(Modifier.padding(end = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
          stringResource(R.string.check_in_privacy_title),
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold).exact(),
          color = Ink,
      )
      Text(
          stringResource(R.string.check_in_privacy_body),
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal).exact(),
          color = CheckInSecondaryInk,
      )
    }
  }
}

@Composable
private fun SurfaceRow(modifier: Modifier, content: @Composable () -> Unit) {
  Row(
      modifier.fillMaxWidth().background(CheckInSurface, RoundedCornerShape(14.dp)).padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    content()
  }
}

@Composable
private fun Badge(
    @DrawableRes icon: Int,
    size: Int = 56,
    background: Color = CheckInSurface,
    shape: Shape = RoundedCornerShape(17.dp),
    tint: Color = Color.Unspecified,
) {
  Box(Modifier.size(size.dp).background(background, shape), contentAlignment = Alignment.Center) {
    Icon(painterResource(icon), null, Modifier.size(25.dp), tint = tint)
  }
}

@Composable
private fun Heading(text: String, large: Boolean = false, centered: Boolean = true) {
  val type = MaterialTheme.typography
  Text(
      text,
      modifier = Modifier.semantics { heading() },
      style = (if (large) type.headlineMedium else type.titleLarge).exact(),
      fontWeight = if (large) null else FontWeight.ExtraBold,
      textAlign = if (centered) TextAlign.Center else TextAlign.Start,
      color = Ink,
  )
}

@Composable
private fun Description(text: String) {
  Text(
      text,
      Modifier.padding(horizontal = 20.dp),
      style = MaterialTheme.typography.bodyMedium.exact(),
      textAlign = TextAlign.Center,
      color = CheckInSecondaryInk,
  )
}

@Composable
private fun EventLabel(text: String) {
  Text(
      text,
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold).exact(),
      textAlign = TextAlign.Center,
      color = CheckInSecondaryInk,
  )
}

@Composable
private fun OutlineButton(text: String, tag: String, onClick: () -> Unit) {
  Text(
      text,
      Modifier.fillMaxWidth()
          .heightIn(min = 52.dp)
          .clip(CircleShape)
          .border(1.dp, Border, CircleShape)
          .clickable(role = Role.Button, onClick = onClick)
          .testTag(tag)
          .padding(8.dp)
          .wrapContentHeight(),
      style = MaterialTheme.typography.titleSmall.exact(),
      color = Ink,
      textAlign = TextAlign.Center,
  )
}

@Composable
private fun CheckInEntry.label(): String =
    when (availability) {
      CheckInAvailability.NOT_OPEN ->
          stringResource(R.string.check_in_opens_at, time(target.window.opensAt))
      CheckInAvailability.OPEN -> stringResource(R.string.check_in_title)
      CheckInAvailability.CLOSED -> stringResource(R.string.check_in_closed)
    }

private val CheckInSecondaryInk = Color(0xFF5D5D68)
private val CheckInSurface = Color(0xFFF5F5F2)
private val CHECK_IN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun CheckInEntry.time(time: Instant): String = CHECK_IN_TIME.withZone(zone).format(time)
