// Contributors: Claude (the Events tab's filter chips from the Figma "Events · date chips" and
// "Events · active filters" frames, a picked day, and the chip shared with the Filters sheet,
// #115; the row without *All* for the Map tab, #116).
package com.polysocial.ui.event.list

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * tapping it clears them all, the search included, as decided in #113. The Map's row has no *All*
 * ([showAll] false, Figma "Map"): its "No matching events" card clears them instead. Over the map,
 * the chips that are off are filled with [offFill] so they stay readable on the tiles.
 */
@Composable
fun EventsChipRow(
    selection: EventSelection,
    onSelectDate: (DateChoice) -> Unit,
    onToggleCategory: (EventCategory) -> Unit,
    onClearFilters: () -> Unit,
    onSelectDistance: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    showAll: Boolean = true,
    offFill: Color = Color.Transparent,
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
  // Likewise for a distance, as "≤ 2 km".
  val distance =
      selection.maxDistanceMeters?.let { meters ->
        FilterChip(
            DISTANCE,
            stringResource(R.string.events_filter_within, distanceLabel(meters)),
            isActive = true,
        ) {
          onSelectDistance(null)
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
  val chips = listOfNotNull(pickedDay, distance) + dateChips + categoryChips
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
        listOfNotNull(all.takeIf { showAll }) +
            chips.filter { it.isActive } +
            chips.filterNot { it.isActive },
        { it.key },
    ) { chip ->
      EventsChip(
          label = chip.label,
          selected = chip.isActive,
          onClick = chip.onClick,
          tag = EventsTags.chip(chip.key),
          removable = chip.isActive && chip.key != ALL,
          offFill = offFill,
      )
    }
  }
}

/**
 * A 36 dp Figma chip inside a 48 dp touch target: dark when [selected], outlined otherwise. A
 * [removable] chip ends with an ×; [leadingIcon] is drawn before the label ("Pick a date"). A
 * disabled chip is faded to 40 %, as in Figma.
 */
@Composable
internal fun EventsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    removable: Boolean = false,
    role: Role = Role.Checkbox,
    @DrawableRes leadingIcon: Int? = null,
    enabled: Boolean = true,
    offFill: Color = Color.Transparent,
) {
  val shape = RoundedCornerShape(18.dp)
  val colors =
      if (selected) Modifier.background(Ink, shape)
      else Modifier.background(offFill, shape).border(1.dp, Border, shape)
  val content = if (selected) Bg else Ink
  Row(
      modifier =
          Modifier.heightIn(min = 48.dp)
              .alpha(if (enabled) 1f else DISABLED_ALPHA)
              .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
              .testTag(tag),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
        modifier =
            Modifier.height(36.dp)
                .clip(shape)
                .then(colors)
                .padding(
                    start =
                        when {
                          removable -> 16.dp
                          leadingIcon != null -> 13.dp
                          else -> 15.dp
                        },
                    end = if (removable) 12.dp else 15.dp,
                ),
        horizontalArrangement = Arrangement.spacedBy(if (leadingIcon != null) 6.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (leadingIcon != null) {
        Icon(
            painterResource(leadingIcon),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
      }
      Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
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
private const val DISABLED_ALPHA = 0.4f
private const val PICKED_DAY = "DAY"
private const val DISTANCE = "DISTANCE"

/** Figma's "Sat 4 Oct", as in the list's day headings. */
private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

/** The categories in Figma's chip order, which isn't the enum's. */
internal val CHIP_CATEGORIES =
    listOf(
        EventCategory.PARTY,
        EventCategory.SPORTS,
        EventCategory.CULTURE,
        EventCategory.STUDY,
        EventCategory.OTHER,
    )
