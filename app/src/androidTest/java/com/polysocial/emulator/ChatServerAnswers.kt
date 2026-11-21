// Contributors: Claude (Opus 5.5, Anthropic) (the group chat's emulator test helpers, #237);
// Claude (Fable 5.2, Anthropic) (the latest message's, #363).
package com.polysocial.emulator

import com.polysocial.model.chat.LatestMessageResult
import com.polysocial.model.chat.Message
import com.polysocial.model.chat.MessagesResult
import com.polysocial.model.chat.MyOneToOneChatsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue

/** How long a chat test waits for the server's first answer. */
private const val SERVER_ANSWER_TIMEOUT_MS = 15_000L

/**
 * The server's first answer among these messages: not `Loading`, and a `Success` only once it isn't
 * from the cache (a failure ends the wait too). Fails the test after 15 s.
 */
@JvmName("firstMessagesFromServer")
suspend fun Flow<MessagesResult>.firstFromServer(): MessagesResult =
    withTimeout(SERVER_ANSWER_TIMEOUT_MS) {
      first { it !is MessagesResult.Loading && (it !is MessagesResult.Success || !it.fromCache) }
    }

/** Like [firstFromServer] for a chat's messages, for its latest message. */
@JvmName("firstLatestMessageFromServer")
suspend fun Flow<LatestMessageResult>.firstFromServer(): LatestMessageResult =
    withTimeout(SERVER_ANSWER_TIMEOUT_MS) {
      first {
        it !is LatestMessageResult.Loading && (it !is LatestMessageResult.Success || !it.fromCache)
      }
    }

/** Like [firstFromServer] for a chat's messages, for a student's one-to-one chats. */
@JvmName("firstOneToOneChatsFromServer")
suspend fun Flow<MyOneToOneChatsResult>.firstFromServer(): MyOneToOneChatsResult =
    withTimeout(SERVER_ANSWER_TIMEOUT_MS) {
      first {
        it !is MyOneToOneChatsResult.Loading &&
            (it !is MyOneToOneChatsResult.Success || !it.fromCache)
      }
    }

/** The messages of the server's first answer ([firstFromServer]), failing the test without any. */
suspend fun Flow<MessagesResult>.messagesFromServer(): List<Message> {
  val result = firstFromServer()
  assertTrue("Expected messages, got $result", result is MessagesResult.Success)
  return (result as MessagesResult.Success).messages
}
