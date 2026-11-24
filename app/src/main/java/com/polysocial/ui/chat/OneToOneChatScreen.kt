// Contributors: Claude (Opus 5.5, Anthropic) (the one-to-one chat screen, from #430's frames,
// #431).
package com.polysocial.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.group.GroupHeader
import com.polysocial.ui.group.MemberAvatar
import com.polysocial.ui.group.initials
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Surface

internal object OneToOneChatTags {
  const val SCREEN = "one_to_one_chat_screen"
  const val BACK = "one_to_one_chat_back"
  const val NAME = "one_to_one_chat_name"
  const val EMPTY = "one_to_one_chat_empty"
  const val READ_ONLY = "one_to_one_chat_read_only"
}

/**
 * The chat with a friend (#431, Figma section 25), full screen: the friend's name, the history and
 * the composer shared with the group chat ([ChatTimeline], [ChatComposer]), or the read-only note
 * in place of the composer once the two aren't friends or the other account was deleted.
 */
@Composable
internal fun OneToOneChatRoute(
    onBack: () -> Unit,
    viewModel: OneToOneChatViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val today by viewModel.today.collectAsStateWithLifecycle()
  Column(Modifier.fillMaxSize().background(Surface).imePadding().testTag(OneToOneChatTags.SCREEN)) {
    OneToOneChatHeader(state.friend, onBack)
    Box(Modifier.weight(1f).fillMaxWidth()) {
      when (val history = state.history) {
        ChatHistory.Loading -> LoadingState(stringResource(R.string.chat_loading))
        // A one-to-one chat has no membership to leave: Left never comes.
        ChatHistory.Failed,
        ChatHistory.Left ->
            ErrorState(
                stringResource(R.string.chat_read_failed),
                stringResource(R.string.chat_read_failed_body),
                viewModel::retry,
            )
        is ChatHistory.Ready ->
            ChatTimeline(
                history.messages,
                emptyList(),
                state.draft,
                viewModel.myUid,
                today,
                viewModel.zone,
                state.offline,
                showSenders = false,
                empty = { EmptyChat(state.friend) },
            )
      }
    }
    if (state.readOnly) ReadOnlyNote(state)
    else ChatComposer(state.draft, state.canWrite, viewModel::edit, viewModel::send)
  }
}

@Composable
private fun OneToOneChatHeader(friend: ChatFriend, onBack: () -> Unit) {
  val name =
      when (friend) {
        is ChatFriend.Named -> friend.name
        ChatFriend.Deleted -> stringResource(R.string.chat_deleted_author)
        ChatFriend.Unavailable -> stringResource(R.string.group_profile_unavailable)
        ChatFriend.Loading -> null
      }
  Column(Modifier.background(Bg)) {
    GroupHeader(OneToOneChatTags.BACK, onBack) {
      Row(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        if (name != null) {
          // "?" for a deleted account, as in Figma.
          MemberAvatar(if (friend is ChatFriend.Named) initials(name) else "?", 0)
          Text(
              name,
              Modifier.semantics { heading() }.testTag(OneToOneChatTags.NAME),
              style = MaterialTheme.typography.titleSmall,
              color = Ink,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    HorizontalDivider(color = Border)
  }
}

@Composable
private fun EmptyChat(friend: ChatFriend) {
  val firstName = (friend as? ChatFriend.Named)?.firstName
  Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
    Text(
        if (firstName.isNullOrEmpty()) stringResource(R.string.one_to_one_chat_empty)
        else stringResource(R.string.one_to_one_chat_empty_named, firstName),
        Modifier.testTag(OneToOneChatTags.EMPTY),
        color = Ink2,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun ReadOnlyNote(state: OneToOneChatState) {
  val firstName = (state.friend as? ChatFriend.Named)?.firstName
  val note =
      when {
        state.access == ChatAccess.ACCOUNT_DELETED ->
            stringResource(R.string.one_to_one_chat_account_deleted)
        firstName.isNullOrEmpty() -> stringResource(R.string.one_to_one_chat_not_friends)
        else -> stringResource(R.string.one_to_one_chat_not_friends_named, firstName)
      }
  Column(Modifier.fillMaxWidth().background(Surface)) {
    HorizontalDivider(color = Border)
    Text(
        note,
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .testTag(OneToOneChatTags.READ_ONLY),
        color = Ink2,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
  }
}
