// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests of the Find a group screen, #233,
// and the refusal, place, starting and initials cases after review; the friend reason, #497.
package com.polysocial.ui.group

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GroupWriteResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class FindGroupScreenTest {

  @get:Rule(order = 0) val main = MainDispatcherRule()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun string(id: Int) = context.getString(id)

  private val events = FakeEventRepository()
  private val groups = FakeGroupRepository(currentUid = ME).apply { readableEventIds += EVENT }
  private val profiles =
      FakePublicProfileRepository(
          listOf(
                  PublicProfile(ME, "You", "IN", "BA3", setOf("climbing", "techno")),
                  PublicProfile("lea", "Léa Martin", "IN", "BA3", setOf("climbing")),
                  PublicProfile("tom", "Tom Rossi", "SC", "BA3", setOf("cinema")),
              )
              .associateBy { it.uid }
              .toMutableMap()
      )
  private var online = true
  private val finished = mutableListOf<FindGroupDone>()
  private var backs = 0

  // Through the route and the ViewModel, with fakes

  private fun showRoute(locationName: String = "Satellite") {
    events.seed(validEvent(title = "Balélec warm-up").copy(id = EVENT, locationName = locationName))
    val viewModel =
        FindGroupViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT)),
            events,
            groups,
            profiles,
            FakeFriendRepository(currentUid = ME),
            FakeAuthRepository(AuthUser(ME, "fictional.student@epfl.ch", true)),
            object : NetworkMonitor {
              override fun isOnline() = online
            },
            Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Europe/Zurich")),
        )
    rule.setContent {
      PolySocialTheme {
        FindGroupRoute(onBack = { backs++ }, onDone = { finished += it }, viewModel = viewModel)
      }
    }
    settle()
  }

  private fun settle() {
    repeat(2) {
      rule.waitForIdle()
      main.dispatcher.scheduler.advanceUntilIdle()
    }
    rule.waitForIdle()
  }

  private fun inCard(groupId: String, text: String) =
      rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(FindGroupTags.card(groupId))))

  @Test
  fun aGroup_showsItsMembersPlacesAndWhyItFits() {
    val group = groups.seedGroup(EVENT, listOf("lea", "tom"))
    showRoute()

    rule.onNodeWithTag(FindGroupTags.EVENT).assertIsDisplayed()
    rule.onNodeWithText("Balélec warm-up").assertIsDisplayed()
    inCard(group.id, "2 of 4").assertIsDisplayed()
    inCard(group.id, "2 places left").assertIsDisplayed()
    inCard(group.id, "Léa Martin · IN · BA3   Tom Rossi · SC · BA3").assertIsDisplayed()
    inCard(group.id, "1 shared interest").assertIsDisplayed()
    inCard(group.id, "Same section · IN").assertIsDisplayed()
    inCard(group.id, "Same year · BA3").assertIsDisplayed()
    inCard(group.id, "LM").assertIsDisplayed()
    rule.onNodeWithText(string(R.string.find_group_privacy)).performScrollTo().assertIsDisplayed()
  }

  @Test
  fun joiningAGroup_leavesTheScreenAsJoined() {
    val group = groups.seedGroup(EVENT, listOf("lea"))
    showRoute()

    rule.onNodeWithTag(FindGroupTags.join(group.id)).assertHeightIsAtLeast(48.dp).performClick()
    settle()

    assertEquals(listOf(FindGroupDone.JOINED), finished)
    assertEquals(listOf("lea", ME), groups.groupsOf(EVENT).single().memberUids)
  }

  @Test
  fun startingAGroup_whenThereIsNone_leavesTheScreenAsStarted() {
    showRoute()
    rule.onNodeWithTag(FindGroupTags.EMPTY).assertIsDisplayed()
    rule.onNodeWithText(string(R.string.find_group_empty_title)).assertIsDisplayed()

    rule.onNodeWithTag(FindGroupTags.START).performClick()
    settle()

    assertEquals(listOf(FindGroupDone.STARTED), finished)
    assertEquals(listOf(ME), groups.groupsOf(EVENT).single().memberUids)
  }

  @Test
  fun aGroupThatFilledUp_saysSo_andStays() {
    val group = groups.seedGroup(EVENT, listOf("a", "b", "c"))
    showRoute()
    // Someone else takes the last place first.
    groups.currentUid = "d"
    runBlocking { groups.joinGroup(EVENT, group.id) }
    groups.currentUid = ME

    rule.onNodeWithTag(FindGroupTags.join(group.id)).performClick()
    settle()

    rule.onNodeWithText(string(R.string.find_group_full)).assertIsDisplayed()
    assertEquals(emptyList<FindGroupDone>(), finished)
    rule.onNodeWithTag(FindGroupTags.EMPTY).assertIsDisplayed()
  }

  @Test
  fun eachRefusal_showsItsOwnMessage() {
    val group = groups.seedGroup(EVENT, listOf("lea"))
    showRoute()

    groups.writeFailure = GroupWriteResult.AlreadyInGroup
    rule.onNodeWithTag(FindGroupTags.join(group.id)).performClick()
    settle()
    rule.onNodeWithText(string(R.string.find_group_already_in_group)).assertIsDisplayed()

    groups.writeFailure = GroupWriteResult.NotFound
    rule.onNodeWithTag(FindGroupTags.join(group.id)).performClick()
    settle()
    rule.onNodeWithText(string(R.string.find_group_gone)).assertIsDisplayed()

    // Starting a group of an event that is gone.
    rule.onNodeWithTag(FindGroupTags.START).performClick()
    settle()
    rule.onNodeWithText(string(R.string.find_group_event_gone)).assertIsDisplayed()
    assertEquals(emptyList<FindGroupDone>(), finished)
  }

  @Test
  fun anEventWithoutAPlace_showsOnlyItsDayAndTime() {
    showRoute(locationName = "")

    val line =
        rule
            .onNode(
                hasAnyAncestor(hasTestTag(FindGroupTags.EVENT)) and hasText(" · ", substring = true)
            )
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString()
    // Day and time only, with no empty place after them.
    assertEquals(1, line.split(" · ").size - 1)
  }

  @Test
  fun aSecondRefusal_whileTheFirstShows_stillShowsItsSnackbar() {
    val group = groups.seedGroup(EVENT, listOf("lea"))
    showRoute()
    groups.writeFailure = GroupWriteResult.NetworkError
    rule.onNodeWithTag(FindGroupTags.join(group.id)).performClick()
    settle()
    rule.onNodeWithText(string(R.string.find_group_offline)).assertIsDisplayed()

    groups.writeFailure = GroupWriteResult.UnexpectedError
    rule.onNodeWithTag(FindGroupTags.join(group.id)).performClick()
    settle()

    rule.onNodeWithText(string(R.string.find_group_unexpected)).assertIsDisplayed()
  }

  @Test
  fun offline_showsTheBanner_andDisablesJoinAndStart() {
    val group = groups.seedGroup(EVENT, listOf("lea"))
    online = false
    showRoute()

    rule.onNodeWithTag(FindGroupTags.OFFLINE_BANNER).assertIsDisplayed()
    rule.onNodeWithTag(FindGroupTags.join(group.id)).assertIsNotEnabled()
    rule.onNodeWithTag(FindGroupTags.START).assertIsNotEnabled()
    assertEquals(listOf("lea"), groups.groupsOf(EVENT).single().memberUids)
  }

  @Test
  fun aFailedRead_showsCouldNotLoad_andRetryShowsTheGroups() {
    groups.readFailure = FakeGroupRepository.ReadFailure.UNEXPECTED
    val group = groups.seedGroup(EVENT, listOf("lea"))
    showRoute()
    rule.onNodeWithTag(FindGroupTags.ERROR).assertIsDisplayed()
    rule.onNodeWithText(string(R.string.find_group_error_title)).assertIsDisplayed()

    groups.readFailure = null
    rule.onNodeWithText(string(R.string.retry)).performClick()
    settle()

    rule.onNodeWithTag(FindGroupTags.card(group.id)).assertIsDisplayed()
  }

  @Test
  fun back_leavesWithoutAChange() {
    showRoute()

    rule.onNodeWithTag(FindGroupTags.BACK).performClick()

    assertEquals(1, backs)
    assertEquals(emptyList<FindGroupDone>(), finished)
  }

  // The screen alone, for states the fakes answer too fast to see

  private fun showScreen(state: FindGroupUiState) {
    rule.setContent { PolySocialTheme { FindGroupScreen(state, null, FindGroupActions()) } }
  }

  @Test
  fun searching_showsTheLoadingState() {
    showScreen(FindGroupUiState.Loading)

    rule.onNodeWithTag(FindGroupTags.LOADING).assertIsDisplayed()
    rule.onNodeWithText(string(R.string.find_group_searching)).assertIsDisplayed()
  }

  @Test
  fun joining_showsJoiningOnThatGroup_andLocksTheOtherActions() {
    val suggestion = { id: String ->
      GroupSuggestion(id, 4, listOf(GroupMember("x", null)), 0, null, null)
    }
    showScreen(
        FindGroupUiState.Loaded(
            event = null,
            suggestions = listOf(suggestion("a"), suggestion("b")),
            isOffline = false,
            pending = PendingGroupWrite.Join("a"),
        )
    )

    inCard("a", string(R.string.find_group_joining)).assertIsDisplayed()
    rule.onNodeWithTag(FindGroupTags.join("a")).assertIsNotEnabled()
    rule.onNodeWithTag(FindGroupTags.join("b")).assertIsNotEnabled()
    rule.onNodeWithTag(FindGroupTags.START).assertIsNotEnabled()
    // A member whose profile couldn't be read shows "?".
    inCard("b", "?").assertIsDisplayed()
  }

  @Test
  fun starting_showsStartingOnTheButton_andLocksJoin() {
    showScreen(
        FindGroupUiState.Loaded(
            event = null,
            suggestions = listOf(GroupSuggestion("a", 4, emptyList(), 0, null, null)),
            isOffline = false,
            pending = PendingGroupWrite.Start,
        )
    )

    rule.onNodeWithText(string(R.string.find_group_starting)).assertIsDisplayed()
    rule.onNodeWithTag(FindGroupTags.START).assertIsNotEnabled()
    rule.onNodeWithTag(FindGroupTags.join("a")).assertIsNotEnabled()
  }

  @Test
  fun membersInitials_comeFromTheirNames_andANamelessOneShowsAQuestionMark() {
    showScreen(
        FindGroupUiState.Loaded(
            event = null,
            suggestions =
                listOf(
                    GroupSuggestion(
                        "a",
                        4,
                        listOf(
                            GroupMember("noe", PublicProfile("noe", "Noé", "IN", "BA5", setOf())),
                            GroupMember("x", PublicProfile("x", " ", "SC", "BA1", setOf())),
                        ),
                        0,
                        null,
                        null,
                    )
                ),
            isOffline = false,
        )
    )

    inCard("a", "N").assertIsDisplayed()
    inCard("a", "?").assertIsDisplayed()
    // A blank name is left out of the members line, not shown as an empty part.
    inCard("a", "Noé · IN · BA5   SC · BA1").assertIsDisplayed()
  }

  @Test
  fun withGroups_startANewGroupIsEnabled() {
    showScreen(
        FindGroupUiState.Loaded(
            event = null,
            suggestions = listOf(GroupSuggestion("a", 4, emptyList(), 0, null, null)),
            isOffline = false,
        )
    )

    rule.onNodeWithTag(FindGroupTags.START).assertIsEnabled()
  }

  // The friend reason (#497), from #496's frames

  private fun friend(uid: String, name: String) =
      GroupMember(uid, PublicProfile(uid, name, "GC", "BA3", setOf()))

  /** One group "a" whose [members] are all the student's friends, with the other three reasons. */
  private fun showFriends(vararg members: GroupMember) =
      showScreen(
          FindGroupUiState.Loaded(
              event = null,
              suggestions =
                  listOf(
                      GroupSuggestion(
                          "a",
                          4,
                          members.toList(),
                          sharedInterests = 1,
                          sameSection = "IN",
                          sameYear = "BA3",
                          friends = members.toList(),
                      )
                  ),
              isOffline = false,
          )
      )

  private fun friendsReason() = rule.onNodeWithTag(FindGroupTags.friends("a"))

  @Test
  fun oneFriend_isNamedByTheirFirstName_firstAmongTheReasons() {
    showFriends(friend("marc", "  Marc Dubois "))

    friendsReason().assertIsDisplayed().assertTextEquals("Your friend Marc")
    // On the same line, before the shared interests.
    assertTrue(
        friendsReason().getBoundsInRoot().right <
            inCard("a", "1 shared interest").getBoundsInRoot().left
    )
  }

  @Test
  fun severalFriends_areCounted() {
    showFriends(friend("marc", "Marc Dubois"), friend("lea", "Léa Martin"))

    friendsReason().assertTextEquals("2 friends")
  }

  @Test
  fun aFriendWhoseNameCantBeRead_isCounted_neverNamed() {
    showFriends(GroupMember("marc", null))
    friendsReason().assertTextEquals("1 friend")
  }

  @Test
  fun aFriendWithABlankName_isCounted() {
    showFriends(friend("marc", "   "))
    friendsReason().assertTextEquals("1 friend")
  }

  @Test
  fun noFriend_showsNoFriendReason() {
    showScreen(
        FindGroupUiState.Loaded(
            event = null,
            suggestions = listOf(GroupSuggestion("a", 4, emptyList(), 1, "IN", null)),
            isOffline = false,
        )
    )

    friendsReason().assertDoesNotExist()
    inCard("a", "1 shared interest").assertIsDisplayed()
  }

  // CI's screen width; native graphics measure and cut text like a phone.
  @Config(qualifiers = "w320dp-h640dp")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test
  fun aLongFirstName_staysOnOneLine_withinTheCard() {
    showFriends(friend("anne", "Anne-Charlotte-Maximilienne Rossi"))

    friendsReason().assertTextEquals("Your friend Anne-Charlotte-Maximilienne")
    val chip = friendsReason().getBoundsInRoot()
    // The other chip's node is its text, inside the chip's 4 dp padding above and below.
    val otherText = inCard("a", "Same year · BA3").getBoundsInRoot()
    val card = rule.onNodeWithTag(FindGroupTags.card("a")).getBoundsInRoot()
    // One line, as tall as the other chips, and cut inside the card's 14 dp padding (the card's
    // node is its content, inside that padding).
    assertEquals(
        (otherText.bottom - otherText.top).value + 8f,
        (chip.bottom - chip.top).value,
        0.5f,
    )
    assertTrue(chip.right <= card.right)
  }

  private companion object {
    const val EVENT = "party"
    const val ME = "me"
  }
}
