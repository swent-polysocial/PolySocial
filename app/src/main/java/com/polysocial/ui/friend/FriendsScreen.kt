// Contributors: OpenAI Codex (6.2 Sol, high; request controls, feedback and accessibility, #361).
// Contributors: OpenAI Codex (6.2 Sol, high; Compose Friends screen from Figma #358, #360,
// profile-link modifier, and review fixes for fallback copy and confirmation).
// Contributors: Claude (Opus 5.5, Anthropic) (Add by @username above the list, and its card, #452);
// Konstantinos Perdikeas (directed, reviewed and owns it).
// Contributors: Claude (Opus 5.5, Anthropic) (the Message action opening the chat, #431; the
// confirmation sheet, badge and pill moved to ui/common to share them with Unsend, #436).
package com.polysocial.ui.friend

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.ui.common.CenteredConfirmationSheet
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.IconBadge
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.common.OutlinePill
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.group.GroupHeader
import com.polysocial.ui.group.initials
import com.polysocial.ui.profile.privacyTextStyle
import com.polysocial.ui.profile.profileDetails
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.InfoSoft
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.SuccessSoft
import com.polysocial.ui.theme.Surface
import com.polysocial.ui.theme.WarningSoft

object FriendsTags {
  const val ENTRY = "profile_friends"
  const val SCREEN = "friends_screen"
  const val BACK = "friends_back"
  const val LIST = "friends_list"
  const val EMPTY = "friends_empty"
  const val OFFLINE = "friends_offline"
  const val OFFLINE_EMPTY = "friends_offline_empty"
  const val SCAN = "friends_scan"
  const val RETRY = "friends_retry"
  const val REMOVE = "friends_remove"
  const val MESSAGE = "friends_message"
  const val CONFIRM = "friends_confirm_remove"
  const val KEEP = "friends_keep"
  const val NOTICE = "friends_notice"
  const val NOTICE_ACTION = "friends_notice_action"

  fun row(uid: String) = "friends_row_$uid"

  fun options(uid: String) = "friends_options_$uid"

  fun accept(uid: String) = "friends_accept_$uid"

  fun decline(uid: String) = "friends_decline_$uid"

  fun cancel(uid: String) = "friends_cancel_$uid"
}

@Composable
fun FriendsRoute(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onScan: () -> Unit,
    onOpenChat: (pairId: String) -> Unit,
    viewModel: FriendsViewModel = hiltViewModel(),
    addByUsername: AddByUsernameViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val lookup by addByUsername.uiState.collectAsStateWithLifecycle()
  // A found student's card covers Friends until it is closed (#452).
  lookup.card?.let { card ->
    BackHandler(onBack = addByUsername::onCloseCard)
    FriendCardScreen(
        card,
        onClose = addByUsername::onCloseCard,
        onButton = addByUsername::onCardButton,
        addNote = stringResource(R.string.add_by_username_card_note),
    )
    return
  }
  val lookupActions =
      remember(addByUsername) {
        AddByUsernameActions(addByUsername::onInput, addByUsername::onLookUp)
      }
  val actions =
      remember(viewModel, onBack, onOpenProfile, onScan, onOpenChat) {
        FriendsActions(
            onBack,
            onOpenProfile,
            onScan,
            viewModel::retryLoad,
            viewModel::requestRemoval,
            viewModel::keepFriend,
            viewModel::confirmRemoval,
            viewModel::retryAction,
            viewModel::dismissNotice,
            viewModel::answerRequest,
            onMessage = { row -> onOpenChat(row.friendship.id) },
        )
      }
  FriendsScreen(state, actions) { offline -> AddByUsername(lookup, offline, lookupActions) }
}

internal data class FriendsActions(
    val onBack: () -> Unit,
    val onOpenProfile: (String) -> Unit,
    val onScan: () -> Unit,
    val onRetryLoad: () -> Unit,
    val onRemove: (FriendRow) -> Unit,
    val onKeep: () -> Unit,
    val onConfirm: () -> Unit,
    val onRetryAction: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onAnswer: (FriendRow, FriendListAction) -> Unit,
    /** Opens the chat with a friend (#431). */
    val onMessage: (FriendRow) -> Unit = {},
)

/**
 * Friends (#360). [top] is drawn above the list in every state, told whether Friends is offline:
 * the app gives it Add by @username (#452).
 */
@Composable
internal fun FriendsScreen(
    state: FriendsUiState,
    actions: FriendsActions,
    top: @Composable (offline: Boolean) -> Unit = {},
) {
  Column(Modifier.fillMaxSize().background(Bg).testTag(FriendsTags.SCREEN)) {
    GroupHeader(FriendsTags.BACK, actions.onBack) {
      Text(
          stringResource(R.string.friends_title),
          style = privacyTextStyle(17, 21.4f, FontWeight.Bold),
          modifier = Modifier.padding(start = 2.dp).semantics { heading() },
          color = Ink,
      )
    }
    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
      top(
          when (val content = state.content) {
            FriendsContent.OfflineWithoutSavedData -> true
            is FriendsContent.ListContent -> content.offline
            else -> false
          }
      )
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
      when (val content = state.content) {
        FriendsContent.Loading -> LoadingState(stringResource(R.string.friends_loading))
        FriendsContent.Error ->
            ErrorState(
                stringResource(R.string.friends_load_error),
                stringResource(R.string.friends_load_error_body),
                actions.onRetryLoad,
            )
        FriendsContent.OfflineWithoutSavedData ->
            FriendsEmpty(
                R.drawable.ic_friends_empty_wifi,
                R.string.friends_offline,
                R.string.friends_offline_without_data,
                FriendsTags.OFFLINE_EMPTY,
            )
        is FriendsContent.ListContent ->
            if (content.rows.isEmpty()) {
              FriendsEmpty(
                  R.drawable.ic_friends_empty_people,
                  R.string.friends_empty,
                  R.string.friends_empty_body,
                  FriendsTags.EMPTY,
              )
            } else {
              FriendsList(content, state.writing, actions)
            }
      }
    }
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      state.notice?.let { ActionNotice(it, state.noticeAction, actions) }
      when (val content = state.content) {
        FriendsContent.OfflineWithoutSavedData ->
            OutlinePill(R.string.retry, FriendsTags.RETRY, actions.onRetryLoad)
        is FriendsContent.ListContent ->
            if (content.offline || content.rows.isEmpty()) {
              PrimaryButton(
                  stringResource(R.string.friend_code_scan),
                  enabled = !content.offline,
                  tag = FriendsTags.SCAN,
                  onClick = actions.onScan,
              )
            } else {
              OutlinePill(R.string.friend_code_scan, FriendsTags.SCAN, actions.onScan)
            }
        else -> Unit
      }
    }
  }
  state.removal?.let { RemoveFriendSheet(it, actions) }
}

@Composable
private fun FriendsList(
    content: FriendsContent.ListContent,
    writing: Boolean,
    actions: FriendsActions,
) {
  val groups = content.rows.groupBy { it.section }
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(FriendsTags.LIST),
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (content.offline) item { OfflineFriendsBanner() }
    for (section in FriendsSection.entries) {
      val rows = groups[section] ?: continue
      item(key = section) {
        Text(
            stringResource(section.heading),
            color = Ink,
            style = privacyTextStyle(14, 18f, FontWeight.Bold),
            modifier = Modifier.semantics { heading() },
        )
      }
      items(rows, key = { it.uid }) { row -> FriendCard(row, content.offline, writing, actions) }
    }
  }
}

private val FriendsSection.heading
  get() =
      when (this) {
        FriendsSection.INCOMING -> R.string.friends_incoming
        FriendsSection.SENT -> R.string.friends_sent
        FriendsSection.FRIENDS -> R.string.friends_yours
      }

private val FriendsSection.avatarColor: Color
  get() =
      when (this) {
        FriendsSection.INCOMING -> AccentSoft
        FriendsSection.SENT -> InfoSoft
        FriendsSection.FRIENDS -> SuccessSoft
      }

@Composable
private fun FriendCard(
    row: FriendRow,
    offline: Boolean,
    writing: Boolean,
    actions: FriendsActions,
) {
  val accepted = row.section == FriendsSection.FRIENDS
  val shape = RoundedCornerShape(14.dp)
  Column(
      Modifier.fillMaxWidth()
          .clip(shape)
          .background(if (accepted) Bg else Surface)
          .border(1.dp, Border, shape),
  ) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = if (accepted && !offline) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      val topPadding =
          when {
            accepted && !offline -> 15.dp
            offline -> 13.dp
            else -> 12.dp
          }
      Row(
          Modifier.weight(1f)
              .profileLink(row, actions.onOpenProfile)
              .testTag(FriendsTags.row(row.uid))
              .padding(top = topPadding, bottom = if (accepted || offline) topPadding else 8.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        FriendIdentity(row, offline)
      }
      if (accepted && !offline)
          FriendOptions(row, enabled = !writing, actions.onRemove, actions.onMessage)
    }
    if (!accepted && !offline) RequestActions(row, enabled = !writing, actions.onAnswer)
  }
}

@Composable
private fun RequestActions(
    row: FriendRow,
    enabled: Boolean,
    onAnswer: (FriendRow, FriendListAction) -> Unit,
) {
  Row(
      Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
  ) {
    if (row.section == FriendsSection.INCOMING) {
      RequestButton(
          R.string.friends_decline,
          R.string.friends_decline_named,
          FriendsTags.decline(row.uid),
          enabled,
          row,
          FriendListAction.DECLINE,
          onAnswer,
      )
      RequestButton(
          R.string.friends_accept,
          R.string.friends_accept_named,
          FriendsTags.accept(row.uid),
          enabled,
          row,
          FriendListAction.ACCEPT,
          onAnswer,
      )
    } else {
      RequestButton(
          R.string.friends_cancel,
          R.string.friends_cancel_named,
          FriendsTags.cancel(row.uid),
          enabled,
          row,
          FriendListAction.CANCEL,
          onAnswer,
      )
    }
  }
}

@Composable
private fun RequestButton(
    @StringRes text: Int,
    @StringRes namedDescription: Int,
    tag: String,
    enabled: Boolean,
    row: FriendRow,
    action: FriendListAction,
    onAnswer: (FriendRow, FriendListAction) -> Unit,
) {
  val label = stringResource(text)
  val description = row.profile?.displayName?.let { stringResource(namedDescription, it) } ?: label
  val filled = action == FriendListAction.ACCEPT
  val background =
      when {
        !enabled -> Disabled
        filled -> Ink
        else -> Bg
      }
  val textColor =
      when {
        !enabled -> Ink3
        filled -> Bg
        else -> Ink
      }
  Box(
      Modifier.widthIn(min = 92.dp)
          .heightIn(min = 40.dp)
          .clip(CircleShape)
          .background(background)
          .then(if (filled) Modifier else Modifier.border(1.dp, Border, CircleShape))
          .clickable(enabled = enabled, role = Role.Button) { onAnswer(row, action) }
          .testTag(tag)
          .padding(horizontal = 10.dp, vertical = 8.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        label,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        style = privacyTextStyle(13, 17f, FontWeight.Bold),
        color = textColor,
        textAlign = TextAlign.Center,
    )
  }
}

private fun Modifier.profileLink(row: FriendRow, onOpenProfile: (String) -> Unit): Modifier =
    if (row.section == FriendsSection.FRIENDS) {
      clickable(role = Role.Button) { onOpenProfile(row.uid) }
    } else this

@Composable
private fun FriendIdentity(row: FriendRow, offline: Boolean) {
  val profile = row.profile
  Box(
      Modifier.size(40.dp).background(row.section.avatarColor, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        initials(profile?.displayName.orEmpty()),
        style = privacyTextStyle(13, 16f, FontWeight.Bold),
        color = Ink,
    )
  }
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(
        profile?.displayName ?: stringResource(R.string.group_profile_unavailable),
        style = privacyTextStyle(15, 19f, FontWeight.Bold),
        color = Ink,
    )
    val details =
        when {
          profile != null -> profileDetails(profile.section, profile.year)
          !offline -> null
          row.section == FriendsSection.FRIENDS -> stringResource(R.string.friends_saved_friendship)
          else -> stringResource(R.string.friends_saved_request)
        }
    if (details != null) Text(details, style = privacyTextStyle(12, 15f), color = Ink3)
  }
}

@Composable
private fun FriendOptions(
    row: FriendRow,
    enabled: Boolean,
    onRemove: (FriendRow) -> Unit,
    onMessage: (FriendRow) -> Unit,
) {
  var expanded by remember(enabled) { mutableStateOf(false) }
  val name = row.profile?.displayName ?: stringResource(R.string.group_profile_unavailable)
  Box {
    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.testTag(FriendsTags.options(row.uid)),
        enabled = enabled,
    ) {
      Icon(
          painterResource(R.drawable.ic_friends_more),
          stringResource(R.string.friends_options, name),
          Modifier.size(20.dp),
          tint = Ink,
      )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        shape = RoundedCornerShape(14.dp),
        containerColor = Bg,
    ) {
      // Chats start here (decided in #365), above Remove friend as in Figma section 25.
      DropdownMenuItem(
          text = {
            Text(
                stringResource(R.string.friends_message),
                style = privacyTextStyle(14, 18f, FontWeight.Bold),
                color = Ink,
            )
          },
          onClick = {
            expanded = false
            onMessage(row)
          },
          modifier = Modifier.testTag(FriendsTags.MESSAGE),
      )
      DropdownMenuItem(
          text = {
            Text(
                stringResource(R.string.friends_remove),
                style = privacyTextStyle(14, 18f, FontWeight.Bold),
                color = Ink,
            )
          },
          onClick = {
            expanded = false
            onRemove(row)
          },
          modifier = Modifier.testTag(FriendsTags.REMOVE),
      )
    }
  }
}

@Composable
private fun FriendsEmpty(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes body: Int,
    tag: String,
) {
  BoxWithConstraints(Modifier.fillMaxSize().testTag(tag)) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(start = 40.dp, end = 40.dp, bottom = minOf(90.dp, maxHeight / 5)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
      IconBadge(icon)
      Text(
          stringResource(title),
          style = privacyTextStyle(22, 28f, FontWeight.ExtraBold),
          color = Ink,
          textAlign = TextAlign.Center,
          modifier = Modifier.semantics { heading() },
      )
      Text(
          stringResource(body),
          style = privacyTextStyle(14, 18f),
          color = Ink3,
          textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun OfflineFriendsBanner() {
  Row(
      Modifier.fillMaxWidth()
          .background(WarningSoft, RoundedCornerShape(14.dp))
          .padding(12.dp)
          .testTag(FriendsTags.OFFLINE),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
        painterResource(R.drawable.ic_friends_banner_wifi),
        null,
        Modifier.size(20.dp),
        tint = Color.Unspecified,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          stringResource(R.string.friends_offline),
          style = privacyTextStyle(13, 17f, FontWeight.Bold),
          color = Ink,
      )
      Text(
          stringResource(R.string.friends_offline_saved),
          style = privacyTextStyle(12, 15f),
          color = Ink3,
      )
    }
  }
}

@Composable
private fun RemoveFriendSheet(row: FriendRow, actions: FriendsActions) {
  val firstName =
      row.profile?.displayName?.let { it.trim().substringBefore(' ').takeIf(String::isNotEmpty) }
  CenteredConfirmationSheet(
      icon = R.drawable.ic_friends_remove,
      title =
          if (firstName != null) stringResource(R.string.friends_remove_named, firstName)
          else stringResource(R.string.friends_remove_unnamed),
      body = stringResource(R.string.friends_remove_body),
      confirm = stringResource(R.string.friends_remove),
      keep = R.string.friends_keep,
      confirmTag = FriendsTags.CONFIRM,
      keepTag = FriendsTags.KEEP,
      onConfirm = actions.onConfirm,
      onKeep = actions.onKeep,
  )
}

@Composable
private fun ActionNotice(
    notice: FriendsNotice,
    action: FriendListAction?,
    actions: FriendsActions,
) {
  val forRequest = action != null && action != FriendListAction.REMOVE
  val message =
      when (notice) {
        FriendsNotice.OFFLINE -> R.string.friends_offline
        FriendsNotice.FAILED ->
            if (forRequest) R.string.friends_request_error else R.string.friends_remove_error
        FriendsNotice.FRIENDSHIP_CHANGED ->
            if (forRequest) R.string.friends_request_changed
            else R.string.friends_friendship_changed
      }
  val canRetry = notice != FriendsNotice.FRIENDSHIP_CHANGED
  Snackbar(
      modifier =
          Modifier.testTag(FriendsTags.NOTICE).semantics {
            liveRegion = LiveRegionMode.Polite
            dismiss {
              actions.onDismissNotice()
              true
            }
          },
      shape = RoundedCornerShape(12.dp),
      containerColor = Ink,
      contentColor = Bg,
      action = {
        TextButton(
            onClick = if (canRetry) actions.onRetryAction else actions.onDismissNotice,
            modifier = Modifier.testTag(FriendsTags.NOTICE_ACTION),
        ) {
          Text(
              stringResource(
                  if (canRetry) R.string.friends_try_again else R.string.friends_dismiss
              ),
              style = privacyTextStyle(13, 17f, FontWeight.Bold),
              color = Bg,
          )
        }
      },
  ) {
    Text(stringResource(message), style = privacyTextStyle(13, 17f))
  }
}
