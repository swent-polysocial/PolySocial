// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #237).
// Contributors: Claude (Fable 5.2, Anthropic) (tests of a group's latest message, #363).
// Contributors: Claude (Opus 5.5, Anthropic) (tests of the members read from the groups, after
// review, #363).
// Contributors: Claude (Opus 5.5, Anthropic) (unsend tests, #439).
package com.polysocial.model.chat

import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GroupWriteResult
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

  @Test
  fun observeLatestMessage_emitsTheGroupsNewestMessage_onlyWhenItChanges() = runTest {
    repository.setMembers(EVENT, OTHER_GROUP, setOf("student"))
    val time = Instant.parse("2030-01-01T10:00:00Z")
    val results = mutableListOf<LatestMessageResult>()
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeLatestMessage(EVENT, GROUP).toList(results)
        }

    val first = repository.seedMessage(EVENT, GROUP, "b", "other", "First", time)
    // Neither an older message nor another group's changes the newest one.
    repository.seedMessage(EVENT, GROUP, "a", "other", "Older", time.minusSeconds(60))
    repository.sendMessage(EVENT, OTHER_GROUP, "Elsewhere")
    repository.sendMessage(EVENT, GROUP, "Newest")
    collecting.cancel()

    assertEquals(
        listOf(
            LatestMessageResult.Loading,
            LatestMessageResult.Success(null, fromCache = false),
            LatestMessageResult.Success(first, fromCache = false),
            LatestMessageResult.Success(repository.messagesOf(EVENT, GROUP).last(), false),
        ),
        results,
    )
    assertEquals("Newest", (results.last() as LatestMessageResult.Success).message?.text)
  }

  @Test
  fun observeLatestMessage_failsLikeTheChat() = runTest {
    suspend fun latest(groupId: String = GROUP) =
        repository.observeLatestMessage(EVENT, groupId).toList()
    val unexpected = listOf(LatestMessageResult.Loading, LatestMessageResult.UnexpectedError)

    assertEquals(unexpected, latest(groupId = "unknown"))
    assertEquals(unexpected, latest(groupId = "groups/other"))
    repository.readFailure = FakeMessageRepository.ReadFailure.NETWORK
    assertEquals(listOf(LatestMessageResult.Loading, LatestMessageResult.NetworkError), latest())
    repository.readFailure = FakeMessageRepository.ReadFailure.UNEXPECTED
    assertEquals(unexpected, latest())
    repository.readFailure = null
    repository.currentUid = null
    assertEquals(unexpected, latest())
  }

  @Test
  fun observeLatestMessage_reportsTheCacheFlag() = runTest {
    repository.readFromCache = true
    val sent = repository.seedMessage(EVENT, GROUP, "a", "other", "Hi", Instant.EPOCH)

    assertEquals(
        LatestMessageResult.Success(sent, fromCache = true),
        repository.observeLatestMessage(EVENT, GROUP).drop(1).first(),
    )
  }

  @Test
  fun membersOf_givesTheChatOfAGroupItsMembers_whileTheyStayInIt() = runTest {
    val groups = FakeGroupRepository(currentUid = "student").apply { readableEventIds += EVENT }
    val chats =
        FakeMessageRepository(currentUid = "student").apply { membersOf = groups::memberUidsOf }
    val created = groups.createGroup(EVENT) as GroupWriteResult.Success

    assertEquals(SendMessageResult.Sent, chats.sendMessage(EVENT, created.groupId, "Hi"))
    assertEquals(
        LatestMessageResult.Success(chats.messagesOf(EVENT, created.groupId).single(), false),
        chats.observeLatestMessage(EVENT, created.groupId).drop(1).first(),
    )

    groups.leaveGroup(EVENT, created.groupId)
    assertEquals(SendMessageResult.UnexpectedError, chats.sendMessage(EVENT, created.groupId, "Hi"))
    assertEquals(
        LatestMessageResult.UnexpectedError,
        chats.observeLatestMessage(EVENT, created.groupId).drop(1).first(),
    )
  }

  @Test
  fun setMembers_winsOverMembersOf_evenToDenyTheGroupsMembers() = runTest {
    repository.membersOf = { _, _ -> listOf("student") }
    repository.setMembers(EVENT, OTHER_GROUP, emptySet())

    assertEquals(MessagesResult.UnexpectedError, messages(groupId = OTHER_GROUP))
    assertEquals(
        MessagesResult.Success(emptyList(), fromCache = false),
        messages(groupId = "group-3"),
    )
    // GROUP's members, set by setMembers, don't include the outsider membersOf would give.
    repository.membersOf = { _, _ -> listOf("outsider") }
    repository.currentUid = "outsider"
    assertEquals(MessagesResult.UnexpectedError, messages())
  }

  // unsendMessage (#439)

  private val time: Instant = Instant.parse("2030-01-01T10:00:00Z")

  @Test
  fun unsend_emptiesOnlyTheText_live_andARetryIsUnsentToo() =
      runTest(UnconfinedTestDispatcher()) {
        repository.seedMessage(EVENT, GROUP, "theirs", "other", "Hi", time)
        repository.seedMessage(EVENT, GROUP, "mine", "student", "See you", time.plusSeconds(60))
        val latest = mutableListOf<LatestMessageResult>()
        val listening = launch { repository.observeLatestMessage(EVENT, GROUP).toList(latest) }

        assertEquals(UnsendMessageResult.Unsent, repository.unsendMessage(EVENT, GROUP, "mine"))
        assertEquals(UnsendMessageResult.Unsent, repository.unsendMessage(EVENT, GROUP, "mine"))

        val stored = repository.messagesOf(EVENT, GROUP)
        assertEquals(listOf("theirs", "mine"), stored.map { it.id })
        assertEquals(
            Message("mine", "student", UNSENT_MESSAGE_TEXT, time.plusSeconds(60)),
            stored[1],
        )
        assertEquals("Hi", stored[0].text)
        // Another member's latest message receives the empty text.
        assertEquals(
            LatestMessageResult.Success(stored[1], fromCache = false),
            latest.last(),
        )
        listening.cancel()
      }

  @Test
  fun unsend_anotherMembersMissingOrAnonymisedMessage_isUnexpected_withoutAWrite() = runTest {
    repository.seedMessage(EVENT, GROUP, "theirs", "other", "Hi", time)
    repository.seedMessage(EVENT, GROUP, "anonymous", DELETED_MESSAGE_SENDER, "Bye", time)

    for (id in listOf("theirs", "anonymous", "missing")) {
      assertEquals(
          id,
          UnsendMessageResult.UnexpectedError,
          repository.unsendMessage(EVENT, GROUP, id),
      )
    }
    assertEquals(listOf("Bye", "Hi"), repository.messagesOf(EVENT, GROUP).map { it.text })
  }

  @Test
  fun unsend_outOfTheGroup_isUnexpected_andAgainAllowedOnceBack() = runTest {
    repository.seedMessage(EVENT, GROUP, "mine", "student", "See you", time)
    repository.setMembers(EVENT, GROUP, setOf("other"))

    assertEquals(
        UnsendMessageResult.UnexpectedError,
        repository.unsendMessage(EVENT, GROUP, "mine"),
    )
    assertEquals("See you", repository.messagesOf(EVENT, GROUP).single().text)

    repository.setMembers(EVENT, GROUP, setOf("other", "student"))
    assertEquals(UnsendMessageResult.Unsent, repository.unsendMessage(EVENT, GROUP, "mine"))
  }

  @Test
  fun unsend_checksTheIdsAndUser_thenOnline_thenTheFailure_thenMembership() = runTest {
    repository.seedMessage(EVENT, GROUP, "mine", "student", "See you", time)
    repository.online = false
    repository.unsendFailure = UnsendMessageResult.UnexpectedError
    repository.setMembers(EVENT, GROUP, emptySet())

    for (ids in
        listOf(
            listOf("", GROUP, "mine"),
            listOf(EVENT, "a/b", "mine"),
            listOf(EVENT, GROUP, " "),
        )) {
      assertEquals(
          ids.toString(),
          UnsendMessageResult.UnexpectedError,
          repository.unsendMessage(ids[0], ids[1], ids[2]),
      )
    }
    assertEquals(UnsendMessageResult.NetworkError, repository.unsendMessage(EVENT, GROUP, "mine"))
    repository.online = true
    repository.unsendFailure = UnsendMessageResult.NetworkError
    assertEquals(UnsendMessageResult.NetworkError, repository.unsendMessage(EVENT, GROUP, "mine"))
    repository.unsendFailure = null
    assertEquals(
        UnsendMessageResult.UnexpectedError,
        repository.unsendMessage(EVENT, GROUP, "mine"),
    )
    repository.currentUid = null
    repository.setMembers(EVENT, GROUP, setOf("student"))
    assertEquals(
        UnsendMessageResult.UnexpectedError,
        repository.unsendMessage(EVENT, GROUP, "mine"),
    )
    assertEquals("See you", repository.messagesOf(EVENT, GROUP).single().text)
  }

  private companion object {
    const val EVENT = "event-1"
    const val GROUP = "group-1"
    const val OTHER_GROUP = "group-2"
  }
}
