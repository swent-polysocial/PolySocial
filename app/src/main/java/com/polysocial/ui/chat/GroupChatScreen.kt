// Contributors: OpenAI Codex (group chat UI and Figma avatar colors, #236 and #238).
// Contributors: OpenAI Codex (neutral deleted author label, #241).
// Contributors: Claude (Opus 5.5, Anthropic) (the timeline, bubbles and composer moved to
// ChatComponents.kt, to share them with the one-to-one chat, #431).
package com.polysocial.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.group.GroupCategoryTile
import com.polysocial.ui.group.GroupHeader
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Surface
import java.time.format.DateTimeFormatter

/** The group chat screen's own tags; its timeline and composer use the shared [ChatTags]. */
internal object GroupChatTags {
  const val SCREEN = "group_chat_screen"
  const val BACK = "group_chat_back"
}

@Composable
internal fun GroupChatRoute(onBack: () -> Unit, viewModel: GroupChatViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val today by viewModel.today.collectAsStateWithLifecycle()
  val ready = state.history as? ChatHistory.Ready
  Column(Modifier.fillMaxSize().background(Surface).imePadding().testTag(GroupChatTags.SCREEN)) {
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
                history.messages,
                history.members,
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
    GroupHeader(GroupChatTags.BACK, onBack) {
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

private val CHAT_TIME = DateTimeFormatter.ofPattern("HH:mm")
