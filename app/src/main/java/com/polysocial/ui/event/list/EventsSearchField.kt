// Contributors: Claude (the Events tab's search field from the Figma "Events · date chips" frame,
// #115).
package com.polysocial.ui.event.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.Surface

/**
 * Figma's "Search events" field: a pill with a magnifier. The list follows every keystroke, so the
 * keyboard's search key only closes the keyboard.
 */
@Composable
fun EventsSearchField(
    search: String,
    onSearchChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            modifier =
                Modifier.fillMaxWidth()
                    .height(44.dp)
                    .background(Surface, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp),
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
