// Contributors: Claude Opus 5.5 (drafted the scanned student's card from Figma section 15 and its
// #359 states, in ScanFriendScreen.kt); Claude (Opus 5.5, Anthropic) (moved it here to share it,
// #452); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.friend

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.common.StudentProfileCard
import com.polysocial.ui.profile.privacyTextStyle
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Ink2

/**
 * A student's card (Figma "Scanned student · Public profile" and its #359 button states), shared by
 * Scan a friend's code and Add by username (#452): their public profile, then the note and the
 * friend button of [FriendCardController]. Close and the button call [onClose] and [onButton].
 */
@Composable
internal fun FriendCardScreen(card: FriendCard, onClose: () -> Unit, onButton: () -> Unit) {
  Column(Modifier.fillMaxSize().background(Bg).testTag(C.Tag.scan_friend_card)) {
    ScanHeader(R.string.scan_friend_profile_title, onClose, dark = false)
    Column(
        Modifier.weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
      Spacer(Modifier.height(16.dp))
      StudentProfileCard(card.profile)
    }
    CardAction(card, onButton)
  }
}

/** The note above the button (the friendship, or why the last tap failed) and the button. */
@Composable
private fun CardAction(card: FriendCard, onButton: () -> Unit) {
  val firstName = card.profile.displayName.trim().substringBefore(' ')
  val note =
      when {
        card.error == FriendActionError.OFFLINE -> stringResource(R.string.scan_friend_offline)
        card.error == FriendActionError.FAILED -> stringResource(R.string.scan_friend_failed)
        card.button == FriendButton.SENT ->
            stringResource(R.string.scan_friend_sent_note, firstName)
        card.button == FriendButton.ACCEPT ->
            stringResource(R.string.scan_friend_accept_note, firstName)
        card.button == FriendButton.FRIENDS ->
            stringResource(R.string.scan_friend_friends_note, firstName)
        else -> null
      }
  Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
    if (note != null) {
      Text(
          note,
          color = if (card.error != null) AccentText else Ink2,
          textAlign = TextAlign.Center,
          style = privacyTextStyle(13, 16.4f),
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.scan_friend_note),
      )
      Spacer(Modifier.height(18.dp))
    }
    PrimaryButton(
        stringResource(card.button.label),
        enabled = card.button.actionable && !card.busy,
        loading = card.busy,
        tag = C.Tag.scan_friend_button,
        onClick = onButton,
    )
  }
}

private val FriendButton.label: Int
  get() =
      when (this) {
        FriendButton.ADD -> R.string.scan_friend_add
        FriendButton.SENT -> R.string.scan_friend_sent
        FriendButton.ACCEPT -> R.string.scan_friend_accept
        FriendButton.FRIENDS -> R.string.scan_friend_friends
      }

private val FriendButton.actionable: Boolean
  get() = this == FriendButton.ADD || this == FriendButton.ACCEPT
