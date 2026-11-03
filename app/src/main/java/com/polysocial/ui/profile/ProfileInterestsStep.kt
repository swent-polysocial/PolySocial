// Contributors: Claude (the profile step's interests screen from the Figma "Interests · …" frames,
// #230; its save errors and Log out moved here from the details screen, where #142 added them).
package com.polysocial.ui.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.polysocial.R
import com.polysocial.model.user.INTERESTS
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.exact

/** Each interest code's chip label: every code in [INTERESTS] has one. */
internal val INTEREST_LABELS: Map<String, Int> =
    mapOf(
        "sports" to R.string.interest_sports,
        "outdoors" to R.string.interest_outdoors,
        "music" to R.string.interest_music,
        "parties" to R.string.interest_parties,
        "cinema" to R.string.interest_cinema,
        "games" to R.string.interest_games,
        "photography" to R.string.interest_photography,
        "cooking" to R.string.interest_cooking,
        "tech" to R.string.interest_tech,
        "startups" to R.string.interest_startups,
        "arts" to R.string.interest_arts,
        "volunteering" to R.string.interest_volunteering,
    )

/** Figma's 45 % fade of the choices while the profile is being saved. */
private const val SAVING_ALPHA = 0.45f

/**
 * Draws step 2 of the profile step, the interests (#230), for [state]; all measurements come from
 * the Figma "Interests · …" frames. Any choice, none included, can be saved with Finish. [onBack]
 * returns to the details; "Log out" on the generic save error calls [onLogOut].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileInterestsContent(
    state: ProfileSetupUiState,
    onBack: () -> Unit,
    onInterestToggle: (String) -> Unit,
    onFinish: () -> Unit,
    onLogOut: () -> Unit,
) {
  val saving = state.status == ProfileSetupStatus.Saving
  ProfileStepScaffold(
      pageTag = ProfileSetupTestTags.INTERESTS,
      top = {
        ProfileTopBar(onBack, backEnabled = !state.locked, step = 2)
        Spacer(Modifier.height(11.5.dp))
        StepHeading(R.string.profile_interests_title, R.string.profile_interests_subtitle)
        Spacer(Modifier.height(18.5.dp))
        Text(
            pluralStringResource(
                R.plurals.profile_interests_selected,
                state.interests.size,
                state.interests.size,
            ),
            style = MaterialTheme.typography.labelMedium.exact(),
            color = Ink2,
            modifier = Modifier.heightIn(min = 17.dp).testTag(ProfileSetupTestTags.INTERESTS_COUNT),
        )
        // Each chip's 48 dp touch target starts 4 dp above the drawn chip, which Figma places 9.5
        // dp under the count; rows of touch targets then put the chips 48 dp apart, as in Figma.
        Spacer(Modifier.height(5.5.dp))
        FlowRow(
            Modifier.alpha(if (saving) SAVING_ALPHA else 1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          INTERESTS.forEach { code ->
            InterestChip(
                code = code,
                selected = code in state.interests,
                enabled = !state.locked,
                onToggle = { onInterestToggle(code) },
            )
          }
        }
      },
      bottom = {
        when (state.status) {
          ProfileSetupStatus.CouldNotSave -> {
            ErrorMessage(R.string.profile_could_not_save)
            Spacer(Modifier.height(50.dp))
          }
          ProfileSetupStatus.CouldNotSaveGeneric -> {
            // Figma "Interests · couldn't save · generic": Log out 4 dp under the message, Finish
            // where it is on the offline frame.
            ErrorMessage(R.string.profile_could_not_save_generic)
            Spacer(Modifier.height(4.dp))
            LogOutAction(onLogOut)
            // The offline frame's 50 dp, minus the 4 dp gap and Log out's 15 dp line.
            Spacer(Modifier.height(31.dp))
          }
          else -> {}
        }
        StepButton(
            label = R.string.profile_finish,
            saving = saving,
            enabled = state.canFinish,
            tag = ProfileSetupTestTags.FINISH,
            onClick = onFinish,
        )
      },
  )
}

/**
 * One interest: dark with a check mark when [selected], white with a border otherwise. Figma draws
 * it 40 dp high; its touch target is 48 dp, 4 dp above and below, which is also Figma's gap between
 * two rows. Screen readers announce it as a checkbox.
 */
@Composable
private fun InterestChip(code: String, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
  val shape = RoundedCornerShape(20.dp)
  Box(
      Modifier.heightIn(min = 48.dp)
          .toggleable(
              value = selected,
              enabled = enabled,
              role = Role.Checkbox,
              onValueChange = { onToggle() },
          )
          .testTag(ProfileSetupTestTags.interest(code)),
      contentAlignment = Alignment.Center,
  ) {
    Row(
        Modifier.height(40.dp)
            .clip(shape)
            .background(if (selected) Ink else Bg)
            .then(if (selected) Modifier else Modifier.border(1.dp, Border, shape))
            // Figma: the check mark 14 dp from the left and the label 6 dp after it; unselected,
            // the label 15 dp from either side.
            .padding(start = if (selected) 14.dp else 15.dp, end = if (selected) 14.dp else 15.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (selected) Image(painterResource(R.drawable.ic_interest_check), contentDescription = null)
      Text(
          stringResource(INTEREST_LABELS.getValue(code)),
          style = MaterialTheme.typography.labelLarge.exact(),
          color = if (selected) Bg else Ink,
      )
    }
  }
}

@Composable
private fun ErrorMessage(@StringRes message: Int) {
  Row(
      Modifier.testTag(ProfileSetupTestTags.ERROR),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Image(
        painterResource(R.drawable.ic_error),
        contentDescription = null,
        modifier = Modifier.padding(top = 1.dp),
    )
    Text(
        stringResource(message),
        style = MaterialTheme.typography.bodySmall.exact(),
        color = AccentText,
        modifier = Modifier.heightIn(min = 30.dp),
    )
  }
}

/**
 * The generic error's action, in the error's red. It starts at the message's text: 20 dp is the
 * error icon's 14 dp plus the 6 dp gap after it. Figma draws a 15 dp line of text; the touch area
 * extends [LOG_OUT_TOUCH_EXTRA] above and below it (49 dp in all, near Android's 48 dp minimum)
 * without changing the layout, so nothing moves.
 */
@Composable
private fun LogOutAction(onClick: () -> Unit) {
  Text(
      stringResource(R.string.profile_log_out),
      style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold).exact(),
      color = AccentText,
      modifier =
          Modifier.padding(start = 20.dp)
              .layout { measurable, constraints ->
                // Measured with the extra touch area, but laid out at the text's own height.
                val extra = LOG_OUT_TOUCH_EXTRA.roundToPx()
                val placeable = measurable.measure(constraints.offset(vertical = 2 * extra))
                layout(placeable.width, placeable.height - 2 * extra) { placeable.place(0, -extra) }
              }
              .clickable(role = Role.Button, onClick = onClick)
              .padding(vertical = LOG_OUT_TOUCH_EXTRA)
              .testTag(ProfileSetupTestTags.LOG_OUT),
  )
}

/**
 * Touch area added above and below Log out: 17 + 15 + 17 = 49 dp. Tests measure the text with it.
 */
internal val LOG_OUT_TOUCH_EXTRA = 17.dp
