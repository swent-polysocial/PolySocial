// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #364).
package com.polysocial.ui.chat

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.chat.ChatsRepository
import com.polysocial.model.chat.FakeMessageRepository
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [ChatsRoute] with a real [ChatsViewModel] on the fakes (#364). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ChatsRouteTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val groups = FakeGroupRepository(currentUid = "me")
  private val messages =
      FakeMessageRepository(currentUid = "me").apply { membersOf = groups::memberUidsOf }
  private val events =
      FakeEventRepository(currentUid = "me").apply {
        seed(
            validEvent(title = "Balélec warm-up").copy(id = "party", category = EventCategory.PARTY)
        )
      }
  private val opened = mutableListOf<Pair<String, String>>()

  private fun show() {
    val viewModel =
        ChatsViewModel(
            ChatsRepository(groups, messages),
            events,
            FakePublicProfileRepository(
                mutableMapOf("lea" to PublicProfile("lea", "Léa Martin", "IN", "BA3"))
            ),
            FakeAuthRepository(AuthUser("me", "me@epfl.ch", true)),
            FakeNetworkMonitor(),
            Clock.fixed(Instant.parse("2030-01-02T11:00:00Z"), ZoneId.of("Europe/Zurich")),
        )
    composeTestRule.setContent {
      PolySocialTheme {
        ChatsRoute(onOpenChat = { e, g -> opened += e to g }, viewModel = viewModel)
      }
    }
    composeTestRule.waitForIdle()
  }

  @Test
  fun theStudentsChats_showAndOpen() {
    val party = groups.seedGroup("party", listOf("me", "lea"))
    messages.seedMessage(
        "party",
        party.id,
        "m1",
        "lea",
        "On my way",
        Instant.parse("2030-01-02T10:00:00Z"),
    )

    show()

    val row = composeTestRule.onNodeWithTag(ChatsTags.row("party", party.id))
    row.assert(hasText("Balélec warm-up")).assert(hasText("Léa: On my way"))
    row.performClick()
    assertEquals(listOf("party" to party.id), opened)
  }

  @Test
  fun retry_readsTheChatsAgain() {
    groups.seedGroup("party", listOf("me"))
    groups.readFailure = FakeGroupRepository.ReadFailure.NETWORK
    show()

    groups.readFailure = null
    composeTestRule.onNodeWithTag(C.Tag.error_retry).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(ChatsTags.row("party", "group-1")).assert(hasText("Just you"))
  }
}
