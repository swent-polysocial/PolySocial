// Contributors: Claude Opus 5.5 (moved the scanned student's card out of Scan a friend's code so
// the group member's profile shares it, #368); Konstantinos Perdikeas (directed, reviewed and owns
// it).
package com.polysocial.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.model.user.PublicProfile
import com.polysocial.resources.C
import com.polysocial.ui.group.initials
import com.polysocial.ui.profile.privacyTextStyle
import com.polysocial.ui.profile.profileDetails
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.SuccessSoft

/**
 * Another student's public profile as Figma "Scanned student · Public profile" draws it: their
 * initials, name, section and year, then their interests (none shown when they chose none). Shared
 * by Scan a friend's code (#359) and a group member's profile (#368), so both stay the same.
 */
@Composable
fun StudentProfileCard(profile: PublicProfile, modifier: Modifier = Modifier) {
  Column(modifier) {
    ProfileHeader(profile)
    Spacer(Modifier.height(30.dp))
    ProfileInterests(profile)
  }
}

@Composable
private fun ProfileHeader(profile: PublicProfile) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
        Modifier.size(60.dp).background(SuccessSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          initials(profile.displayName),
          color = Success,
          style = privacyTextStyle(20, 16.4f, FontWeight.Bold),
      )
    }
    Spacer(Modifier.width(14.dp))
    Column {
      Text(
          profile.displayName,
          color = Ink,
          style = privacyTextStyle(22, 27.7f, FontWeight.ExtraBold),
          modifier = Modifier.testTag(C.Tag.student_profile_name).semantics { heading() },
      )
      Text(
          profileDetails(profile.section, profile.year),
          color = Ink2,
          style = privacyTextStyle(14, 18f),
          modifier = Modifier.testTag(C.Tag.student_profile_details),
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileInterests(profile: PublicProfile) {
  val interests = profile.interests.mapNotNull(::interestLabel)
  if (interests.isEmpty()) return
  Text(
      stringResource(R.string.scan_friend_interests),
      color = Ink,
      style = privacyTextStyle(16, 20f, FontWeight.Bold),
  )
  Spacer(Modifier.height(8.dp))
  FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    for (interest in interests) {
      Text(
          stringResource(interest),
          color = Ink,
          style = privacyTextStyle(13, 16.4f, FontWeight.SemiBold),
          modifier =
              Modifier.heightIn(min = 32.dp)
                  .border(1.dp, Border, RoundedCornerShape(16.dp))
                  .padding(horizontal = 13.dp, vertical = 8.dp)
                  .testTag(C.Tag.student_profile_interest),
      )
    }
  }
}
