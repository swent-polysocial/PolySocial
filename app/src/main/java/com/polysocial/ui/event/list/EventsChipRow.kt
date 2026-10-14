// Contributors: Claude (the Events tab's filter chips from the Figma "Events · date chips" and
// "Events · active filters" frames, and a picked day, #115).
package com.polysocial.ui.event.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.model.event.EventCategory
import com.polysocial.ui.event.style
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import java.time.format.DateTimeFormatter

/** One chip of the row: what it filters by, its label and whether that filter is on. */
private data class FilterChip(
    val key: String,
    val label: String,
    val isActive: Boolean,
    val onClick: () -> Unit,
)

/**
 * Figma's chip row under the search: *All*, then the filters that are on (dark, with an ×, tapping
 * one removes it), then the quick filters that are off. *All* is on when no chip filter is, and
 * tapping it clears them all, the search included, as decided in #113.
 */
@Composable
fun EventsChipRow(
    selection: EventSelection,
    onSelectDate: (DateChoice) -> Unit,
    onToggleCategory: (EventCategory) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val quickDates =
      listOf(
          Triple("TODAY", DateChoice.Today, stringResource(R.string.events_filter_today)),
          Triple(
              "THIS_WEEK",
              DateChoice.ThisWeek,
              stringResource(R.string.events_filter_this_week),
          ),
      )
  val dateChips = quickDates.map { (key, date, label) ->
    val isActive = selection.date == date
    FilterChip(key, label, isActive) { onSelectDate(if (isActive) DateChoice.Any else date) }
  }
  // A day picked in the Filters sheet has no quick chip; it shows only while it is on.
  val pickedDay =
      (selection.date as? DateChoice.Day)?.let { day ->
        FilterChip(PICKED_DAY, day.date.format(dayFormat), isActive = true) {
          onSelectDate(DateChoice.Any)
        }
      }
  val categoryChips = CHIP_CATEGORIES.map { category ->
    FilterChip(
        category.name,
        stringResource(category.style.label),
        category in selection.categories,
    ) {
      onToggleCategory(category)
    }
  }
  val chips = listOfNotNull(pickedDay) + dateChips + categoryChips
  val all =
      FilterChip(
          ALL,
          stringResource(R.string.events_filter_all),
          chips.none { it.isActive },
          onClearFilters,
      )
  LazyRow(
      modifier = modifier.testTag(EventsTags.CHIPS),
      contentPadding = PaddingValues(horizontal = 20.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(
        listOf(all) + chips.filter { it.isActive } + chips.filterNot { it.isActive },
        { it.key },
    ) { chip ->
      Chip(chip, removable = chip.isActive && chip.key != ALL)
    }
  }
}

/** A 36 dp Figma chip inside a 48 dp touch target. */
@Composable
private fun Chip(chip: FilterChip, removable: Boolean) {
  val shape = RoundedCornerShape(18.dp)
  val colors =
      if (chip.isActive) Modifier.background(Ink, shape) else Modifier.border(1.dp, Border, shape)
  Row(
      modifier =
          Modifier.heightIn(min = 48.dp)
              .selectable(selected = chip.isActive, role = Role.Checkbox, onClick = chip.onClick)
              .testTag(EventsTags.chip(chip.key)),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
        modifier =
            Modifier.height(36.dp)
                .clip(shape)
                .then(colors)
                .padding(
                    start = if (removable) 16.dp else 15.dp,
                    end = if (removable) 12.dp else 15.dp,
                ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          text = chip.label,
          style = MaterialTheme.typography.labelLarge,
          color = if (chip.isActive) Bg else Ink,
      )
      if (removable) {
        // The chip's selected state already says the filter is on, so the × is decorative.
        Icon(
            painterResource(R.drawable.ic_close),
            contentDescription = null,
            tint = Bg,
            modifier = Modifier.size(14.dp),
        )
      }
    }
  }
}

private const val ALL = "ALL"
private const val PICKED_DAY = "DAY"

/** Figma's "Sat 4 Oct", as in the list's day headings. */
private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

/** The categories in Figma's chip order, which isn't the enum's. */
private val CHIP_CATEGORIES =
    listOf(
        EventCategory.PARTY,
        EventCategory.SPORTS,
        EventCategory.CULTURE,
        EventCategory.STUDY,
        EventCategory.OTHER,
    )
