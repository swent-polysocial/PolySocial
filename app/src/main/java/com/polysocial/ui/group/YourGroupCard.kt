// Contributors: Claude (Opus 5.5, Anthropic) drew the group card on the event detail from #227's
// Figma frame "Event detail · in a group", #233.
package com.polysocial.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.ui.event.detail.YourGroup
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2

/**
 * The student's group on the event detail (Figma "Event detail · in a group", #233): the members'
 * avatars, the student's last, "Your group · 3 of 4", and who is in it with how many places are
 * left. A member whose public profile couldn't be read shows "?" and is left out of the names.
 * Tapping it calls [onOpen].
 */
@Composable
fun YourGroupCard(group: YourGroup, onOpen: () -> Unit, modifier: Modifier = Modifier) {
  val others = group.members.filter { it.uid != group.myUid }
  val left = (group.capacity - group.members.size).coerceAtLeast(0)
  Column(
      modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .background(Bg)
          .border(1.dp, Border, RoundedCornerShape(16.dp))
          .clickable(role = Role.Button, onClick = onOpen)
          .padding(start = 13.dp, top = 13.dp, end = 15.dp, bottom = 14.dp),
      verticalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        others.forEachIndexed { index, member ->
          MemberAvatar(
              member.profile?.displayName?.let(::initials) ?: "?",
              OTHERS_COLORS[index % OTHERS_COLORS.size],
          )
        }
        // Figma's blue "You", the first avatar color.
        MemberAvatar(stringResource(R.string.your_group_you), 0)
      }
      Text(
          stringResource(R.string.your_group_title, group.members.size, group.capacity),
          style = MaterialTheme.typography.titleSmall,
          color = Ink,
          modifier = Modifier.weight(1f),
      )
      Icon(
          painterResource(R.drawable.ic_chevron_right),
          contentDescription = null,
          tint = Ink,
          modifier = Modifier.size(20.dp),
      )
    }
    val names = others.mapNotNull { it.profile?.displayName?.split(' ')?.firstOrNull() }
    val who =
        when {
          others.isEmpty() -> stringResource(R.string.your_group_only_you)
          names.isEmpty() -> null
          else -> stringResource(R.string.your_group_members, names.joinToString(", "))
        }
    val places =
        if (left == 0) stringResource(R.string.your_group_full)
        else pluralStringResource(R.plurals.your_group_places_left, left, left)
    Text(
        listOfNotNull(who, places).joinToString(" "),
        style = MaterialTheme.typography.bodyMedium,
        color = Ink2,
    )
  }
}

/** The other members' avatar colors, as in Figma: red, then orange, then green. */
private val OTHERS_COLORS = listOf(3, 2, 1)
