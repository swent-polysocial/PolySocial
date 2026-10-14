// Contributors: Claude (the Events tab's search field from the Figma "Events · date chips" frame,
// and its Filters button from "Events · active filters", #115).
package com.polysocial.ui.event.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Surface

/**
 * Figma's "Search events" field: a pill with a magnifier, and the Filters button at its end, which
 * shows how many filters are on ([activeFilterCount]). The list follows every keystroke, so the
 * keyboard's search key only closes the keyboard.
 */
@Composable
fun EventsSearchField(
    search: String,
    onSearchChange: (String) -> Unit,
    activeFilterCount: Int,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
  // The button sits on top of the field rather than inside it, so tapping it doesn't focus the
  // field and open the keyboard.
  Box(
      modifier =
          modifier.fillMaxWidth().height(44.dp).background(Surface, RoundedCornerShape(22.dp)),
      contentAlignment = Alignment.CenterEnd,
  ) {
    SearchText(search, onSearchChange, Modifier.padding(end = 44.dp))
    FiltersButton(activeFilterCount, onOpenFilters, Modifier.padding(end = 4.dp))
  }
}

@Composable
private fun SearchText(search: String, onSearchChange: (String) -> Unit, modifier: Modifier) {
  val focusManager = LocalFocusManager.current
  val placeholder = stringResource(R.string.events_search_placeholder)
  val textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp, color = Ink)
  BasicTextField(
      value = search,
      onValueChange = onSearchChange,
      singleLine = true,
      textStyle = textStyle,
      cursorBrush = SolidColor(Ink),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
      modifier = modifier.fillMaxWidth().testTag(EventsTags.SEARCH),
      decorationBox = { innerTextField ->
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
              painterResource(R.drawable.ic_search),
              contentDescription = null,
              tint = Ink2,
              modifier = Modifier.size(18.dp),
          )
          Box(Modifier.weight(1f)) {
            if (search.isEmpty()) Text(placeholder, style = textStyle, color = Ink3)
            innerTextField()
          }
        }
      },
  )
}

/**
 * Figma's 36 dp sliders button, with a dark badge counting the filters that are on. Compose extends
 * its touch area to 48 dp.
 */
@Composable
private fun FiltersButton(activeFilterCount: Int, onClick: () -> Unit, modifier: Modifier) {
  val description =
      if (activeFilterCount == 0) stringResource(R.string.events_filters)
      else
          pluralStringResource(
              R.plurals.events_filters_active,
              activeFilterCount,
              activeFilterCount,
          )
  Box(
      modifier =
          modifier
              .size(36.dp)
              .background(Bg, CircleShape)
              .clickable(role = Role.Button, onClick = onClick)
              .semantics { contentDescription = description }
              .testTag(EventsTags.FILTERS_BUTTON),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        painterResource(R.drawable.ic_filters),
        contentDescription = null,
        tint = Ink,
        modifier = Modifier.size(18.dp),
    )
    if (activeFilterCount > 0) {
      Box(
          modifier =
              Modifier.align(Alignment.TopStart)
                  .offset(x = 22.dp, y = (-6).dp)
                  .size(20.dp)
                  .background(Ink, CircleShape)
                  .border(2.dp, Bg, CircleShape)
                  // The button's description already says how many filters are on.
                  .clearAndSetSemantics {},
          contentAlignment = Alignment.Center,
      ) {
        Text(
            text = activeFilterCount.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 11.sp),
            fontWeight = FontWeight.Bold,
            color = Bg,
        )
      }
    }
  }
}
