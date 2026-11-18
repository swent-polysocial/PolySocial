// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests of every organizer's name on the
// detail screen, from the Figma "Organizers on the detail" frames, #371).
package com.polysocial.ui.event.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.validEvent
import com.polysocial.ui.event.studentEvent
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.PolySocialTheme
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A student's event names all its organizers under its title, "by A, B and C" (#371, Figma section
 * 21), each name in bold. Every name is fictional.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class EventDetailOrganizersScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val zone = ZoneId.of("Europe/Zurich")
  private val study = studentEvent(validEvent(title = "Study"))

  private fun show(names: List<String>) {
    val state =
        EventDetailUiState.Loaded(
            eventDetails(study, zone),
            isOffline = false,
            organizerNames = names,
            attendance = Attendance.GOING,
        )
    composeTestRule.setContent {
      PolySocialTheme { EventDetailScreen(state, onBack = {}, onRetry = {}) }
    }
  }

  private fun organizers() =
      composeTestRule.onNodeWithTag(EventDetailTags.ORGANIZER_NAME, useUnmergedTree = true)

  @Test
  fun twoOrganizers_areJoinedWithAnd() {
    show(listOf("Léa Martin", "Tom Rossi"))

    organizers().assertIsDisplayed().assertTextEquals("by Léa Martin and Tom Rossi")
  }

  @Test
  fun threeOrganizers_areListedAsInFigma() {
    show(listOf("Léa Martin", "Tom Rossi", "Arnav Shah"))

    organizers().assertIsDisplayed().assertTextEquals("by Léa Martin, Tom Rossi and Arnav Shah")
  }

  @Test
  fun fiveOrganizers_areAllNamed() {
    show(listOf("Léa Martin", "Tom Rossi", "Arnav Shah", "Mia Keller", "Noah Dubois"))

    organizers()
        .assertIsDisplayed()
        .assertTextEquals("by Léa Martin, Tom Rossi, Arnav Shah, Mia Keller and Noah Dubois")
  }

  @Test
  fun eachName_isBold_andNothingElse() {
    val names = listOf("Léa Martin", "and", "by")
    val text = withBoldNames("by ${nameMarker(0)}, ${nameMarker(1)} and ${nameMarker(2)}", names)

    assertEquals("by Léa Martin, and and by", text.text)
    val bold =
        text.spanStyles
            .filter { it.item.fontWeight == FontWeight.Bold && it.item.color == Ink }
            .map { text.text.substring(it.start, it.end) }
    assertEquals(names, bold)
    assertEquals(names.size, text.spanStyles.size)
  }

  @Test
  fun aMarkerWithoutAName_isKeptAsItIs() {
    val text = withBoldNames("by ${nameMarker(0)} and ${nameMarker(1)}", listOf("Léa Martin"))

    assertEquals("by Léa Martin and ${nameMarker(1)}", text.text)
  }
}
