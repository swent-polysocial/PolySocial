// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #432; a latest message that fails,
// from #521's review).
package com.polysocial.model.chat

import com.polysocial.model.friend.friendshipId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [observeChatPreviews]: each one-to-one chat with its latest message, for the Chats tab (#432).
 */
class OneToOneChatPreviewsTest {
  private val chats = FakeOneToOneChatRepository(currentUid = ME)

  private suspend fun firstAnswer() =
      chats.observeChatPreviews().first { it != OneToOneChatPreviewsResult.Loading }

  @Test
  fun eachChatWithAMessage_isListedWithItsLatestMessage() = runTest {
    chats.seedChat(LEA)
    chats.seedMessage(LEA, "m1", "lea", "Meet at 8?", NOON)
    val latest = chats.seedMessage(LEA, "m2", ME, "On my way", NOON.plusSeconds(60))
    chats.seedChat(TOM)
    chats.seedMessage(TOM, "t1", "tom", "Thanks!", NOON)

    val result = firstAnswer() as OneToOneChatPreviewsResult.Success

    assertEquals(
        setOf(
            OneToOneChatPreview(OneToOneChat(LEA, "lea"), latest),
            OneToOneChatPreview(OneToOneChat(TOM, "tom"), chats.messagesOf(TOM).single()),
        ),
        result.previews.toSet(),
    )
    assertEquals(false, result.fromCache)
  }

  @Test
  fun aChatWithoutAMessage_isNotListed() = runTest {
    chats.seedChat(LEA)

    assertEquals(OneToOneChatPreviewsResult.Success(emptyList(), false), firstAnswer())
  }

  @Test
  fun noChat_isAnEmptyList() = runTest {
    assertEquals(OneToOneChatPreviewsResult.Success(emptyList(), false), firstAnswer())
  }

  @Test
  fun aDeletedAccountsChat_isListedWithoutTheirUid() = runTest {
    chats.seedChat(LEA, uids = listOf(ME))
    chats.seedMessage(LEA, "m1", "", "Bye", NOON)

    val result = firstAnswer() as OneToOneChatPreviewsResult.Success

    assertEquals(OneToOneChat(LEA, null), result.previews.single().chat)
  }

  @Test
  fun fromTheCache_isReported() = runTest {
    chats.seedChat(LEA)
    chats.seedMessage(LEA, "m1", "lea", "Saved", NOON)
    chats.readFromCache = true

    assertTrue((firstAnswer() as OneToOneChatPreviewsResult.Success).fromCache)
  }

  @Test
  fun chatsThatCantBeRead_fail() = runTest {
    chats.readFailure = FakeMessageRepository.ReadFailure.NETWORK

    assertEquals(OneToOneChatPreviewsResult.Failed, firstAnswer())
  }

  @Test
  fun aLatestMessageThatCantReachTheServer_failsTheList() = runTest {
    seedTwoChats()
    val failing = withLatest(LEA, LatestMessageResult.NetworkError)

    assertEquals(
        OneToOneChatPreviewsResult.Failed,
        failing.observeChatPreviews().first { it != OneToOneChatPreviewsResult.Loading },
    )
  }

  @Test
  fun aLatestMessageThatIsDenied_isLeftOut_andTheOthersListed() = runTest {
    seedTwoChats()
    val denied = withLatest(LEA, LatestMessageResult.UnexpectedError)

    val result =
        denied.observeChatPreviews().first { it != OneToOneChatPreviewsResult.Loading }
            as OneToOneChatPreviewsResult.Success

    assertEquals(listOf(TOM), result.previews.map { it.chat.id })
  }

  private fun seedTwoChats() {
    chats.seedChat(LEA)
    chats.seedMessage(LEA, "m1", "lea", "Hi", NOON)
    chats.seedChat(TOM)
    chats.seedMessage(TOM, "t1", "tom", "Thanks!", NOON)
  }

  /** [chats], except that the latest message of [failing] answers [latest]. */
  private fun withLatest(failing: String, latest: LatestMessageResult) =
      object : OneToOneChatRepository by chats {
        override fun observeLatestMessage(pairId: String): Flow<LatestMessageResult> =
            if (pairId == failing) flowOf(latest) else chats.observeLatestMessage(pairId)
      }

  @Test
  fun loading_untilTheChatsAnswer() = runTest {
    assertEquals(OneToOneChatPreviewsResult.Loading, chats.observeChatPreviews().first())
  }

  private companion object {
    const val ME = "me"
    val LEA = checkNotNull(friendshipId(ME, "lea"))
    val TOM = checkNotNull(friendshipId(ME, "tom"))
    val NOON: Instant = Instant.parse("2030-01-02T11:00:00Z")
  }
}
