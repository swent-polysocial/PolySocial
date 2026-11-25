// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests for the shared card, #452);
// Konstantinos Perdikeas (directed, reviewed and owns them).
package com.polysocial.ui.friend

import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.FriendRepository
import com.polysocial.model.friend.SendFriendRequestResult
import com.polysocial.model.user.PublicProfile
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FriendCardController] on its own, with a host state that is just the card or nothing. The scan
 * screen's tests (`ScanFriendViewModelTest`) cover its buttons and requests through that screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FriendCardControllerTest {
  private val lea = PublicProfile("lea", "Léa Martin", "IN", "BA3", setOf("sports"))
  private val fake =
      FakeFriendRepository(currentUid = "alice", now = Instant.parse("2026-11-16T10:00:00Z"))

  /**
   * The replies of the next requests, in call order, each given when the test completes it, without
   * writing: the request was stored earlier, only its reply is late.
   */
  private val sendReplies = ArrayDeque<CompletableDeferred<SendFriendRequestResult>>()

  /** [fake], counting the requests sent through it. */
  private var sendCalls = 0
  private val friends =
      object : FriendRepository by fake {
        override suspend fun sendRequest(otherUid: String): SendFriendRequestResult {
          sendCalls++
          sendReplies.removeFirstOrNull()?.let {
            return it.await()
          }
          return fake.sendRequest(otherUid)
        }
      }

  private val state = MutableStateFlow<FriendCard?>(null)

  private fun TestScope.controller() =
      FriendCardController(
          "alice",
          friends,
          backgroundScope,
          state,
          cardOf = { it },
          withCard = { _, card -> card },
      )

  @Test
  fun aSecondTapWhileTheRequestRuns_sendsNothingMore() =
      runTest(UnconfinedTestDispatcher()) {
        // The friendships list hasn't shown the request yet when the second tap comes (here it
        // never delivers), so only the busy card can stop a second request.
        fake.readFailure = true
        val controller = controller()
        state.value = controller.cardFor(lea)
        val gate = CompletableDeferred<Unit>()
        fake.sendResponseGate = gate

        controller.onButton()
        controller.onButton()
        gate.complete(Unit)

        assertEquals(1, sendCalls)
        assertEquals(FriendCard(lea, FriendButton.SENT), state.value)
      }

  @Test
  fun aLateReply_afterTheCardWasClosedAndReopened_cantHideTheirAcceptance() =
      runTest(UnconfinedTestDispatcher()) {
        val controller = controller()
        state.value = controller.cardFor(lea)
        val reply = CompletableDeferred<SendFriendRequestResult>()
        sendReplies.add(reply)
        controller.onButton()

        state.value = null
        fake.seedAcceptedFriendship("lea", "alice")
        state.value = controller.cardFor(lea)
        reply.complete(SendFriendRequestResult.Sent)

        assertEquals(FriendCard(lea, FriendButton.FRIENDS), state.value)
      }

  @Test
  fun aLateReply_toTheClosedCard_leavesTheReopenedCardsOwnRequestRunning() =
      runTest(UnconfinedTestDispatcher()) {
        val controller = controller()
        state.value = controller.cardFor(lea)
        val first = CompletableDeferred<SendFriendRequestResult>()
        val second = CompletableDeferred<SendFriendRequestResult>()
        sendReplies.addAll(listOf(first, second))
        controller.onButton()
        state.value = null
        state.value = controller.cardFor(lea)
        controller.onButton()

        first.complete(SendFriendRequestResult.UnexpectedError)
        assertEquals(FriendCard(lea, FriendButton.ADD, busy = true), state.value)

        second.complete(SendFriendRequestResult.Sent)
        assertEquals(FriendCard(lea, FriendButton.SENT), state.value)
        assertEquals(2, sendCalls)
      }

  @Test
  fun withoutACard_theListenerLeavesTheHostStateAlone_andATapDoesNothing() =
      runTest(UnconfinedTestDispatcher()) {
        val controller = controller()

        fake.seedAcceptedFriendship("lea", "alice")
        controller.onButton()

        assertEquals(null, state.value)
        assertEquals(0, sendCalls)
        assertEquals(FriendButton.FRIENDS, controller.cardFor(lea).button)
      }
}
