// Contributors: Claude (Opus 5.5, Anthropic) (the Chats list, from #362's Figma section 16, #364).
package com.polysocial.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.common.OfflineBanner
import com.polysocial.ui.common.secondaryTextStyle
import com.polysocial.ui.event.style
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Surface
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayNameStyle

/** Test tags of the Chats tab. */
object ChatsTags {
  const val LIST = "chats_list"
  const val EMPTY = "chats_empty"
  const val OFFLINE_BANNER = "chats_offline_banner"

  /** The row of the group [groupId] of the event [eventId]. */
  fun row(eventId: String, groupId: String) = "chats_row_${eventId}_$groupId"
}

/**
 * The Chats tab (#364): the student's group chats, newest activity first. [onOpenChat] opens a
 * chat, given its event and group.
 */
@Composable
fun ChatsRoute(
    onOpenChat: (eventId: String, groupId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  ChatsScreen(state, onOpenChat, viewModel::retry, modifier)
}

/**
 * The Chats list from [state] alone (Figma section 16): loading, couldn't load with [onRetry],
 * "Join a group to chat", or the chats with the offline banner above them when offline.
 */
@Composable
fun ChatsScreen(
    state: ChatsUiState,
    onOpenChat: (eventId: String, groupId: String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  when (state) {
    ChatsUiState.Loading -> LoadingState(stringResource(R.string.loading_chats), modifier)
    ChatsUiState.Error ->
        ErrorState(
            stringResource(R.string.chats_error_title),
            stringResource(R.string.chats_error_body),
            onRetry,
            modifier,
        )
    is ChatsUiState.Chats ->
        Column(modifier.fillMaxSize()) {
          if (state.isOffline) {
            OfflineBanner(
                stringResource(R.string.chats_offline),
                ChatsTags.OFFLINE_BANNER,
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                textStyle = secondaryTextStyle(),
                verticalAlignment = Alignment.Top,
            )
          }
          if (state.rows.isEmpty()) {
            EmptyChats(Modifier.weight(1f))
          } else {
            LazyColumn(
                Modifier.fillMaxSize().testTag(ChatsTags.LIST),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
              items(state.rows, key = { "${it.eventId}/${it.groupId}" }) { row ->
                ChatRowItem(row) { onOpenChat(row.eventId, row.groupId) }
              }
            }
          }
        }
  }
}

@Composable
private fun ChatRowItem(row: ChatRow, onClick: () -> Unit) {
  Column(
      Modifier.fillMaxWidth()
          .clickable(role = Role.Button, onClick = onClick)
          .testTag(ChatsTags.row(row.eventId, row.groupId))
  ) {
    Row(
        Modifier.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      CategoryTile(row.event)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          EventTitle(row.event, Modifier.weight(1f))
          row.latest?.let {
            Text(it.time.label(), style = smallTextStyle(), color = Ink3, maxLines = 1)
          }
        }
        Text(
            members(row),
            style = smallTextStyle(),
            color = Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val latest = row.latest
        Text(
            if (latest == null) stringResource(R.string.chats_no_messages) else preview(latest),
            style = secondaryTextStyle(),
            color = if (latest == null) Ink3 else Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
    }
    HorizontalDivider(color = Border)
  }
}

/** The event's category tile; a plain one while the event is loading or unavailable. */
@Composable
private fun CategoryTile(event: ChatEvent) {
  val style = (event as? ChatEvent.Known)?.category?.style
  Box(
      Modifier.size(48.dp).background(style?.tileColor ?: Surface, RoundedCornerShape(14.dp)),
      contentAlignment = Alignment.Center,
  ) {
    if (style != null) {
      Icon(
          painterResource(style.icon),
          stringResource(style.label),
          Modifier.size(22.dp),
          tint = style.color,
      )
    } else if (event == ChatEvent.Unavailable) {
      Icon(painterResource(R.drawable.ic_tab_chats), null, Modifier.size(22.dp), tint = Ink2)
    }
  }
}

@Composable
private fun EventTitle(event: ChatEvent, modifier: Modifier) {
  when (event) {
    // A grey bar until the event answers, as the group's roster does (#234).
    ChatEvent.Loading ->
        Box(modifier.heightIn(min = 19.dp), contentAlignment = Alignment.CenterStart) {
          Box(Modifier.size(width = 140.dp, height = 10.dp).background(Disabled, CircleShape))
        }
    is ChatEvent.Known -> TitleText(event.title, modifier)
    ChatEvent.Unavailable -> TitleText(stringResource(R.string.group_chat_title), modifier)
  }
}

@Composable
private fun TitleText(text: String, modifier: Modifier) {
  Text(
      text,
      style = MaterialTheme.typography.titleSmall,
      color = Ink,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = modifier,
  )
}

/** "Léa, Tom and you"; "Just you"; or "3 members" while no other name is known. */
@Composable
private fun members(row: ChatRow): String =
    when {
      row.otherCount == 0 -> stringResource(R.string.chats_only_you)
      row.otherNames.isEmpty() ->
          pluralStringResource(R.plurals.chat_members, row.otherCount + 1, row.otherCount + 1)
      else -> stringResource(R.string.chats_members, row.otherNames.joinToString(", "))
    }

/** "Léa: …", "You: …", "Deleted account: …", or the text alone for an unnamed member. */
@Composable
private fun preview(latest: LatestMessage): String =
    when (val sender = latest.sender) {
      Sender.You ->
          stringResource(R.string.chats_latest, stringResource(R.string.chats_you), latest.text)
      is Sender.Named -> stringResource(R.string.chats_latest, sender.firstName, latest.text)
      Sender.DeletedAccount ->
          stringResource(
              R.string.chats_latest,
              stringResource(R.string.chat_deleted_author),
              latest.text,
          )
      Sender.Unknown -> latest.text
    }

@Composable
private fun ChatTime.label(): String =
    when (this) {
      is ChatTime.Today -> time.format(TIME_FORMAT)
      ChatTime.Yesterday -> stringResource(R.string.chats_yesterday)
      is ChatTime.ThisWeek ->
          day.getDisplayName(DayNameStyle.SHORT, LocalConfiguration.current.locales[0])
      is ChatTime.Earlier -> date.format(DATE_FORMAT)
    }

/** Figma's 12 sp regular text: the time and the members. */
@Composable
private fun smallTextStyle() = secondaryTextStyle().copy(fontSize = 12.sp, lineHeight = 15.sp)

/** Figma's "Chats · empty": the chat icon in a grey circle, the title and the hint, centred. */
@Composable
private fun EmptyChats(modifier: Modifier) {
  Column(
      modifier.fillMaxWidth().padding(horizontal = 40.dp).testTag(ChatsTags.EMPTY),
      verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
        Modifier.size(72.dp).background(Surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(R.drawable.ic_tab_chats), null, Modifier.size(30.dp), tint = Ink2)
    }
    Text(
        stringResource(R.string.chats_empty_title),
        style = MaterialTheme.typography.titleMedium,
        color = Ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        stringResource(R.string.chats_empty_body),
        style = MaterialTheme.typography.bodyLarge,
        color = Ink2,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 280.dp),
    )
  }
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** Figma's "Fri 3 Oct". */
private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")
