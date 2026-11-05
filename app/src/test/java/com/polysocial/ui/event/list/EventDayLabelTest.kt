// Contributors: OpenAI Codex, GPT-6.1 Sol (medium) (day-label preservation tests, #306 review).
package com.polysocial.ui.event.list

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.ui.theme.PolySocialTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventDayLabelTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun labelsPreserveTodayTomorrowAndYearBoundaries() {
    val today = LocalDate.of(2026, 12, 31)
    val dates =
        listOf(today, today.plusDays(1), LocalDate.of(2026, 12, 29), LocalDate.of(2027, 1, 2))
    compose.setContent {
      PolySocialTheme { Column { dates.forEach { Text(eventDayLabel(it, today)) } } }
    }
    val context = ApplicationProvider.getApplicationContext<Context>()
    listOf(
            context.getString(R.string.events_today),
            context.getString(R.string.events_tomorrow),
            "Tue 29 Dec",
            "Sat 2 Jan 2027",
        )
        .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
  }
}
