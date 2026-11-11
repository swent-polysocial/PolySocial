// Contributors: OpenAI Codex (group roster and leave UI, #234); PolySocial Figma #227
// (design and group_open_place.png).
package com.polysocial.ui.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.ui.common.ConfirmationSheet
import com.polysocial.ui.common.OfflineBanner
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.common.SheetIcon
import com.polysocial.ui.common.SheetSecondaryButton
import com.polysocial.ui.common.SheetTitle
import com.polysocial.ui.common.interestLabel
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Surface

internal object YourGroupTags {
  const val SCREEN = "your_group_screen"
  const val BACK = "your_group_back"
  const val EVENT = "your_group_event"
  const val OFFLINE = "your_group_offline"
  const val LEAVE = "your_group_leave"
  const val CONFIRM = "your_group_confirm_leave"
  const val STAY = "your_group_stay"
  const val RETRY = "your_group_retry"
}

@Composable
internal fun YourGroupRoute(
    onBack: () -> Unit,
    onLeft: () -> Unit,
    viewModel: YourGroupViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val left = state.leave?.stage == LeaveGroupRequest.Stage.LEFT
  LaunchedEffect(left) { if (left) onLeft() }
  Column(Modifier.fillMaxSize().background(Bg).testTag(YourGroupTags.SCREEN)) {
    GroupHeader(R.string.group_screen_title, YourGroupTags.BACK, onBack)
    Column(
        Modifier.weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      state.event?.let { GroupEventCard(it, YourGroupTags.EVENT) }
      when (val roster = state.roster) {
        GroupRoster.Loading -> LoadingRoster()
        is GroupRoster.Members -> MemberRoster(roster)
        is GroupRoster.Unavailable -> UnavailableRoster(roster)
      }
    }
    HorizontalDivider(color = Border)
    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 13.dp, bottom = 24.dp)) {
      when (state.roster) {
        GroupRoster.Unavailable.MISSING ->
            PrimaryButton(
                stringResource(R.string.event_detail_navigate_back),
                true,
                YourGroupTags.RETRY,
                onClick = onBack,
            )
        is GroupRoster.Unavailable ->
            PrimaryButton(
                stringResource(R.string.group_try_again),
                true,
                YourGroupTags.RETRY,
                onClick = viewModel::retry,
            )
        else ->
            LeaveButton(
                enabled = (state.roster as? GroupRoster.Members)?.offline == false,
                onClick = viewModel::askToLeave,
            )
      }
    }
  }
  state.leave?.let { LeaveSheet(it, viewModel::confirmLeave, viewModel::stay) }
}

@Composable
private fun MemberRoster(roster: GroupRoster.Members) {
  if (roster.offline) {
    OfflineBanner(
        stringResource(R.string.group_saved_offline),
        tag = YourGroupTags.OFFLINE,
        verticalAlignment = Alignment.Top,
    )
  }
  Text(
      stringResource(R.string.group_members_count, roster.people.size, roster.capacity),
      style = MaterialTheme.typography.labelLarge,
      color = Ink2,
      modifier = Modifier.semantics { heading() },
  )
  roster.people.forEachIndexed { index, member ->
    MemberRow(member, member.uid == roster.myUid, OTHERS_COLORS[index % OTHERS_COLORS.size])
  }
  val freePlaces = roster.capacity - roster.people.size
  if (freePlaces > 0) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Image(painterResource(R.drawable.group_open_place), null, Modifier.size(40.dp))
      Text(
          pluralStringResource(R.plurals.your_group_places_left, freePlaces, freePlaces),
          style = MaterialTheme.typography.bodySmall,
          color = Ink2,
      )
    }
  }
  Row(
      Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(painterResource(R.drawable.ic_tab_chats), contentDescription = null, Modifier.size(20.dp))
    Column {
      Text(stringResource(R.string.group_chat_title), style = MaterialTheme.typography.titleSmall)
      Text(
          stringResource(R.string.group_chat_unavailable),
          style = MaterialTheme.typography.bodySmall,
          color = Ink2,
      )
    }
  }
  GroupPrivacyNote()
}

@Composable
private fun MemberRow(member: GroupMember, isMe: Boolean, colorIndex: Int) {
  val profile = member.profile
  val name = if (isMe) stringResource(R.string.your_group_you) else profile?.displayName
  val details =
      if (profile == null) {
        stringResource(R.string.group_profile_unavailable)
      } else {
        val interests = profile.interests.mapNotNull(::interestLabel).map { stringResource(it) }
        listOf(profile.section, profile.year, interests.joinToString(", "))
            .filter(String::isNotBlank)
            .joinToString(" · ")
      }
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.heightIn(min = 40.dp),
  ) {
    if (name == null) {
      Box(
          Modifier.size(40.dp).background(Disabled, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(painterResource(R.drawable.ic_tab_profile), null, Modifier.size(20.dp), tint = Ink3)
      }
    } else {
      MemberAvatar(
          if (isMe) name else initials(name),
          if (isMe) 0 else colorIndex,
          Modifier.size(40.dp),
      )
    }
    Column {
      Text(
          name ?: stringResource(R.string.group_unknown_member),
          style = MaterialTheme.typography.titleSmall,
      )
      Text(details, color = Ink2, style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun LoadingRoster() {
  Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    CircularProgressIndicator(Modifier.size(14.dp), color = Ink2, strokeWidth = 2.dp)
    Text(
        stringResource(R.string.group_loading),
        style = MaterialTheme.typography.labelLarge,
        color = Ink2,
    )
  }
  repeat(3) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.size(40.dp).background(Disabled, CircleShape))
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(110.dp).height(10.dp).background(Disabled, CircleShape))
        Box(Modifier.width(170.dp).height(10.dp).background(Disabled, CircleShape))
      }
    }
  }
}

@Composable
private fun UnavailableRoster(reason: GroupRoster.Unavailable) {
  val (title, message) =
      when (reason) {
        GroupRoster.Unavailable.OFFLINE -> R.string.group_error_title to R.string.group_not_saved
        GroupRoster.Unavailable.MISSING ->
            R.string.group_missing_title to R.string.group_missing_body
        GroupRoster.Unavailable.FAILED ->
            R.string.group_error_title to R.string.find_group_error_body
      }
  Column(
      Modifier.fillMaxWidth().padding(top = 96.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
        Modifier.size(64.dp).background(AccentSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(R.drawable.ic_alert), null, Modifier.size(26.dp), tint = AccentText)
    }
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        stringResource(message),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = Ink2,
    )
  }
}

@Composable
private fun LeaveButton(enabled: Boolean, onClick: () -> Unit) {
  OutlinedButton(
      onClick,
      Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(YourGroupTags.LEAVE),
      enabled = enabled,
      colors =
          ButtonDefaults.outlinedButtonColors(
              contentColor = AccentText,
              disabledContentColor = Ink3,
              disabledContainerColor = Disabled,
          ),
      border = if (enabled) BorderStroke(1.dp, Border) else null,
  ) {
    Icon(painterResource(R.drawable.ic_log_out), contentDescription = null, Modifier.size(18.dp))
    Text(
        stringResource(R.string.group_leave),
        Modifier.padding(start = 8.dp),
        style = MaterialTheme.typography.titleSmall,
    )
  }
}

@Composable
private fun LeaveSheet(request: LeaveGroupRequest, onConfirm: () -> Unit, onStay: () -> Unit) {
  val busy =
      request.stage == LeaveGroupRequest.Stage.LEAVING ||
          request.stage == LeaveGroupRequest.Stage.LEFT
  val error =
      when (request.stage) {
        LeaveGroupRequest.Stage.OFFLINE -> R.string.group_leave_offline
        LeaveGroupRequest.Stage.FAILED -> R.string.group_leave_failed
        else -> null
      }
  ConfirmationSheet(locked = busy, onDismissRequest = onStay) { dismiss ->
    SheetIcon(R.drawable.ic_log_out, tint = AccentText)
    SheetTitle(R.string.group_leave_title)
    Text(
        request.eventTitle?.let { stringResource(R.string.group_leave_event_body, it) }
            ?: stringResource(R.string.group_leave_body),
        style = MaterialTheme.typography.bodyMedium,
        color = Ink2,
    )
    if (error != null) {
      Text(stringResource(error), color = AccentText, style = MaterialTheme.typography.bodySmall)
    }
    val action =
        when {
          busy -> R.string.group_leaving
          error != null -> R.string.group_try_again
          else -> R.string.group_leave
        }
    PrimaryButton(
        stringResource(action),
        enabled = !busy,
        tag = YourGroupTags.CONFIRM,
        loading = busy,
        color = Accent,
        onClick = onConfirm,
    )
    SheetSecondaryButton(R.string.group_stay, !busy, YourGroupTags.STAY, dismiss)
  }
}
