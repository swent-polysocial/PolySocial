// Contributors: Claude (the Events tab's Filters sheet from the Figma "Events · filters" and
// "Events · filters, pick a date" frames, #115).
package com.polysocial.ui.event.list

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polysocial.R
import com.polysocial.model.event.EventCategory
import com.polysocial.ui.event.style
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Figma's Filters sheet: the date, the distance and the categories. Every tap updates the list
 * right away, like the chip row; *Clear* clears the filters (not the search), and "Show N events"
 * only closes the sheet. *Pick a date* opens a strip of the next 14 days from [today].
 *
 * @param shownCount how many events the current search and filters keep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsFiltersSheet(
    selection: EventSelection,
    today: LocalDate,
    shownCount: Int,
    onSelectDate: (DateChoice) -> Unit,
    onSelectDistance: (Int?) -> Unit,
    onToggleCategory: (EventCategory) -> Unit,
    onClearFilters: () -> Unit,
    onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
      containerColor = Bg,
      scrimColor = Ink.copy(alpha = 0.45f),
      dragHandle = { Handle() },
      modifier = Modifier.testTag(EventsTags.FILTERS_SHEET),
  ) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
      Spacer(Modifier.height(14.dp))
      Text(
          text = stringResource(R.string.events_filters),
          style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 25.sp),
          fontWeight = FontWeight.ExtraBold,
          color = Ink,
          modifier = Modifier.padding(horizontal = 20.dp),
      )
      Spacer(Modifier.height(20.dp))
      DateSection(selection.date, today, onSelectDate)
      Spacer(Modifier.height(16.dp))
      DistanceSection(selection.maxDistanceMeters, onSelectDistance)
      Spacer(Modifier.height(16.dp))
      CategorySection(selection.categories, onToggleCategory)
      Spacer(Modifier.height(18.dp))
      Buttons(
          shownCount = shownCount,
          onClear = onClearFilters,
          onShow = {
            scope
                .launch { sheetState.hide() }
                .invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
          },
      )
    }
  }
}

@Composable
private fun Handle() {
  Box(
      Modifier.padding(top = 10.dp)
          .size(width = 40.dp, height = 4.dp)
          .background(Border, RoundedCornerShape(2.dp))
  )
}

/**
 * A section's title and its chips. The chips' 48 dp touch targets add 6 dp around them, so the gap
 * under the title is 6 dp smaller than Figma's 10 dp, and the rows are 12 dp apart instead of 8.
 */
@Composable
private fun Section(title: String, chips: @Composable () -> Unit) {
  Column(Modifier.padding(horizontal = 20.dp)) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Ink2,
    )
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
  }
}

@Composable
private fun DateSection(date: DateChoice, today: LocalDate, onSelectDate: (DateChoice) -> Unit) {
  // The strip stays open while picking, even before a day is picked.
  var picking by rememberSaveable { mutableStateOf(date is DateChoice.Day) }
  fun select(choice: DateChoice) {
    picking = false
    onSelectDate(choice)
  }
  Section(stringResource(R.string.events_filter_date)) {
    EventsChip(
        label = stringResource(R.string.events_filter_any),
        selected = date == DateChoice.Any && !picking,
        onClick = { select(DateChoice.Any) },
        tag = EventsTags.sheetChip("DATE_ANY"),
        role = Role.RadioButton,
    )
    EventsChip(
        label = stringResource(R.string.events_filter_today),
        selected = date == DateChoice.Today,
        onClick = { select(DateChoice.Today) },
        tag = EventsTags.sheetChip("TODAY"),
        role = Role.RadioButton,
    )
    EventsChip(
        label = stringResource(R.string.events_filter_this_week),
        selected = date == DateChoice.ThisWeek,
        onClick = { select(DateChoice.ThisWeek) },
        tag = EventsTags.sheetChip("THIS_WEEK"),
        role = Role.RadioButton,
    )
    EventsChip(
        label = stringResource(R.string.events_filter_pick_date),
        selected = picking || date is DateChoice.Day,
        onClick = {
          // Tapping it again closes the strip and drops the picked day.
          if (picking || date is DateChoice.Day) select(DateChoice.Any) else picking = true
        },
        tag = EventsTags.sheetChip("PICK_DATE"),
        role = Role.RadioButton,
        leadingIcon = R.drawable.ic_tab_events,
    )
  }
  if (picking || date is DateChoice.Day) {
    Spacer(Modifier.height(6.dp))
    DayStrip((date as? DateChoice.Day)?.date, today) { day ->
      onSelectDate(
          if ((date as? DateChoice.Day)?.date == day) DateChoice.Any else DateChoice.Day(day)
      )
    }
  }
}

/** Figma's "Next 14 days": one 48 × 60 dp tile per day, from [today]. */
@Composable
private fun DayStrip(picked: LocalDate?, today: LocalDate, onPick: (LocalDate) -> Unit) {
  LazyRow(
      modifier = Modifier.fillMaxWidth().testTag(EventsTags.DAY_STRIP),
      contentPadding = PaddingValues(horizontal = 20.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items((0L until DAYS_TO_PICK).map { today.plusDays(it) }, { it.toEpochDay() }) { day ->
      DayTile(day, selected = day == picked) { onPick(day) }
    }
  }
}

@Composable
private fun DayTile(day: LocalDate, selected: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(14.dp)
  val colors =
      if (selected) Modifier.background(Ink, shape) else Modifier.border(1.dp, Border, shape)
  Column(
      modifier =
          Modifier.size(width = 48.dp, height = 60.dp)
              .clip(shape)
              .then(colors)
              .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
              .testTag(EventsTags.day(day)),
      verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
        text = day.format(weekdayFormat),
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) Bg else Ink2,
    )
    Text(
        text = day.dayOfMonth.toString(),
        style = MaterialTheme.typography.titleMedium.copy(lineHeight = 21.sp),
        color = if (selected) Bg else Ink,
    )
  }
}

@Composable
private fun DistanceSection(maxDistanceMeters: Int?, onSelectDistance: (Int?) -> Unit) {
  Section(stringResource(R.string.events_filter_distance)) {
    EventsChip(
        label = stringResource(R.string.events_filter_any),
        selected = maxDistanceMeters == null,
        onClick = { onSelectDistance(null) },
        tag = EventsTags.sheetChip("DISTANCE_ANY"),
        role = Role.RadioButton,
    )
    DISTANCE_CHOICES_METERS.forEach { meters ->
      EventsChip(
          label = distanceLabel(meters),
          selected = maxDistanceMeters == meters,
          onClick = { onSelectDistance(meters) },
          tag = EventsTags.sheetChip("DISTANCE_$meters"),
          role = Role.RadioButton,
      )
    }
  }
}

/** "500 m" under a kilometer, "2 km" from one. */
@Composable
internal fun distanceLabel(meters: Int): String =
    if (meters < 1_000) stringResource(R.string.events_distance_meters, meters)
    else stringResource(R.string.events_distance_km, meters / 1_000)

@Composable
private fun CategorySection(
    categories: Set<EventCategory>,
    onToggleCategory: (EventCategory) -> Unit,
) {
  Section(stringResource(R.string.events_filter_category)) {
    CHIP_CATEGORIES.forEach { category ->
      EventsChip(
          label = stringResource(category.style.label),
          selected = category in categories,
          onClick = { onToggleCategory(category) },
          tag = EventsTags.sheetChip(category.name),
      )
    }
  }
}

/** *Clear* and "Show N events", 52 dp high and sized in Figma's 130.5 : 179.5 ratio. */
@Composable
private fun Buttons(shownCount: Int, onClear: () -> Unit, onShow: () -> Unit) {
  val shape = RoundedCornerShape(26.dp)
  Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    OutlinedButton(
        onClick = onClear,
        shape = shape,
        border = BorderStroke(1.dp, Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
        modifier = Modifier.weight(130.5f).height(52.dp).testTag(EventsTags.FILTERS_CLEAR),
    ) {
      Text(
          stringResource(R.string.events_filter_clear),
          style = MaterialTheme.typography.titleSmall,
      )
    }
    Button(
        onClick = onShow,
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Bg),
        modifier = Modifier.weight(179.5f).height(52.dp).testTag(EventsTags.FILTERS_SHOW),
    ) {
      Text(
          pluralStringResource(R.plurals.events_filter_show, shownCount, shownCount),
          style = MaterialTheme.typography.titleSmall,
      )
    }
  }
}

/** Figma's 14-day strip, today included. */
private const val DAYS_TO_PICK = 14L

/** "Sat" above the day's number. */
private val weekdayFormat = DateTimeFormatter.ofPattern("EEE")
