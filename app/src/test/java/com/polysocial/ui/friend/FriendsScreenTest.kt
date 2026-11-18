// Contributors: OpenAI Codex (6.2 Sol, high; Compose Friends screen tests, #360).
package com.polysocial.ui.friend

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.user.PublicProfile
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FriendsScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val state = mutableStateOf(FriendsUiState())
  private val calls = mutableListOf<String>()

  private val friend =
      row(FriendsSection.FRIENDS, "lea", PublicProfile("lea", "Léa Sample", "IN", "BA3"))
  private val incoming =
      row(FriendsSection.INCOMING, "emma", PublicProfile("emma", "Emma Sample", "SV", "BA3"))
  private val sent = row(FriendsSection.SENT, "tom", null)

  private fun render(initial: FriendsUiState) {
    state.value = initial
    compose.setContent {
      PolySocialTheme {
        FriendsScreen(
            state.value,
            FriendsActions(
                onBack = { calls += "back" },
                onOpenProfile = { calls += "profile:$it" },
                onScan = { calls += "scan" },
                onRetryLoad = { calls += "reload" },
                onRemove = { state.value = state.value.copy(removal = it) },
                onKeep = { state.value = state.value.copy(removal = null) },
                onConfirm = { calls += "confirm" },
                onRetryAction = { calls += "retry" },
                onDismissNotice = { calls += "dismiss" },
            ),
        )
      }
    }
  }

  @Test
  fun groupsAreLabeledAndTheFriendAndScannerCanBeOpened() {
    render(FriendsUiState(FriendsContent.ListContent(listOf(friend, incoming, sent), false)))
    compose.onNodeWithText("Incoming requests").assertIsDisplayed()
    compose.onNodeWithText("Sent requests").assertIsDisplayed()
    compose.onNodeWithText("Your friends").assertIsDisplayed()
    compose.onNodeWithText("Profile unavailable").assertIsDisplayed()
    tag(FriendsTags.row("lea")).performClick()
    tag(FriendsTags.SCAN).performClick()
    tag(FriendsTags.BACK).performClick()
    assertEquals(listOf("profile:lea", "scan", "back"), calls)
  }

  @Test
  fun removeHasItsOwnMenuAndExplicitConfirmation() {
    render(FriendsUiState(FriendsContent.ListContent(listOf(friend), false)))
    tag(FriendsTags.options("lea")).performClick()
    tag(FriendsTags.REMOVE).performClick()
    compose.onNodeWithText("Remove Léa as a friend?").assertIsDisplayed()
    tag(FriendsTags.KEEP).performClick()
    tag(FriendsTags.CONFIRM).assertDoesNotExist()
    tag(FriendsTags.options("lea")).performClick()
    tag(FriendsTags.REMOVE).performClick()
    tag(FriendsTags.CONFIRM).performClick()
    assertEquals(listOf("confirm"), calls)
  }

  @Test
  fun offlineListKeepsTheRowsAndShowsTheActionRetry() {
    render(
        FriendsUiState(
            FriendsContent.ListContent(listOf(friend, sent), true),
            notice = FriendsNotice.OFFLINE,
        )
    )
    tag(FriendsTags.OFFLINE).assertIsDisplayed()
    tag(FriendsTags.row("lea")).assertIsDisplayed()
    tag(FriendsTags.options("lea")).assertDoesNotExist()
    tag(FriendsTags.SCAN).assertIsNotEnabled()
    tag(FriendsTags.NOTICE_ACTION).performClick()
    assertEquals(listOf("retry"), calls)
  }

  @Test
  fun loadingEmptyErrorAndOfflineWithoutSavedDataRenderDistinctly() {
    render(FriendsUiState())
    compose.onNodeWithText("Loading friends…").assertIsDisplayed()
    tag(FriendsTags.SCAN).assertDoesNotExist()
    compose.runOnIdle { state.value = FriendsUiState(FriendsContent.Error) }
    tag(C.Tag.error_retry).performClick()
    compose.runOnIdle { state.value = FriendsUiState(FriendsContent.OfflineWithoutSavedData) }
    tag(FriendsTags.OFFLINE_EMPTY).assertIsDisplayed()
    tag(FriendsTags.RETRY).performClick()
    compose.runOnIdle {
      state.value = FriendsUiState(FriendsContent.ListContent(emptyList(), false))
    }
    tag(FriendsTags.EMPTY).assertIsDisplayed()
    tag(FriendsTags.SCAN).performClick()
    assertEquals(listOf("reload", "reload", "scan"), calls)
  }

  @Test
  fun failedRemovalOffersRetryAndStaleRemovalOffersDismiss() {
    val content = FriendsContent.ListContent(listOf(friend), false)
    render(FriendsUiState(content, notice = FriendsNotice.FAILED))
    compose.onNodeWithText("Couldn’t remove friend.").assertIsDisplayed()
    tag(FriendsTags.NOTICE_ACTION).performClick()

    compose.runOnIdle {
      state.value = FriendsUiState(content, notice = FriendsNotice.FRIENDSHIP_CHANGED)
    }
    compose.onNodeWithText("This friendship has changed.").assertIsDisplayed()
    tag(FriendsTags.NOTICE_ACTION).performClick()
    tag(FriendsTags.NOTICE).performSemanticsAction(SemanticsActions.Dismiss)
    assertEquals(listOf("retry", "dismiss", "dismiss"), calls)
  }

  private fun tag(tag: String) = compose.onNodeWithTag(tag)

  private fun row(section: FriendsSection, uid: String, profile: PublicProfile?) =
      FriendRow(
          Friendship(
              id = "me~$uid",
              uids = listOf("me", uid),
              requestedBy = if (section == FriendsSection.INCOMING) uid else "me",
              status =
                  if (section == FriendsSection.FRIENDS) FriendshipStatus.ACCEPTED
                  else FriendshipStatus.PENDING,
              createdAt = Instant.EPOCH,
              acceptedAt = null,
          ),
          uid,
          section,
          profile,
      )
}
