// Contributors: Claude (wrote these tests, #128; large font sizes and a long name after a review,
// #128).
package com.polysocial.ui.event.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.event.Event
import com.polysocial.model.event.validEvent
import com.polysocial.ui.event.CLUB_NAME
import com.polysocial.ui.event.associationEvent
import com.polysocial.ui.event.studentEvent
import com.polysocial.ui.theme.PolySocialTheme
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The detail's association row (#128): with the name, "by <name>" and the Verified badge (Figma
 * "Event detail · Sprint 2 · association name"); without it, the "Verified association" badge
 * (Figma "Event detail · Sprint 2"); a student's event shows neither.
 */
@RunWith(AndroidJUnit4::class)
class EventDetailAssociationNameScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val party = associationEvent(validEvent(title = "Party")).copy(id = "party")
  private val study = studentEvent(validEvent(title = "Study")).copy(id = "study")

  private fun show(event: Event, name: String?) {
    val state =
        EventDetailUiState.Loaded(
            eventDetails(event, ZoneId.of("Europe/Zurich")),
            isOffline = false,
            associationName = name,
        )
    composeTestRule.setContent {
      PolySocialTheme { EventDetailScreen(state, onBack = {}, onRetry = {}) }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  /** The Verified pill, read as one label like TalkBack does: its node holds its text. */
  private fun pill(tag: String) = composeTestRule.onNodeWithTag(tag)

  @Test
  fun anAssociationsEvent_showsByItsNameAndTheVerifiedBadge() {
    show(party, CLUB_NAME)

    node(EventDetailTags.ASSOCIATION_NAME).assertIsDisplayed().assertTextEquals("by $CLUB_NAME")
    pill(EventDetailTags.VERIFIED_BADGE).assertIsDisplayed().assertTextEquals("Verified")
  }

  // The largest font size Android offers, with native graphics, which measure text like a phone.
  @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun atTheLargestFontSize_aLongNameWraps_andTheBadgeStaysWhole() {
    // The longest name the rules accept (#126).
    val longName = "Association " + "x".repeat(48)
    show(party, longName)

    node(EventDetailTags.ASSOCIATION_NAME).assertTextEquals("by $longName")
    val badge = pill(EventDetailTags.VERIFIED_BADGE).assertIsDisplayed()
    // Its padding and the text "Verified" on one line, not squeezed by the name.
    badge.assertWidthIsAtLeast(36.dp)
    val screen = composeTestRule.onNodeWithTag(EventDetailTags.SCREEN).getBoundsInRoot()
    assertTrue("The badge ends past the screen", badge.getBoundsInRoot().right <= screen.right)
    val name = node(EventDetailTags.ASSOCIATION_NAME).getBoundsInRoot()
    assertTrue("The name runs under the badge", name.right <= badge.getBoundsInRoot().left)
  }

  @Test
  fun anAssociationsEvent_withoutItsName_showsVerifiedAssociation() {
    show(party, null)

    node(EventDetailTags.ASSOCIATION_NAME).assertDoesNotExist()
    pill(EventDetailTags.VERIFIED_BADGE)
        .assertIsDisplayed()
        .assertTextEquals("Verified association")
  }

  @Test
  fun aStudentsEvent_showsNeitherTheBadgeNorAName() {
    // Even with a name in the state, a student's event has no association to show.
    show(study, CLUB_NAME)

    node(EventDetailTags.ASSOCIATION_NAME).assertDoesNotExist()
    pill(EventDetailTags.VERIFIED_BADGE).assertDoesNotExist()
  }
}
