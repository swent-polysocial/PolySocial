// Contributors: OpenAI Codex, GPT-6.1 Sol (medium) (shared card day labels, #306 review).
package com.polysocial.ui.event.list

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.polysocial.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM")
private val dateWithYearFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

/** The day label shared by chosen-event and association cards, with a year outside this year. */
@Composable
internal fun eventDayLabel(start: LocalDate, today: LocalDate): String =
    when (start) {
      today -> stringResource(R.string.events_today)
      today.plusDays(1) -> stringResource(R.string.events_tomorrow)
      else -> start.format(if (start.year == today.year) dateFormat else dateWithYearFormat)
    }
