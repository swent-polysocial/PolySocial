// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #450 under Lucas Pinto Ricardo's supervision and
// approval).
// Contributors: Claude Opus 5.5 (wrote these tests, #368 review); Konstantinos Perdikeas
// (directed, reviewed and owns them).
// Contributors: Claude (Opus 5.5, Anthropic) (the friend action on a student's profile, #478);
// Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakeProfileFriendsRepository
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.ProfileFriendsResult
import com.polysocial.model.user.PublicProfile
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The route wires the real ViewModel to the screen: its state, Retry and Back. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class StudentProfileRouteTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val lea = PublicProfile("lea", "Léa Martin", "IN", "BA3", setOf("sports"))
  private val profiles = FakePublicProfileRepository(mutableMapOf("lea" to lea))
  private var backs = 0
  private val friends = FakeFriendRepository(currentUid = "alice")
  private val friendLists = FakeProfileFriendsRepository()

  private fun show() {
    val viewModel =
        StudentProfileViewModel(
            SavedStateHandle(mapOf(StudentProfileViewModel.UID_ARG to "lea")),
            profiles,
            FakeAuthRepository(AuthUser("alice", "alice@epfl.ch", true)),
            friends,
            friendLists,
            FakeNetworkMonitor(),
        )
    compose.setContent { PolySocialTheme { StudentProfileRoute({ backs++ }, viewModel) } }
  }

  @Test
  fun showsTheProfileTheViewModelRead_andBackLeaves() {
    show()

    compose.onNodeWithTag(C.Tag.student_profile_name).assertTextEquals("Léa Martin")
    compose.onNodeWithTag(C.Tag.student_profile_back).performClick()
    assertEquals(1, backs)
  }

  @Test
  fun retry_afterAFailedRead_readsAgainAndShowsTheProfile() {
    profiles.failure = FakePublicProfileRepository.Failure.Offline
    show()
    compose.onNodeWithTag(C.Tag.student_profile_error).assertExists()

    profiles.failure = null
    compose.onNodeWithTag(C.Tag.error_retry).performClick()

    compose.onNodeWithTag(C.Tag.student_profile_name).assertTextEquals("Léa Martin")
    assertEquals(listOf(listOf("lea"), listOf("lea")), profiles.requestedUids)
  }

  @Test
  fun retryReloadsTheFriendsListWithoutReopeningTheProfile() {
    friendLists.online = ProfileFriendsResult.Error
    show()
    compose.onNodeWithTag(C.Tag.profile_friends_status).assertTextEquals("Couldn’t load friends")

    friendLists.online = ProfileFriendsResult.Found(emptyList())
    compose.onNodeWithTag(C.Tag.profile_friends_retry).performClick()

    compose.onNodeWithTag(C.Tag.profile_friends_count).assertTextEquals("Friends · 0")
    assertEquals(listOf(listOf("lea")), profiles.requestedUids)
  }

  @Test
  fun anotherStudentsProfile_showsAddFriend_andTappingItSendsTheRequest() {
    show()

    compose.onNodeWithTag(C.Tag.scan_friend_button).assertTextEquals("Add friend").performClick()

    compose.onNodeWithTag(C.Tag.scan_friend_button).assertTextEquals("Request sent")
    compose
        .onNodeWithTag(C.Tag.scan_friend_note)
        .assertTextEquals("Léa will see your request in PolySocial.")
    assertEquals("alice", friends.friendship("alice~lea")?.requestedBy)
  }
}
