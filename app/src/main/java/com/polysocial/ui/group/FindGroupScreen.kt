// Contributors: Claude (Opus 5.5, Anthropic) drafted Find a group from #227's Figma frames, #233.
package com.polysocial.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.common.OfflineBanner
import com.polysocial.ui.event.detail.EventDetails
import com.polysocial.ui.event.detail.timeText
import com.polysocial.ui.event.style
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.InfoSoft
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.SuccessSoft
import com.polysocial.ui.theme.Surface
import com.polysocial.ui.theme.Warning
import com.polysocial.ui.theme.WarningSoft
import java.time.format.DateTimeFormatter

/** Test tags of Find a group (#233). */
object FindGroupTags {
  const val SCREEN = "find_group_screen"
  const val BACK = "find_group_back"
  const val EVENT = "find_group_event"
  const val LOADING = "find_group_loading"
  const val ERROR = "find_group_error"
  const val EMPTY = "find_group_empty"
  const val OFFLINE_BANNER = "find_group_offline_banner"
  const val START = "find_group_start"

  /** One group card, by its group ID. */
  fun card(groupId: String) = "find_group_card_$groupId"

  /** One group's Join button, by its group ID. */
  fun join(groupId: String) = "find_group_join_$groupId"
}

/**
 * Find a group for the event in the navigation arguments (see [FindGroupViewModel]). [onBack]
 * leaves without a change; once the student joined or started a group, [onDone] leaves and tells
 * how.
 */
@Composable
fun FindGroupRoute(
    onBack: () -> Unit,
    onDone: (FindGroupDone) -> Unit,
    viewModel: FindGroupViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val message by viewModel.message.collectAsStateWithLifecycle()
  val done by viewModel.done.collectAsStateWithLifecycle()
  val leave by rememberUpdatedState(onDone)
  LaunchedEffect(done) { done?.let(leave) }
  FindGroupScreen(
      state,
      message,
      FindGroupActions(
          onBack = onBack,
          onRetry = viewModel::retry,
          onJoin = viewModel::join,
          onStart = viewModel::start,
          onMessageShown = viewModel::onMessageShown,
      ),
  )
}

/** What Find a group's buttons do. */
class FindGroupActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onJoin: (groupId: String) -> Unit = {},
    val onStart: () -> Unit = {},
    val onMessageShown: (FindGroupMessage) -> Unit = {},
)

/**
 * Draws [state] as in #227's Figma frames (section 11): the event, then the open groups best match
 * first, each with its members, how many places are left, why it fits and Join, and Start a new
 * group at the bottom. Also "searching", "no group yet", "couldn't load" with Retry, "offline"
 * (Join and Start disabled) and "joining". [message] is shown once in a Snackbar.
 */
@Composable
fun FindGroupScreen(
    state: FindGroupUiState,
    message: FindGroupMessage?,
    actions: FindGroupActions,
    modifier: Modifier = Modifier,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val toShow = message?.let { it to stringResource(it.text) }
  val shown by rememberUpdatedState(actions.onMessageShown)
  LaunchedEffect(toShow) {
    val (shownMessage, text) = toShow ?: return@LaunchedEffect
    try {
      snackbarHostState.showSnackbar(text)
    } finally {
      // Only this message: a newer one that cancelled this Snackbar must still show.
      shown(shownMessage)
    }
  }
  Column(modifier.fillMaxSize().background(Bg).testTag(FindGroupTags.SCREEN)) {
    Header(actions.onBack)
    Box(Modifier.weight(1f)) {
      when (state) {
        FindGroupUiState.Loading ->
            LoadingState(
                stringResource(R.string.find_group_searching),
                Modifier.testTag(FindGroupTags.LOADING),
            )
        FindGroupUiState.CouldNotLoad ->
            ErrorState(
                title = stringResource(R.string.find_group_error_title),
                message = stringResource(R.string.find_group_error_body),
                onRetry = actions.onRetry,
                modifier = Modifier.testTag(FindGroupTags.ERROR),
            )
        is FindGroupUiState.Loaded -> Groups(state, actions)
      }
      SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
    if (state is FindGroupUiState.Loaded) StartBar(state, actions.onStart)
  }
}

/** Back and the title, as on Edit event. */
@Composable
private fun Header(onBack: () -> Unit) {
  Row(
      Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 6.dp, end = 20.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        Modifier.size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onBack)
            .testTag(FindGroupTags.BACK),
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
        stringResource(R.string.find_group_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Ink,
        modifier = Modifier.semantics { heading() },
    )
  }
}

@Composable
private fun Groups(state: FindGroupUiState.Loaded, actions: FindGroupActions) {
  Column(
      Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    state.event?.let { EventCard(it) }
    if (state.isOffline) {
      OfflineBanner(
          stringResource(R.string.find_group_offline),
          tag = FindGroupTags.OFFLINE_BANNER,
          verticalAlignment = Alignment.Top,
      )
    }
    if (state.suggestions.isEmpty()) {
      Empty()
    } else {
      Text(
          stringResource(R.string.find_group_list_heading),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = Ink2,
      )
      state.suggestions.forEach { suggestion ->
        GroupCard(
            suggestion,
            joining = state.pending == PendingGroupWrite.Join(suggestion.groupId),
            enabled = state.pending == null && !state.isOffline,
            onJoin = { actions.onJoin(suggestion.groupId) },
        )
      }
      PrivacyNote()
    }
  }
}

/** The event: its category tile, title, day, times and place. */
@Composable
private fun EventCard(details: EventDetails) {
  val event = details.event
  val style = event.category.style
  val day = details.date.format(DAY_FORMAT)
  val time = timeText(details)
  Row(
      Modifier.fillMaxWidth()
          .background(Surface, RoundedCornerShape(16.dp))
          .padding(12.dp)
          .testTag(FindGroupTags.EVENT),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        Modifier.size(40.dp).background(style.tileColor, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(style.icon),
          contentDescription = stringResource(style.label),
          tint = style.color,
          modifier = Modifier.size(20.dp),
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(event.title, style = MaterialTheme.typography.titleSmall, color = Ink)
      Text(
          if (event.locationName.isBlank())
              stringResource(R.string.find_group_event_when, day, time)
          else stringResource(R.string.find_group_event_when_where, day, time, event.locationName),
          style = MaterialTheme.typography.bodySmall,
          color = Ink2,
      )
    }
  }
}

/** One suggested group (Figma "Group card"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    suggestion: GroupSuggestion,
    joining: Boolean,
    enabled: Boolean,
    onJoin: () -> Unit,
) {
  val taken = suggestion.members.size
  val left = (suggestion.capacity - taken).coerceAtLeast(0)
  Column(
      Modifier.fillMaxWidth()
          .border(1.dp, Border, RoundedCornerShape(16.dp))
          .padding(14.dp)
          .testTag(FindGroupTags.card(suggestion.groupId)),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        suggestion.members.forEachIndexed { index, member -> Avatar(member, index) }
      }
      Column(Modifier.weight(1f)) {
        Text(
            stringResource(R.string.find_group_places_taken, taken, suggestion.capacity),
            style = MaterialTheme.typography.titleSmall,
            color = Ink,
        )
        Text(
            pluralStringResource(R.plurals.find_group_places_left, left, left),
            style = MaterialTheme.typography.bodySmall,
            color = Ink2,
        )
      }
      JoinButton(suggestion.groupId, joining, enabled, onJoin)
    }
    val known = suggestion.members.mapNotNull { it.profile }
    if (known.isNotEmpty()) {
      Text(
          known.joinToString("   ") { profile ->
            listOf(profile.displayName, profile.section, profile.year)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
          },
          style = MaterialTheme.typography.bodySmall,
          color = Ink2,
      )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (suggestion.sharedInterests > 0) {
        Reason(
            pluralStringResource(
                R.plurals.find_group_shared_interests,
                suggestion.sharedInterests,
                suggestion.sharedInterests,
            ),
            AccentText,
            AccentSoft,
        )
      }
      suggestion.sameSection?.let {
        Reason(stringResource(R.string.find_group_same_section, it), Info, InfoSoft)
      }
      suggestion.sameYear?.let {
        Reason(stringResource(R.string.find_group_same_year, it), Success, SuccessSoft)
      }
    }
  }
}

/** A member's initials, or "?" when their profile couldn't be read, in one of Figma's colors. */
@Composable
private fun Avatar(member: GroupMember, index: Int) {
  val (fg, bg) = AVATAR_COLORS[index % AVATAR_COLORS.size]
  Box(
      Modifier.size(34.dp).border(2.dp, Bg, CircleShape).background(bg, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        member.profile?.displayName?.let(::initials) ?: "?",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = fg,
    )
  }
}

@Composable
private fun JoinButton(groupId: String, joining: Boolean, enabled: Boolean, onJoin: () -> Unit) {
  val active = enabled || joining
  Row(
      Modifier.heightIn(min = 48.dp)
          .clip(RoundedCornerShape(24.dp))
          .clickable(enabled = enabled, role = Role.Button, onClick = onJoin)
          .testTag(FindGroupTags.join(groupId)),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
        Modifier.background(if (active) Ink else Disabled, RoundedCornerShape(18.dp))
            .heightIn(min = 36.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (joining) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = Bg,
            trackColor = Bg.copy(alpha = 0.3f),
            strokeWidth = 2.dp,
        )
      }
      Text(
          stringResource(if (joining) R.string.find_group_joining else R.string.find_group_join),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = if (active) Bg else Ink3,
      )
    }
  }
}

/** One reason the group fits, as a colored chip. */
@Composable
private fun Reason(text: String, color: Color, background: Color) {
  Text(
      text,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      color = color,
      modifier =
          Modifier.background(background, RoundedCornerShape(12.dp))
              .padding(horizontal = 10.dp, vertical = 4.dp),
  )
}

@Composable
private fun PrivacyNote() {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(
        painterResource(R.drawable.ic_shield),
        contentDescription = null,
        tint = Ink3,
        modifier = Modifier.size(14.dp),
    )
    Text(
        stringResource(R.string.find_group_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = Ink3,
    )
  }
}

/** Figma "Find a group · no group yet". */
@Composable
private fun Empty() {
  Column(
      Modifier.fillMaxWidth().padding(top = 72.dp).testTag(FindGroupTags.EMPTY),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
        Modifier.size(64.dp).background(Surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(R.drawable.ic_people),
          contentDescription = null,
          tint = Ink2,
          modifier = Modifier.size(28.dp),
      )
    }
    Text(
        stringResource(R.string.find_group_empty_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.ExtraBold,
        color = Ink,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        stringResource(R.string.find_group_empty_body),
        style = MaterialTheme.typography.bodyMedium,
        color = Ink2,
        textAlign = TextAlign.Center,
    )
  }
}

/**
 * Start a new group, at the bottom, above a hairline: the main action (red) when there is no open
 * group, a secondary one (outlined) otherwise, as in Figma. Disabled offline and while a write
 * runs.
 */
@Composable
private fun StartBar(state: FindGroupUiState.Loaded, onStart: () -> Unit) {
  val starting = state.pending == PendingGroupWrite.Start
  val enabled = state.pending == null && !state.isOffline
  val main = state.suggestions.isEmpty()
  val (background, content) =
      when {
        !enabled && !starting -> Disabled to Ink3
        main -> Accent to Bg
        else -> Bg to Ink
      }
  Column(Modifier.fillMaxWidth().background(Bg)) {
    HorizontalDivider(color = Border)
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 13.dp, bottom = 24.dp)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(background)
            .let {
              if (main || !enabled) it else it.border(1.dp, Border, RoundedCornerShape(26.dp))
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onStart)
            .testTag(FindGroupTags.START),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (starting) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = content,
            trackColor = content.copy(alpha = 0.3f),
            strokeWidth = 2.dp,
        )
      } else {
        Icon(
            painterResource(R.drawable.ic_plus),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
      }
      Text(
          stringResource(if (starting) R.string.find_group_starting else R.string.find_group_start),
          style = MaterialTheme.typography.titleSmall,
          color = content,
      )
    }
  }
}

/** "Julia Schmid" → "JS", "Léa" → "L". */
private fun initials(name: String): String =
    name
        .split(' ')
        .filter { it.isNotBlank() }
        .let { words ->
          when {
            words.isEmpty() -> "?"
            words.size == 1 -> words.first().take(1)
            else -> words.first().take(1) + words.last().take(1)
          }
        }
        .uppercase()

private val FindGroupMessage.text: Int
  get() =
      when (this) {
        FindGroupMessage.GROUP_FULL -> R.string.find_group_full
        FindGroupMessage.ALREADY_IN_GROUP -> R.string.find_group_already_in_group
        FindGroupMessage.GROUP_GONE -> R.string.find_group_gone
        FindGroupMessage.EVENT_GONE -> R.string.find_group_event_gone
        FindGroupMessage.OFFLINE -> R.string.find_group_offline
        FindGroupMessage.UNEXPECTED -> R.string.find_group_unexpected
      }

/** Figma's "Fri 3 Oct". */
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")

/** The avatars' text and background colors, in order (Figma's sample members). */
private val AVATAR_COLORS =
    listOf(
        Info to InfoSoft,
        Success to SuccessSoft,
        Warning to WarningSoft,
        AccentText to AccentSoft,
    )
