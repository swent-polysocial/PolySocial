// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests of the group card on the event
// detail, #233.
// Contributors: OpenAI Codex (asserted the group card opens the roster, #234).
package com.polysocial.ui.event.detail

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.validEvent
import com.polysocial.model.user.PublicProfile
import com.polysocial.ui.group.GroupMember
import com.polysocial.ui.theme.PolySocialTheme
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YourGroupCardTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private var findGroups = 0
  private var openedGroups = 0

  private val lea = GroupMember("lea", PublicProfile("lea", "Léa Martin", "IN", "BA3", setOf()))
  private val tom = GroupMember("tom", PublicProfile("tom", "Tom Rossi", "SC", "BA3", setOf()))
  private val me = GroupMember(ME, null)

  private fun show(members: List<GroupMember>) {
    val details =
        EventDetails(
            event = validEvent(title = "Balélec warm-up").copy(id = "party"),
            date = LocalDate.of(2026, 10, 2),
            startTime = LocalTime.of(20, 0),
            endTime = null,
            endDayOffset = 0,
        )
    composeTestRule.setContent {
      PolySocialTheme {
        EventDetailScreen(
            EventDetailUiState.Loaded(
                details,
                isOffline = false,
                yourGroup = YourGroup("group", capacity = 4, members = members, myUid = ME),
            ),
            onBack = {},
            onRetry = {},
            onFindGroup = { findGroups++ },
            onOpenGroup = { openedGroups++ },
        )
      }
    }
  }

  private fun inCard(text: String) =
      composeTestRule.onNode(
          hasText(text) and hasAnyAncestor(hasTestTag(EventDetailTags.YOUR_GROUP)),
          // The clickable card merges its texts into one node.
          useUnmergedTree = true,
      )

  @Test
  fun inAGroup_theCardShowsItsMembers_insteadOfFindAGroup() {
    show(listOf(lea, tom, me))

    composeTestRule.onNodeWithTag(EventDetailTags.YOUR_GROUP).performScrollTo().assertIsDisplayed()
    inCard("Your group · 3 of 4").assertIsDisplayed()
    inCard("Léa, Tom and you. One place left, so someone may still join.").assertIsDisplayed()
    inCard("LM").assertIsDisplayed()
    inCard("TR").assertIsDisplayed()
    inCard(context.getString(R.string.your_group_you)).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(EventDetailTags.FIND_GROUP).assertCountEquals(0)
  }

  @Test
  fun tappingTheCard_opensTheGroupRoster() {
    show(listOf(lea, me))

    composeTestRule.onNodeWithTag(EventDetailTags.YOUR_GROUP).performScrollTo().performClick()

    assertEquals(1, openedGroups)
    assertEquals(0, findGroups)
  }

  @Test
  fun aFullGroup_saysItIsFull() {
    show(listOf(lea, tom, GroupMember("noe", null), me))

    composeTestRule.onNodeWithTag(EventDetailTags.YOUR_GROUP).performScrollTo()
    inCard("Your group · 4 of 4").assertIsDisplayed()
    // Noé's profile couldn't be read: "?" and no name.
    inCard("?").assertIsDisplayed()
    inCard("Léa, Tom and you. The group is full.").assertIsDisplayed()
  }

  @Test
  fun aloneInTheGroup_saysJustYou() {
    show(listOf(me))

    composeTestRule.onNodeWithTag(EventDetailTags.YOUR_GROUP).performScrollTo()
    inCard("Your group · 1 of 4").assertIsDisplayed()
    inCard("Just you so far. 3 places left, so others may still join.").assertIsDisplayed()
  }

  @Test
  fun membersWhoseProfilesCantBeRead_leaveOnlyThePlaces() {
    show(listOf(GroupMember("lea", null), me))

    composeTestRule.onNodeWithTag(EventDetailTags.YOUR_GROUP).performScrollTo()
    inCard("2 places left, so others may still join.").assertIsDisplayed()
  }

  private companion object {
    const val ME = "me"
  }
}
