// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #450 under Lucas Pinto Ricardo's supervision and
// approval).
package com.polysocial.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.common.StateButton
import com.polysocial.ui.group.initials
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.InfoSoft
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Surface

/** The independently loaded friends section of a student's public profile. */
sealed interface ProfileFriendsUiState {
  data object Loading : ProfileFriendsUiState

  data class Listed(val names: List<String?>, val offline: Boolean = false) : ProfileFriendsUiState

  data object Private : ProfileFriendsUiState

  data object Offline : ProfileFriendsUiState

  data object Error : ProfileFriendsUiState
}

@Composable
internal fun ProfileFriendsSection(
    state: ProfileFriendsUiState,
    ownerName: String,
    onRetry: () -> Unit,
) {
  Column(
      Modifier.fillMaxWidth().padding(top = 24.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    HorizontalDivider(color = Border)
    when (state) {
      is ProfileFriendsUiState.Listed ->
          FriendsContent(stringResource(R.string.profile_friends_count, state.names.size)) {
            if (state.offline) FriendsStatus(R.string.profile_friends_offline)
            state.names.forEach { FriendPreview(it) }
          }
      ProfileFriendsUiState.Private -> PrivateFriendsNote(ownerName)
      ProfileFriendsUiState.Offline ->
          FriendsContent(stringResource(R.string.profile_friends_unknown_count)) {
            FriendsStatus(R.string.profile_friends_offline)
          }
      ProfileFriendsUiState.Loading ->
          FriendsContent(stringResource(R.string.profile_friends_title)) {
            FriendsStatus(R.string.profile_friends_loading)
          }
      ProfileFriendsUiState.Error ->
          FriendsContent(stringResource(R.string.profile_friends_title)) {
            FriendsStatus(R.string.profile_friends_error)
            StateButton(
                stringResource(R.string.retry),
                onRetry,
                Modifier.testTag(C.Tag.profile_friends_retry),
            )
          }
    }
  }
}

@Composable
private fun FriendsContent(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
        title,
        Modifier.testTag(C.Tag.profile_friends_count).semantics { heading() },
        color = Ink,
        style = privacyTextStyle(16, 20f, FontWeight.Bold),
    )
    content()
  }
}

@Composable
private fun FriendsStatus(message: Int) {
  Text(
      stringResource(message),
      Modifier.testTag(C.Tag.profile_friends_status),
      color = Ink2,
      style = privacyTextStyle(13, 16f),
  )
}

@Composable
private fun FriendPreview(name: String?) {
  Row(
      Modifier.fillMaxWidth().testTag(C.Tag.profile_friend_row),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        Modifier.size(40.dp).background(InfoSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          initials(name.orEmpty()),
          color = Ink,
          style = privacyTextStyle(12, 15f, FontWeight.Bold),
      )
    }
    Text(
        name ?: stringResource(R.string.group_profile_unavailable),
        color = Ink,
        style = privacyTextStyle(15, 19f, FontWeight.Bold),
    )
  }
}

@Composable
private fun PrivateFriendsNote(ownerName: String) {
  Row(
      Modifier.fillMaxWidth()
          .background(Surface, RoundedCornerShape(14.dp))
          .testTag(C.Tag.profile_friends_private)
          .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
      Icon(
          painterResource(R.drawable.ic_profile_friends_lock),
          contentDescription = null,
          tint = Ink,
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
          stringResource(R.string.profile_friends_private_title),
          color = Ink,
          style = privacyTextStyle(15, 19f, FontWeight.Bold),
      )
      Text(
          stringResource(R.string.profile_friends_private_body, ownerName),
          color = Ink2,
          style = privacyTextStyle(13, 16f),
      )
    }
  }
}
