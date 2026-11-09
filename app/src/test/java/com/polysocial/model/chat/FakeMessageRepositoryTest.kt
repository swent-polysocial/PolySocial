// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #237).
package com.polysocial.model.chat

import java.time.Instant
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fake follows the [MessageRepository] contracts the chat screen (#238) will be tested with.
 */
class FakeMessageRepositoryTest {
  private val repository =
      FakeMessageRepository(currentUid = "student").apply {
        setMembers(EVENT, GROUP, setOf("student", "other"))
      }

  /** The first result after [MessagesResult.Loading]. */
  private suspend fun messages(groupId: String = GROUP) =
      repository.observeMessages(EVENT, groupId).drop(1).first()

  @Test
  fun sendMessage_storesTheTrimmedTextAsTheStudent() = runTest {
    assertEquals(SendMessageResult.Sent, repository.sendMessage(EVENT, GROUP, "  See you at 6?\n"))

    val message = repository.messagesOf(EVENT, GROUP).single()
    assertEquals("student", message.senderId)
    assertEquals("See you at 6?", message.text)
    assertEquals(MessagesResult.Success(listOf(message), fromCache = false), messages())
  }

  @Test
  fun messages_areListedOldestFirst_thenById() = runTest {
    val time = Instant.parse("2030-01-01T10:00:00Z")
    val later = repository.seedMessage(EVENT, GROUP, "z", "other", "Later", time.plusSeconds(60))
    val second = repository.seedMessage(EVENT, GROUP, "b", "other", "Second", time)
    val first = repository.seedMessage(EVENT, GROUP, "a", "student", "First", time)
    repository.sendMessage(EVENT, GROUP, "Newest")

    assertEquals(
        listOf(first, second, later),
        repository.messagesOf(EVENT, GROUP).take(3),
    )
    assertEquals("Newest", repository.messagesOf(EVENT, GROUP).last().text)
  }

  @Test
  fun observeMessages_emitsEveryNewMessage() = runTest {
    val results = mutableListOf<MessagesResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeMessages(EVENT, GROUP).take(3).toList(results)
        }

    repository.sendMessage(EVENT, GROUP, "Hi")
    collecting.join()

    assertEquals(MessagesResult.Loading, results[0])
    assertEquals(MessagesResult.Success(emptyList(), fromCache = false), results[1])
    assertEquals(listOf("Hi"), (results[2] as MessagesResult.Success).messages.map { it.text })
  }

  @Test
  fun sendMessage_afterALaterStoredMessage_isStillListedLast() = runTest {
    val seeded = Instant.parse("2031-06-01T08:00:00Z")
    repository.seedMessage(EVENT, GROUP, "z", "other", "From the future", seeded)

    repository.sendMessage(EVENT, GROUP, "First reply")
    repository.sendMessage(EVENT, GROUP, "Second reply")

    val stored = repository.messagesOf(EVENT, GROUP)
    assertEquals(
        listOf("From the future", "First reply", "Second reply"),
        stored.map { it.text },
    )
    assertEquals(seeded.plusSeconds(1), stored[1].createdAt)
    assertEquals(seeded.plusSeconds(2), stored[2].createdAt)
  }

  @Test
  fun groups_keepTheirOwnMessages() = runTest {
    repository.setMembers(EVENT, OTHER_GROUP, setOf("student"))
    repository.sendMessage(EVENT, OTHER_GROUP, "Only here")

    assertEquals(MessagesResult.Success(emptyList(), fromCache = false), messages())
    assertEquals(1, repository.messagesOf(EVENT, OTHER_GROUP).size)
  }

  @Test
  fun observeMessages_aMessageInAnotherGroup_emitsNothing() = runTest {
    repository.setMembers(EVENT, OTHER_GROUP, setOf("student"))
    val results = mutableListOf<MessagesResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeMessages(EVENT, GROUP).toList(results)
        }

    repository.sendMessage(EVENT, OTHER_GROUP, "Elsewhere")
    repository.sendMessage(EVENT, GROUP, "Here")
    collecting.cancel()

    assertEquals(
        listOf(
            MessagesResult.Loading,
            MessagesResult.Success(emptyList(), fromCache = false),
            MessagesResult.Success(repository.messagesOf(EVENT, GROUP), fromCache = false),
        ),
        results,
    )
  }

  @Test
  fun sendMessage_aBlankOrTooLongText_isInvalidAndStoresNothing() = runTest {
    assertEquals(
        SendMessageResult.Invalid(MessageTextError.BLANK),
        repository.sendMessage(EVENT, GROUP, "\n "),
    )
    assertEquals(
        SendMessageResult.Invalid(MessageTextError.TOO_LONG),
        repository.sendMessage(EVENT, GROUP, "a".repeat(MAX_MESSAGE_LENGTH + 1)),
    )

    assertTrue(repository.messagesOf(EVENT, GROUP).isEmpty())
  }

  @Test
  fun aStudentWhoIsNotAMember_cannotReadOrSend() = runTest {
    repository.setMembers(EVENT, GROUP, setOf("other"))

    assertEquals(MessagesResult.UnexpectedError, messages())
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(EVENT, GROUP, "Hi"))
    assertEquals(MessagesResult.UnexpectedError, messages(groupId = "unknown"))
    assertTrue(repository.messagesOf(EVENT, GROUP).isEmpty())
  }

  @Test
  fun nobodySignedIn_cannotReadOrSend() = runTest {
    repository.currentUid = null

    assertEquals(MessagesResult.UnexpectedError, messages())
    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(EVENT, GROUP, "Hi"))
  }

  @Test
  fun idsThatCannotBeDocumentIds_cannotBeReadOrSentTo() = runTest {
    repository.setMembers("events/other", GROUP, setOf("student"))

    assertEquals(
        listOf(MessagesResult.Loading, MessagesResult.UnexpectedError),
        repository.observeMessages("events/other", GROUP).toList(),
    )
    assertEquals(
        SendMessageResult.UnexpectedError,
        repository.sendMessage("events/other", GROUP, "Hi"),
    )
  }

  @Test
  fun sendMessage_offline_isANetworkErrorAndStoresNothing() = runTest {
    repository.online = false

    assertEquals(SendMessageResult.NetworkError, repository.sendMessage(EVENT, GROUP, "Hi"))

    assertTrue(repository.messagesOf(EVENT, GROUP).isEmpty())
  }

  @Test
  fun sendMessage_aSendFailure_isReturnedAndStoresNothing() = runTest {
    repository.sendFailure = SendMessageResult.UnexpectedError

    assertEquals(SendMessageResult.UnexpectedError, repository.sendMessage(EVENT, GROUP, "Hi"))

    assertTrue(repository.messagesOf(EVENT, GROUP).isEmpty())
  }

  @Test
  fun observeMessages_readFailures_endTheFlowWithTheirOwnError() = runTest {
    repository.readFailure = FakeMessageRepository.ReadFailure.NETWORK
    assertEquals(
        listOf(MessagesResult.Loading, MessagesResult.NetworkError),
        repository.observeMessages(EVENT, GROUP).toList(),
    )

    repository.readFailure = FakeMessageRepository.ReadFailure.UNEXPECTED
    assertEquals(
        listOf(MessagesResult.Loading, MessagesResult.UnexpectedError),
        repository.observeMessages(EVENT, GROUP).toList(),
    )
  }

  @Test
  fun observeMessages_reportsTheCacheFlag() = runTest {
    repository.readFromCache = true

    assertEquals(MessagesResult.Success(emptyList(), fromCache = true), messages())
  }

  private companion object {
    const val EVENT = "event-1"
    const val GROUP = "group-1"
    const val OTHER_GROUP = "group-2"
  }
}
