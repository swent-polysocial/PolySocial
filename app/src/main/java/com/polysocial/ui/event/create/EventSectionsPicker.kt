// Contributors: Claude (Sonnet 5.5, Anthropic) (the sections picker of Create and Edit event, from
// the proposal in docs/mockups/369-event-sections, #369).
package com.polysocial.ui.event.create

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.model.user.SECTIONS
import com.polysocial.ui.common.choiceChipSurface
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2

/** Test tags of the sections picker. */
object EventSectionsTags {
  const val PICKER = "create_event_sections"
  const val CAPTION = "create_event_sections_caption"

  fun chip(code: String) = "create_event_section_$code"
}

/**
 * Who can see a public event, by EPFL section (#369): one chip per section, none picked meaning
 * every section. The caption says which it is, so the empty choice is never ambiguous. Shown for a
 * student's public event only: a private event or an association's has no restriction.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventSectionsPicker(
    selected: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(modifier.testTag(EventSectionsTags.PICKER)) {
    Text(
        text =
            if (selected.isEmpty()) stringResource(R.string.create_event_sections_all)
            else
                stringResource(
                    R.string.create_event_sections_only,
                    SECTIONS.filter { it in selected }.joinToString(", "),
                ),
        style = MaterialTheme.typography.bodyMedium,
        color = Ink2,
        modifier = Modifier.testTag(EventSectionsTags.CAPTION),
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SECTIONS.forEach { code -> SectionChip(code, code in selected, enabled) { onToggle(code) } }
    }
  }
}

/**
 * One section: dark with a check mark when [selected], white with a border otherwise (the shared
 * [choiceChipSurface], like the profile step's interest chips). Its touch target is 48 dp high. It
 * is `toggleable`, so screen readers announce a checkbox and whether it is checked.
 */
@Composable
private fun SectionChip(code: String, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
  val shape = RoundedCornerShape(20.dp)
  Box(
      Modifier.heightIn(min = 48.dp)
          .toggleable(
              value = selected,
              enabled = enabled,
              role = Role.Checkbox,
              onValueChange = { onToggle() },
          )
          .testTag(EventSectionsTags.chip(code)),
      contentAlignment = Alignment.Center,
  ) {
    Row(
        Modifier.height(40.dp)
            .choiceChipSurface(selected, shape)
            .padding(start = if (selected) 14.dp else 15.dp, end = if (selected) 14.dp else 15.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (selected) {
        Image(painterResource(R.drawable.ic_interest_check), contentDescription = null)
      }
      Text(
          text = code,
          style = MaterialTheme.typography.labelLarge,
          color = if (selected) Bg else Ink,
      )
    }
  }
}
