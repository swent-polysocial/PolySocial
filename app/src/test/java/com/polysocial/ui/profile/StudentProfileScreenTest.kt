// Contributors: Claude Opus 5.5 (wrote these tests, #368); Konstantinos Perdikeas (directed,
// reviewed and owns them).
// Contributors: Claude (Opus 5.5, Anthropic) (the friend action on a student's profile, #478);
// Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.profile

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.user.PublicProfile
import com.polysocial.resources.C
import com.polysocial.ui.friend.FriendActionError
import com.polysocial.ui.friend.FriendButton
import com.polysocial.ui.friend.FriendCard
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class StudentProfileScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val lea = PublicProfile("lea", "Léa Martin", "IN", "BA3", setOf("sports", "cinema"))
  private val calls = mutableListOf<String>()

  private fun show(state: StudentProfileUiState) {
    compose.setContent {
      PolySocialTheme {
        StudentProfileScreen(state, onBack = { calls += "back" }, onRetry = { calls += "retry" })
      }
    }
  }

  private fun string(resource: Int) = context.getString(resource)

  @Test
  fun found_showsOnlyThePublicFields_underTheTitle() {
    show(StudentProfileUiState.Found(lea))

    compose.onNodeWithText(string(R.string.student_profile_title)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.student_profile_name).assertTextEquals("Léa Martin")
    compose.onNodeWithTag(C.Tag.student_profile_details).assertTextEquals("IN · BA3")
    compose.onAllNodesWithTag(C.Tag.student_profile_interest).assertCountEquals(2)
    compose.onNodeWithText(string(R.string.interest_sports)).assertIsDisplayed()
    compose.onAllNodesWithTag(C.Tag.error_state).assertCountEquals(0)
    compose.onAllNodesWithTag(C.Tag.loading_state).assertCountEquals(0)
  }

  @Test
  fun found_withoutInterests_showsNoInterestsHeading() {
    show(StudentProfileUiState.Found(lea.copy(interests = emptySet())))

    compose.onNodeWithTag(C.Tag.student_profile_name).assertIsDisplayed()
    compose.onAllNodesWithTag(C.Tag.student_profile_interest).assertCountEquals(0)
    compose.onAllNodesWithText(string(R.string.scan_friend_interests)).assertCountEquals(0)
  }

  @Test
  fun loading_showsTheSharedLoadingState() {
    show(StudentProfileUiState.Loading)

    compose.onNodeWithTag(C.Tag.student_profile_loading).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.student_profile_loading)).assertIsDisplayed()
    compose.onAllNodesWithTag(C.Tag.student_profile_name).assertCountEquals(0)
  }

  @Test
  fun couldNotLoad_offersRetry() {
    show(StudentProfileUiState.CouldNotLoad)

    compose.onNodeWithTag(C.Tag.student_profile_error).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.student_profile_error_title)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.error_retry).performClick()

    assertEquals(listOf("retry"), calls)
  }

  @Test
  fun notFound_saysSoWithoutRetry() {
    show(StudentProfileUiState.NotFound)

    compose.onNodeWithTag(C.Tag.student_profile_not_found).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.student_profile_not_found_title)).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.student_profile_not_found_body)).assertIsDisplayed()
    compose.onAllNodesWithTag(C.Tag.error_retry).assertCountEquals(0)
  }

  @Test
  fun back_callsOnBack_inEveryState() {
    var state by mutableStateOf<StudentProfileUiState>(StudentProfileUiState.Loading)
    compose.setContent {
      PolySocialTheme { StudentProfileScreen(state, onBack = { calls += "back" }, onRetry = {}) }
    }
    val states =
        listOf(
            StudentProfileUiState.Loading,
            StudentProfileUiState.Found(lea),
            StudentProfileUiState.NotFound,
            StudentProfileUiState.CouldNotLoad,
        )

    for (shown in states) {
      state = shown
      compose.onNodeWithTag(C.Tag.student_profile_back).performClick()
    }

    assertEquals(List(states.size) { "back" }, calls)
  }

  // The friend action (#478, Figma #493).

  private fun showWithCard(card: FriendCard?) {
    compose.setContent {
      PolySocialTheme {
        StudentProfileScreen(
            StudentProfileUiState.Found(lea),
            onBack = {},
            onRetry = {},
            friendCard = card,
            onFriendButton = { calls += "friend" },
        )
      }
    }
  }

  @Test
  fun anotherStudentsProfile_showsTheFriendButton_andItsNote() {
    showWithCard(FriendCard(lea, FriendButton.ADD, error = FriendActionError.OFFLINE))

    compose
        .onNodeWithTag(C.Tag.scan_friend_note)
        .assertTextEquals(string(R.string.scan_friend_offline))
    compose
        .onNodeWithTag(C.Tag.scan_friend_button)
        .assertTextEquals(string(R.string.scan_friend_add))
    compose.onNodeWithTag(C.Tag.scan_friend_button).performClick()
    assertEquals(listOf("friend"), calls)
  }

  @Test
  fun withoutACard_yourOwnProfile_hasNoFriendAction() {
    showWithCard(null)

    compose.onNodeWithTag(C.Tag.student_profile_name).assertTextEquals("Léa Martin")
    compose.onAllNodesWithTag(C.Tag.scan_friend_button).assertCountEquals(0)
    compose.onAllNodesWithTag(C.Tag.scan_friend_note).assertCountEquals(0)
  }
}
