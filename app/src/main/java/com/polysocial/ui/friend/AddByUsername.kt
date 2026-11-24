// Contributors: Claude (Opus 5.5, Anthropic) (drafted Add by @username from #480's Figma frames,
// #452); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.friend

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.model.user.normalizeUsername
import com.polysocial.ui.profile.privacyTextStyle
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Surface

object AddByUsernameTags {
  const val SECTION = "add_by_username"
  const val FIELD = "add_by_username_field"
  const val LOOK_UP = "add_by_username_look_up"
  const val MESSAGE = "add_by_username_message"
}

/** What the student can do in Add by @username. */
internal data class AddByUsernameActions(
    val onInput: (String) -> Unit,
    val onLookUp: () -> Unit,
)

/**
 * Figma section 18's "Add by @username" (#480): the field with its fixed `@`, Look up (Try again
 * after a failed lookup), the format hint, and why the last lookup opened no card. Offline, Look up
 * is disabled: the Friends screen's banner says why.
 */
@Composable
internal fun AddByUsername(
    state: AddByUsernameUiState,
    offline: Boolean,
    actions: AddByUsernameActions,
    modifier: Modifier = Modifier,
) {
  val canLookUp = !offline && !state.looking && normalizeUsername(state.input).isNotEmpty()
  Column(modifier.testTag(AddByUsernameTags.SECTION)) {
    Text(
        stringResource(R.string.add_by_username_label),
        style = privacyTextStyle(13, 16f, FontWeight.SemiBold),
        color = Ink,
    )
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      UsernameField(
          state.input,
          actions,
          onSearch = { if (canLookUp) actions.onLookUp() },
          Modifier.weight(1f),
      )
      LookUpButton(
          if (state.problem == UsernameLookupProblem.FAILED) R.string.add_by_username_try_again
          else R.string.add_by_username_look_up,
          enabled = canLookUp,
          loading = state.looking,
          onClick = actions.onLookUp,
      )
    }
    Text(
        stringResource(R.string.add_by_username_hint),
        style = privacyTextStyle(12, 15f),
        color = Ink3,
        modifier = Modifier.padding(top = 6.dp),
    )
    state.problem?.let { LookupMessage(it, Modifier.padding(top = 16.dp)) }
  }
}

@Composable
private fun UsernameField(
    input: String,
    actions: AddByUsernameActions,
    onSearch: () -> Unit,
    modifier: Modifier,
) {
  val description = stringResource(R.string.add_by_username_field)
  BasicTextField(
      value = input,
      onValueChange = actions.onInput,
      singleLine = true,
      textStyle = privacyTextStyle(14, 18f).copy(color = Ink),
      keyboardOptions =
          KeyboardOptions(
              capitalization = KeyboardCapitalization.None,
              autoCorrectEnabled = false,
              keyboardType = KeyboardType.Ascii,
              imeAction = ImeAction.Search,
          ),
      keyboardActions = KeyboardActions(onSearch = { onSearch() }),
      modifier =
          modifier
              .height(44.dp)
              .semantics { contentDescription = description }
              .testTag(AddByUsernameTags.FIELD),
      decorationBox = { inner ->
        Row(
            Modifier.fillMaxWidth()
                .height(44.dp)
                .border(BorderStroke(1.dp, Border), RoundedCornerShape(12.dp))
                .background(Bg, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
              stringResource(R.string.add_by_username_prefix),
              style = privacyTextStyle(14, 18f),
              color = Ink3,
          )
          Box(Modifier.padding(start = 2.dp).weight(1f)) { inner() }
        }
      },
  )
}

/** Figma's 92 dp dark pill next to the field; greyed out while it can't look anything up. */
@Composable
private fun LookUpButton(
    @StringRes label: Int,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
  val greyed = !enabled && !loading
  Row(
      Modifier.width(92.dp)
          .height(44.dp)
          .clip(RoundedCornerShape(22.dp))
          .background(if (greyed) Disabled else Ink)
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .testTag(AddByUsernameTags.LOOK_UP),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (loading) {
      CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          color = Bg,
          trackColor = Bg.copy(alpha = 0.3f),
          strokeWidth = 2.dp,
      )
    } else {
      Text(
          stringResource(label),
          style = privacyTextStyle(14, 18f, FontWeight.SemiBold),
          color = if (greyed) Ink3 else Bg,
      )
    }
  }
}

/** Figma's grey box under the hint: frames 06 (no student), 07 (your own name) and 09 (failed). */
@Composable
private fun LookupMessage(problem: UsernameLookupProblem, modifier: Modifier) {
  val (title, body) =
      when (problem) {
        UsernameLookupProblem.NOT_FOUND ->
            R.string.add_by_username_not_found_title to R.string.add_by_username_not_found_body
        UsernameLookupProblem.OWN_NAME ->
            R.string.add_by_username_own_title to R.string.add_by_username_own_body
        UsernameLookupProblem.FAILED ->
            R.string.add_by_username_failed_title to R.string.add_by_username_failed_body
      }
  Column(
      modifier
          .fillMaxWidth()
          .background(Surface, RoundedCornerShape(12.dp))
          .padding(horizontal = 14.dp, vertical = 12.dp)
          .testTag(AddByUsernameTags.MESSAGE),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
        stringResource(title),
        style = privacyTextStyle(14, 18f, FontWeight.Bold),
        color = Ink,
    )
    Text(stringResource(body), style = privacyTextStyle(13, 16f), color = Ink2)
  }
}
