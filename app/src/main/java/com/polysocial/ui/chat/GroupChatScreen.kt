// Contributors: OpenAI Codex (group chat UI and Figma avatar colors, #236 and #238).
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.common.OfflineBanner
import com.polysocial.ui.group.GroupCategoryTile
import com.polysocial.ui.group.GroupHeader
import com.polysocial.ui.group.MemberAvatar
import com.polysocial.ui.group.OTHERS_COLORS
import com.polysocial.ui.group.initials
import com.polysocial.ui.theme.Accent
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Surface
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object ChatTags {
  const val SCREEN = "group_chat_screen"
  const val BACK = "group_chat_back"
  const val INPUT = "group_chat_input"
  const val SEND = "group_chat_send"
  const val RETRY_SEND = "group_chat_retry_send"
  const val OFFLINE = "group_chat_offline"
  const val TIMELINE = "group_chat_timeline"
}

@Composable
internal fun GroupChatRoute(onBack: () -> Unit, viewModel: GroupChatViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val today by viewModel.today.collectAsStateWithLifecycle()
  val ready = state.history as? ChatHistory.Ready
  Column(Modifier.fillMaxSize().background(Surface).imePadding().testTag(ChatTags.SCREEN)) {
    ChatHeader(state, onBack)
    Box(Modifier.weight(1f).fillMaxWidth()) {
      when (val history = state.history) {
        ChatHistory.Loading -> LoadingState(stringResource(R.string.chat_loading))
        ChatHistory.Failed ->
            ErrorState(
                stringResource(R.string.chat_read_failed),
                stringResource(R.string.chat_read_failed_body),
                viewModel::retry,
            )
        ChatHistory.Left ->
            ChatMessageState(R.string.chat_membership_ended, R.string.chat_membership_body)
        is ChatHistory.Ready ->
            ChatTimeline(
                history,
                state.draft,
                viewModel.myUid,
                today,
                viewModel.zone,
                state.offline,
            )
      }
    }
    ChatComposer(state.draft, ready != null, viewModel::edit, viewModel::send)
  }
}

@Composable
private fun ChatHeader(state: ChatState, onBack: () -> Unit) {
  Column(Modifier.background(Bg)) {
    GroupHeader(ChatTags.BACK, onBack) {
      val details = state.event
      Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        if (details != null) GroupCategoryTile(details)
        Column {
          Text(
              details?.event?.title ?: stringResource(R.string.group_chat_title),
              style = MaterialTheme.typography.titleSmall,
          )
          val count = (state.history as? ChatHistory.Ready)?.members?.size
          if (count != null) {
            val members = pluralStringResource(R.plurals.chat_members, count, count)
            val summary =
                if (details == null) members
                else
                    stringResource(
                        R.string.chat_header_summary,
                        members,
                        details.date.format(CHAT_DAY),
                        details.startTime.format(CHAT_TIME),
                    )
            Text(summary, color = Ink2, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
    HorizontalDivider(color = Border)
  }
}

@Composable
private fun ChatTimeline(
    history: ChatHistory.Ready,
    draft: ChatDraft,
    me: String?,
    today: LocalDate,
    zone: ZoneId,
    offline: Boolean,
) {
  val pending = draft.takeIf { it.stage == ChatDraft.Stage.SENDING }
  val messages = history.messages.filterNot { pending?.id == it.id }
  val peers = history.members.filterNot { it.uid == me }
  Column(Modifier.fillMaxSize()) {
    if (offline) {
      OfflineBanner(
          stringResource(R.string.chat_offline),
          ChatTags.OFFLINE,
          Modifier.padding(12.dp),
      )
    }
    if (messages.isEmpty() && pending == null) {
      ChatMessageState(R.string.chat_empty, R.string.chat_empty_body)
    } else {
      val rowCount = messages.size + if (pending == null) 0 else 1
      val list = rememberLazyListState(initialFirstVisibleItemIndex = rowCount - 1)
      var previousCount by remember { mutableIntStateOf(rowCount) }
      LaunchedEffect(rowCount) {
        val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (pending != null || lastVisible >= previousCount - 1) list.scrollToItem(rowCount - 1)
        previousCount = rowCount
      }
      LazyColumn(
          modifier = Modifier.testTag(ChatTags.TIMELINE),
          state = list,
          contentPadding = PaddingValues(12.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
          val previous = messages.getOrNull(index - 1)
          val day = message.createdAt.atZone(zone).toLocalDate()
          val newDay = day != previous?.createdAt?.atZone(zone)?.toLocalDate()
          if (newDay) {
            Text(
                if (day == today) stringResource(R.string.events_today) else day.format(CHAT_DAY),
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
                color = Ink2,
                style = MaterialTheme.typography.labelMedium,
            )
          }
          val mine = message.senderId == me
          val peerIndex = peers.indexOfFirst { it.uid == message.senderId }
          val name =
              if (mine || (!newDay && previous.senderId == message.senderId)) null
              else
                  peers.getOrNull(peerIndex)?.profile?.displayName
                      ?: stringResource(R.string.group_unknown_member)
          val color = OTHERS_COLORS.getOrElse(peerIndex) { OTHERS_COLORS.first() }
          ChatBubble(message.text, mine, name, avatarColor = color)
        }
        if (pending != null)
            item { ChatBubble(pending.text.trim(), mine = true, name = null, sending = true) }
      }
    }
  }
}

@Composable
private fun ChatBubble(
    text: String,
    mine: Boolean,
    name: String?,
    sending: Boolean = false,
    avatarColor: Int = OTHERS_COLORS.first(),
) {
  Column(
      Modifier.fillMaxWidth(),
      horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
  ) {
    if (name != null)
        Text(
            name,
            Modifier.padding(start = 36.dp),
            color = Ink2,
            style = MaterialTheme.typography.labelMedium,
        )
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (!mine) {
        Box(Modifier.size(28.dp)) {
          if (name != null) MemberAvatar(initials(name), avatarColor, size = 28.dp)
        }
      }
      val shape =
          RoundedCornerShape(
              topStart = 16.dp,
              topEnd = 16.dp,
              bottomStart = if (mine) 16.dp else 4.dp,
              bottomEnd = if (mine) 4.dp else 16.dp,
          )
      Text(
          text,
          Modifier.widthIn(max = 250.dp)
              .background(if (sending) Ink2 else if (mine) Ink else Bg, shape)
              .padding(horizontal = 12.dp, vertical = 10.dp),
          color = if (mine) Bg else Ink,
          style = MaterialTheme.typography.bodyMedium,
      )
    }
    if (sending)
        Text(
            stringResource(R.string.chat_sending),
            color = Ink2,
            style = MaterialTheme.typography.labelMedium,
        )
  }
}

@Composable
private fun ChatMessageState(title: Int, body: Int) {
  Column(
      Modifier.fillMaxSize().padding(28.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = Ink2,
    )
  }
}

@Composable
private fun ChatComposer(
    draft: ChatDraft,
    ready: Boolean,
    onEdit: (String) -> Unit,
    onSend: () -> Unit,
) {
  val busy = draft.stage == ChatDraft.Stage.SENDING
  val enabled = ready && draft.canSend
  val messageLabel = stringResource(R.string.chat_message)
  Column(Modifier.fillMaxWidth().background(Bg)) {
    HorizontalDivider(color = Border)
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      val error =
          when (draft.stage) {
            ChatDraft.Stage.OFFLINE -> R.string.chat_send_offline
            ChatDraft.Stage.FAILED -> R.string.chat_send_failed
            else -> null
          }
      if (error != null) {
        Text(
            stringResource(error),
            Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onSend)
                .heightIn(min = 32.dp)
                .testTag(ChatTags.RETRY_SEND),
            color = AccentText,
            style = MaterialTheme.typography.labelMedium,
        )
      }
      Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        BasicTextField(
            value = if (busy) "" else draft.text,
            onValueChange = onEdit,
            modifier =
                Modifier.weight(1f)
                    .semantics { contentDescription = messageLabel }
                    .testTag(ChatTags.INPUT),
            enabled = !busy,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
            cursorBrush = SolidColor(Ink),
            decorationBox = { field ->
              Box(
                  Modifier.fillMaxWidth()
                      .heightIn(min = 44.dp)
                      .background(Surface, CircleShape)
                      .padding(horizontal = 16.dp, vertical = 10.dp),
                  contentAlignment = Alignment.CenterStart,
              ) {
                if (draft.text.isEmpty() || busy)
                    Text(messageLabel, color = Ink2, style = MaterialTheme.typography.bodyMedium)
                field()
              }
            },
        )
        Box(
            Modifier.size(48.dp)
                .clip(CircleShape)
                .background(if (enabled) Accent else Accent.copy(alpha = 0.4f))
                .clickable(enabled = enabled, role = Role.Button, onClick = onSend)
                .testTag(ChatTags.SEND),
            contentAlignment = Alignment.Center,
        ) {
          Icon(
              painterResource(R.drawable.ic_chat_send),
              stringResource(R.string.chat_send),
              Modifier.size(18.dp),
              tint = Bg,
          )
        }
      }
    }
  }
}

private val CHAT_DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val CHAT_TIME = DateTimeFormatter.ofPattern("HH:mm")
