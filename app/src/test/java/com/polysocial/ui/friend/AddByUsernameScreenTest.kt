// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #452); Konstantinos Perdikeas
// (directed, reviewed and owns them).
package com.polysocial.ui.friend

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.FakeUsernameRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Add by @username (#452): the field and its states, then the whole flow on Friends. */
@RunWith(AndroidJUnit4::class)
class AddByUsernameScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val calls = mutableListOf<String>()
  private val field = hasTestTag(AddByUsernameTags.FIELD).and(hasSetTextAction())

  private fun render(state: AddByUsernameUiState, offline: Boolean = false) {
    compose.setContent {
      PolySocialTheme {
        AddByUsername(
            state,
            offline,
            AddByUsernameActions(
                onInput = { calls += "input:$it" },
                onLookUp = { calls += "look" },
            ),
        )
      }
    }
  }

  private fun tag(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

  @Test
  fun theFieldShowsItsLabelAndHint_andLookUpNeedsAName() {
    render(AddByUsernameUiState())

    compose.onNodeWithText("Add by @username").assertIsDisplayed()
    compose
        .onNodeWithText(
            "Start with a letter. Use 3–20 lowercase letters, numbers, dots or underscores."
        )
        .assertIsDisplayed()
    tag(AddByUsernameTags.LOOK_UP).assertIsNotEnabled()
    compose.onNode(field).performTextInput("nora")
    assertEquals(listOf("input:nora"), calls)
  }

  @Test
  fun aTypedName_isLookedUpWithTheButton_orTheKeyboardsSearch() {
    render(AddByUsernameUiState(input = "nora_meier"))

    tag(AddByUsernameTags.LOOK_UP).assertIsEnabled().performClick()
    compose.onNode(field).performImeAction()

    assertEquals(listOf("look", "look"), calls)
  }

  @Test
  fun offline_orWhileLookingUp_lookUpIsDisabled_andTheKeyboardDoesNothing() {
    render(AddByUsernameUiState(input = "nora_meier"), offline = true)
    tag(AddByUsernameTags.LOOK_UP).assertIsNotEnabled()
    compose.onNode(field).performImeAction()

    assertTrue(calls.isEmpty())
  }

  @Test
  fun whileLookingUp_lookUpIsDisabled() {
    render(AddByUsernameUiState(input = "nora_meier", looking = true))

    tag(AddByUsernameTags.LOOK_UP).assertIsNotEnabled()
  }

  @Test
  fun eachProblem_showsItsMessage_andAFailureOffersTryAgain() {
    val messages =
        mapOf(
            UsernameLookupProblem.NOT_FOUND to
                ("No student found" to "Check the username or ask your friend to share it."),
            UsernameLookupProblem.OWN_NAME to
                ("That’s your own username" to "Enter a friend’s username to send them a request."),
            UsernameLookupProblem.FAILED to
                ("Couldn’t look up this username" to "Check your connection, then try again."),
        )
    val problem = mutableStateOf<UsernameLookupProblem?>(null)
    compose.setContent {
      PolySocialTheme {
        AddByUsername(
            AddByUsernameUiState(input = "nora_meier", problem = problem.value),
            offline = false,
            AddByUsernameActions({}, {}),
        )
      }
    }
    for ((shown, texts) in messages) {
      problem.value = shown
      compose.waitForIdle()
      compose.onNodeWithText(texts.first).assertIsDisplayed()
      compose.onNodeWithText(texts.second).assertIsDisplayed()
      val label = if (shown == UsernameLookupProblem.FAILED) "Try again" else "Look up"
      compose.onNodeWithText(label).assertIsDisplayed()
    }
  }

  // The whole flow, with the real ViewModels on fakes.

  private val nora = PublicProfile("nora", "Nora Meier", "IN", "BA3", setOf("sports"))
  private val auth = FakeAuthRepository(AuthUser("alice", "alice@epfl.ch", true))
  private val usernames =
      FakeUsernameRepository("alice").apply {
        names["nora_meier"] = "nora"
        names["alice.b"] = "alice"
      }
  private val profiles = FakePublicProfileRepository(mutableMapOf("nora" to nora))
  private val friends =
      FakeFriendRepository(currentUid = "alice", now = Instant.parse("2026-11-24T18:00:00Z"))
  private val network = FakeNetworkMonitor(online = true)

  private fun renderFriends() {
    // Built once, outside composition, as hiltViewModel() would.
    val friendsList = FriendsViewModel(auth, friends, profiles, network)
    val lookup = AddByUsernameViewModel(auth, usernames, profiles, friends)
    compose.setContent {
      PolySocialTheme {
        FriendsRoute(
            onBack = {},
            onOpenProfile = {},
            onScan = {},
            viewModel = friendsList,
            addByUsername = lookup,
        )
      }
    }
  }

  @Test
  fun lookingUpAFriend_opensTheirCard_whichSendsTheRequest_andClosesBackToTheName() {
    renderFriends()

    compose.onNode(field).performTextInput("@Nora_Meier")
    tag(AddByUsernameTags.LOOK_UP).performClick()
    tag(C.Tag.scan_friend_card).assertIsDisplayed()
    compose.onNodeWithText("Nora Meier").assertIsDisplayed()
    tag(C.Tag.scan_friend_note).assertTextEquals("Check that this is the friend you meant to find.")

    tag(C.Tag.scan_friend_button).performClick()
    compose.onNodeWithText("Request sent").assertIsDisplayed()
    assertEquals("alice", friends.friendship("alice~nora")?.requestedBy)

    tag(C.Tag.scan_friend_close).performClick()
    tag(AddByUsernameTags.SECTION).assertIsDisplayed()
    compose.onNode(field).assertTextContains("Nora_Meier")
    compose.onNodeWithText("@@", substring = true).assertDoesNotExist()
  }

  @Test
  fun back_closesTheCard() {
    renderFriends()
    compose.onNode(field).performTextInput("nora_meier")
    tag(AddByUsernameTags.LOOK_UP).performClick()
    tag(C.Tag.scan_friend_card).assertIsDisplayed()

    Espresso.pressBack()

    tag(AddByUsernameTags.SECTION).assertIsDisplayed()
  }

  @Test
  fun yourOwnName_staysOnFriends_withItsMessage() {
    renderFriends()

    compose.onNode(field).performTextInput("alice.b")
    tag(AddByUsernameTags.LOOK_UP).performClick()

    compose.onNodeWithText("That’s your own username").assertIsDisplayed()
    tag(FriendsTags.SCREEN).assertIsDisplayed()
  }

  @Test
  fun offline_friendsDisablesLookUp() {
    network.online = false
    renderFriends()

    compose.onNode(field).performTextInput("nora_meier")

    tag(AddByUsernameTags.LOOK_UP).assertIsNotEnabled()
  }
}
