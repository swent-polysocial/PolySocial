// Contributors: Claude (Fable 5.2, Anthropic) (wrote these tests, #363, with more from a test
// audit).
// Contributors: Claude (Opus 5.5, Anthropic) (chats read their members from the groups, one
// listener per chat, and a chat denied before its group leaves the list, after review, #363).
package com.polysocial.model.chat

import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GROUP_CAPACITY
import com.polysocial.model.group.Group
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.GroupWriteResult
import com.polysocial.model.group.MyGroupsResult
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ChatsRepository] on the fake group and message repositories, as the Chats tab will use it. */
class ChatsRepositoryTest {
  private val groups = FakeGroupRepository(currentUid = STUDENT)
  // A chat's members are its group's, as the chat rules read them from the group.
  private val messages =
      FakeMessageRepository(currentUid = STUDENT).apply { membersOf = groups::memberUidsOf }

  /** How many latest-message listeners are open, to check that none outlives the collection. */
  private var listening = 0

  /** How many latest-message listeners were ever opened, to check that they aren't reopened. */
  private var opened = 0

  /** This latest-message listener, counted in [listening] and [opened]. */
  private fun Flow<LatestMessageResult>.counted() = onStart {
    listening++
    opened++
  }
      .onCompletion { listening-- }

  private val countedMessages =
      object : MessageRepository by messages {
        override fun observeLatestMessage(eventId: String, groupId: String) =
            messages.observeLatestMessage(eventId, groupId).counted()
      }
  private val repository = ChatsRepository(groups, countedMessages)

  /** Seeds a group of [eventId] with [STUDENT] and [others], who can all read its chat. */
  private fun chat(eventId: String, vararg others: String): Group =
      groups.seedGroup(eventId, listOf(STUDENT) + others)

  /** Denies everyone the chat of the group [groupId] of [eventId], even its members. */
  private fun deny(eventId: String, groupId: String) =
      messages.setMembers(eventId, groupId, emptySet())

  private fun message(group: Group, id: String, text: String, at: Instant, sender: String = OTHER) =
      messages.seedMessage(group.eventId, group.id, id, sender, text, at)

  /** Another student joins [group]: the student's groups answer again from the server, with it. */
  private suspend fun anotherStudentJoins(group: Group) {
    groups.readableEventIds += group.eventId
    groups.currentUid = JOINER
    assertEquals(GroupWriteResult.Success(group.id), groups.joinGroup(group.eventId, group.id))
    groups.currentUid = STUDENT
  }

  /** Messages whose read of the group [slowGroupId] answers only once [answered] completes. */
  private fun messagesAnsweringLate(slowGroupId: String, answered: CompletableDeferred<Unit>) =
      object : MessageRepository by messages {
        override fun observeLatestMessage(
            eventId: String,
            groupId: String,
        ): Flow<LatestMessageResult> = flow {
          emit(LatestMessageResult.Loading)
          if (groupId == slowGroupId) answered.await()
          emitAll(messages.observeLatestMessage(eventId, groupId))
        }
      }

  /**
   * The fake's messages, except that the latest message of each group in [scripted] answers what
   * its flow emits and, like the real listener, ends after a failure. Every listener is counted.
   */
  private fun messagesWithLatest(
      vararg scripted: Pair<Group, Flow<LatestMessageResult>>
  ): MessageRepository {
    val listeners = scripted.associate { (group, listener) ->
      (group.eventId to group.id) to
          listener.transformWhile {
            emit(it)
            it == LatestMessageResult.Loading || it is LatestMessageResult.Success
          }
    }
    return object : MessageRepository by messages {
      override fun observeLatestMessage(eventId: String, groupId: String) =
          (listeners[eventId to groupId] ?: messages.observeLatestMessage(eventId, groupId))
              .counted()
    }
  }

  /** The fake's groups, except that the student's are what [myGroups] emits. */
  private fun groupsFrom(myGroups: Flow<MyGroupsResult>): GroupRepository =
      object : GroupRepository by groups {
        override fun observeMyGroups(): Flow<MyGroupsResult> = myGroups
      }

  /** Collects the chats in the background; the list holds every result so far. */
  private fun TestScope.startCollecting(chats: ChatsRepository = repository) =
      startCollecting(chats.observeMyChats())

  private fun TestScope.startCollecting(chats: Flow<MyChatsResult>): MutableList<MyChatsResult> {
    val results = mutableListOf<MyChatsResult>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      chats.collect { results += it }
    }
    runCurrent()
    return results
  }

  private val List<MyChatsResult>.latest: MyChatsResult.Success
    get() = last() as MyChatsResult.Success

  @Test
  fun aStudentInNoGroup_hasNoChats() = runTest {
    groups.seedGroup(PARTY, listOf(OTHER))

    val results = startCollecting()

    assertEquals(MyChatsResult.Success(emptyList(), fromCache = false), results.last())
    assertEquals(0, listening)
  }

  @Test
  fun chatsOfEveryEvent_areListedNewestActivityFirst_withTheirLatestMessage() = runTest {
    val quiet = chat(PARTY, OTHER)
    val older = chat(HIKE)
    val newest = chat(STUDY, OTHER, "third")
    groups.seedGroup(STUDY, listOf(OTHER))
    message(older, "a", "Early", NOON.plusSeconds(600))
    val olderLatest = message(older, "b", "Later", NOON.plusSeconds(1200), sender = STUDENT)
    val newestLatest = message(newest, "a", "Last word", NOON.plusSeconds(1800))

    val results = startCollecting()

    // The chat without messages counts from its group's creation, just after noon.
    assertEquals(
        MyChatsResult.Success(
            listOf(
                ChatPreview(newest, newestLatest),
                ChatPreview(older, olderLatest),
                ChatPreview(quiet, null),
            ),
            fromCache = false,
        ),
        results.last(),
    )
  }

  @Test
  fun aNewMessage_movesItsChatFirst() = runTest {
    val first = chat(PARTY)
    val second = chat(HIKE)
    val results = startCollecting()
    assertEquals(listOf(second, first), results.latest.chats.map { it.group })

    val sent = message(first, "a", "Anyone there?", NOON.plusSeconds(60))

    assertEquals(
        listOf(ChatPreview(first, sent), ChatPreview(second, null)),
        results.latest.chats,
    )
  }

  @Test
  fun chatsWithTheSameActivity_areOrderedByEventThenGroup() = runTest {
    val party = chat(PARTY)
    val hike = chat(HIKE)
    val at = NOON.plusSeconds(60)
    message(party, "a", "Same time", at)
    message(hike, "a", "Same time", at)

    val results = startCollecting()

    assertEquals(listOf(hike, party), results.latest.chats.map { it.group })
  }

  @Test
  fun chatsOfOneEventWithTheSameActivity_areOrderedByGroup() = runTest {
    // Two groups of one event exist only if a group and its marker ever disagree; the list keeps a
    // stable order even then.
    val groupB =
        Group("group-b", PARTY, GROUP_CAPACITY, listOf(STUDENT), STUDENT, NOON.plusSeconds(60))
    val groupA = groupB.copy(id = "group-a")
    listOf(groupA, groupB).forEach { messages.setMembers(PARTY, it.id, setOf(STUDENT)) }
    val twoGroups =
        object : GroupRepository by groups {
          override fun observeMyGroups(): Flow<MyGroupsResult> =
              flowOf(MyGroupsResult.Success(listOf(groupB, groupA), fromCache = false))
        }

    val results = startCollecting(ChatsRepository(twoGroups, messages))

    assertEquals(listOf(groupA, groupB), results.latest.chats.map { it.group })
  }

  @Test
  fun moreChatsThanAFlowBuffers_areAllListed_eachWithItsLatestMessage() = runTest {
    // 70 chats: more than the 64 results a channel-backed flow buffers by default.
    val chats = (1..70).map { chat("event-$it") }
    chats.forEachIndexed { index, group ->
      message(group, "a", "Message ${index + 1}", NOON.plusSeconds(3600L + index))
    }

    val results = startCollecting()

    assertEquals(chats.reversed(), results.latest.chats.map { it.group })
    assertEquals(
        (70 downTo 1).map { "Message $it" },
        results.latest.chats.map { it.latestMessage?.text },
    )
    assertEquals(70, listening)
  }

  @Test
  fun aGroupTheServerHasNotTimestampedYet_countsAsTheNewest() = runTest {
    val messaged = chat(PARTY)
    message(messaged, "a", "Hello", NOON.plusSeconds(3600))
    val pending = chat(HIKE).copy(createdAt = null)
    val pendingGroups =
        object : GroupRepository by groups {
          override fun observeMyGroups(): Flow<MyGroupsResult> =
              flowOf(MyGroupsResult.Success(listOf(messaged, pending), fromCache = false))
        }

    val results = startCollecting(ChatsRepository(pendingGroups, messages))

    assertEquals(listOf(pending, messaged), results.latest.chats.map { it.group })
  }

  @Test
  fun joiningAndLeavingGroups_updatesTheList_andStopsListeningToTheChatsLeft() = runTest {
    val kept = chat(PARTY, OTHER)
    val results = startCollecting()
    assertEquals(1, listening)

    val joined = groups.seedGroup(HIKE, listOf(STUDENT))
    assertEquals(listOf(joined, kept), results.latest.chats.map { it.group })
    assertEquals(2, listening)

    groups.leaveGroup(PARTY, kept.id)
    assertEquals(listOf(joined), results.latest.chats.map { it.group })
    assertEquals(1, listening)

    groups.leaveGroup(HIKE, joined.id)
    assertEquals(emptyList<ChatPreview>(), results.latest.chats)
    assertEquals(0, listening)
  }

  @Test
  fun joiningAGroup_opensOnlyItsChatsListener() = runTest {
    val first = chat(PARTY)
    val second = chat(HIKE)
    val results = startCollecting()
    assertEquals(2, opened)

    val joined = chat(STUDY)

    assertEquals(listOf(joined, second, first), results.latest.chats.map { it.group })
    // The chats already listed keep their listeners.
    assertEquals(3, opened)
    assertEquals(3, listening)
  }

  @Test
  fun aMemberJoiningAChat_updatesItsGroup_withoutListeningToItsMessagesAgain() = runTest {
    val group = chat(PARTY)
    groups.readableEventIds += PARTY
    val results = startCollecting()

    groups.currentUid = OTHER
    groups.joinGroup(PARTY, group.id)

    assertEquals(listOf(STUDENT, OTHER), results.latest.chats.single().group.memberUids)
    assertEquals(1, opened)
  }

  @Test
  fun groupsTurningToTheCache_keepTheirMessageListeners() = runTest {
    // Going offline re-emits the same groups as cached. Dropping the message listeners then would
    // empty an in-memory cache, and the chat would lose its latest message (seen on the emulators).
    val group = chat(PARTY)
    val latest = message(group, "a", "Still here", NOON.plusSeconds(60))
    val cached = MutableStateFlow(false)
    val offlineGroups =
        object : GroupRepository by groups {
          override fun observeMyGroups(): Flow<MyGroupsResult> = cached.map {
            MyGroupsResult.Success(listOf(group), fromCache = it)
          }
        }
    val results = startCollecting(ChatsRepository(offlineGroups, countedMessages))

    cached.value = true
    runCurrent()

    assertEquals(
        MyChatsResult.Success(listOf(ChatPreview(group, latest)), fromCache = true),
        results.last(),
    )
    assertEquals(1, opened)
  }

  @Test
  fun theList_waitsForEveryChatsLatestMessage() = runTest {
    val ready = chat(PARTY)
    val slow = chat(HIKE)
    val answered = CompletableDeferred<Unit>()
    val slowMessages =
        object : MessageRepository by messages {
          override fun observeLatestMessage(
              eventId: String,
              groupId: String,
          ): Flow<LatestMessageResult> = flow {
            emit(LatestMessageResult.Loading)
            if (groupId == slow.id) answered.await()
            emitAll(messages.observeLatestMessage(eventId, groupId))
          }
        }

    val results = startCollecting(ChatsRepository(groups, slowMessages))
    assertEquals(listOf<MyChatsResult>(MyChatsResult.Loading), results)

    answered.complete(Unit)
    runCurrent()
    assertEquals(listOf(slow, ready), results.latest.chats.map { it.group })
  }

  @Test
  fun whileTheGroupsHaveNotAnswered_itIsLoading() = runTest {
    val silentGroups =
        object : GroupRepository by groups {
          override fun observeMyGroups(): Flow<MyGroupsResult> = flow {
            emit(MyGroupsResult.Loading)
            awaitCancellation()
          }
        }

    val results = startCollecting(ChatsRepository(silentGroups, messages))

    assertEquals(listOf<MyChatsResult>(MyChatsResult.Loading), results)
  }

  @Test
  fun groupsThatAnsweredBeforeTheirLatestMessages_areNotListedYet() = runTest {
    // As with Firestore: the groups answer some time after Loading, each latest message later.
    val group = chat(PARTY)
    val myGroups = MutableStateFlow<MyGroupsResult>(MyGroupsResult.Loading)
    val lateGroups =
        object : GroupRepository by groups {
          override fun observeMyGroups(): Flow<MyGroupsResult> = myGroups
        }
    val answered = CompletableDeferred<Unit>()
    val results =
        startCollecting(ChatsRepository(lateGroups, messagesAnsweringLate(group.id, answered)))

    myGroups.value = MyGroupsResult.Success(listOf(group), fromCache = false)
    runCurrent()
    assertEquals(listOf<MyChatsResult>(MyChatsResult.Loading), results)

    answered.complete(Unit)
    runCurrent()
    assertEquals(listOf(ChatPreview(group, null)), results.latest.chats)
  }

  @Test
  fun aGroupJoinedWhileTheListShows_isAddedOnlyOnceItsLatestMessageAnswered() = runTest {
    val kept = chat(PARTY)
    val answered = CompletableDeferred<Unit>()
    // The group joined below is the second one the fake creates.
    val results =
        startCollecting(ChatsRepository(groups, messagesAnsweringLate("group-2", answered)))
    val shown = results.toList()
    assertEquals(listOf(kept), results.latest.chats.map { it.group })

    val joined = chat(HIKE)
    runCurrent()
    // Neither a list that shows the new chat as empty, nor Loading again: the list stays.
    assertEquals(shown, results)

    answered.complete(Unit)
    runCurrent()
    assertEquals(listOf(joined, kept), results.latest.chats.map { it.group })
  }

  @Test
  fun aJoinedChatWaitingForItsLatestMessage_doesNotHoldUpTheOthers() = runTest {
    val kept = chat(PARTY)
    val answered = CompletableDeferred<Unit>()
    // The group joined below is the second one the fake creates.
    val results =
        startCollecting(ChatsRepository(groups, messagesAnsweringLate("group-2", answered)))

    val joined = chat(HIKE)
    val sent = message(kept, "a", "Still on for tonight?", NOON.plusSeconds(60))
    // The new message shows at once, without the joined chat, which hasn't answered yet.
    assertEquals(listOf(ChatPreview(kept, sent)), results.latest.chats)

    answered.complete(Unit)
    runCurrent()
    assertEquals(
        listOf(ChatPreview(kept, sent), ChatPreview(joined, null)),
        results.latest.chats,
    )
  }

  @Test
  fun aChatLeftAndJoinedAgain_ignoresTheLastAnswerOfItsStoppedListener() = runTest {
    // The student leaves and joins again before the list has caught up, while the first listener
    // still delivers an answer: that answer must not stand for the new listener's.
    val group = chat(PARTY, OTHER)
    val myGroups = Channel<MyGroupsResult>(Channel.UNLIMITED)
    val firstListener = Channel<LatestMessageResult>(Channel.UNLIMITED)
    var listeners = 0
    val chatMessages =
        object : MessageRepository by messages {
          override fun observeLatestMessage(eventId: String, groupId: String) =
              if (listeners++ == 0) firstListener.receiveAsFlow() else SILENT
        }
    val results = mutableListOf<MyChatsResult>()
    // Not unconfined: the listeners and the list each run in the order they were woken up.
    backgroundScope.launch {
      ChatsRepository(groupsFrom(myGroups.receiveAsFlow()), chatMessages).observeMyChats().collect {
        results += it
      }
    }
    myGroups.send(MyGroupsResult.Success(listOf(group), fromCache = false))
    firstListener.send(LatestMessageResult.Success(null, fromCache = false))
    runCurrent()
    assertEquals(listOf(ChatPreview(group, null)), results.latest.chats)

    myGroups.send(MyGroupsResult.Success(emptyList(), fromCache = false))
    myGroups.send(MyGroupsResult.Success(listOf(group), fromCache = false))
    val stale = Message("a", OTHER, "Before leaving", NOON.plusSeconds(60))
    firstListener.send(LatestMessageResult.Success(stale, fromCache = false))
    runCurrent()

    // The chat is back, but waits for its new listener, which hasn't answered.
    assertEquals(MyChatsResult.Success(emptyList(), fromCache = false), results.last())
    assertEquals(2, listeners)
  }

  @Test
  fun chatsFromTheCache_sayFromCache_whetherTheGroupsOrAMessageComeFromIt() = runTest {
    chat(PARTY)
    groups.readFromCache = true
    assertTrue(startCollecting().latest.fromCache)

    groups.readFromCache = false
    messages.readFromCache = true
    assertTrue(startCollecting().latest.fromCache)

    messages.readFromCache = false
    assertEquals(false, startCollecting().latest.fromCache)
  }

  @Test
  fun groupsThatCannotBeRead_endTheFlowWithTheirError() = runTest {
    chat(PARTY)

    groups.readFailure = FakeGroupRepository.ReadFailure.NETWORK
    assertEquals(MyChatsResult.NetworkError, repository.observeMyChats().toList().last())
    groups.readFailure = FakeGroupRepository.ReadFailure.UNEXPECTED
    assertEquals(MyChatsResult.UnexpectedError, repository.observeMyChats().toList().last())
    groups.readFailure = null
    groups.currentUid = null
    assertEquals(MyChatsResult.UnexpectedError, repository.observeMyChats().toList().last())
    assertEquals(0, listening)
  }

  @Test
  fun aLatestMessageThatCannotBeRead_endsTheFlowWithItsError_andStopsTheOtherListeners() = runTest {
    val party = chat(PARTY)
    chat(HIKE)

    messages.readFailure = FakeMessageRepository.ReadFailure.NETWORK
    assertEquals(MyChatsResult.NetworkError, repository.observeMyChats().toList().last())
    // Denied chats are left out until a later result from the server still lists them.
    messages.readFailure = FakeMessageRepository.ReadFailure.UNEXPECTED
    val denied = startCollecting()
    anotherStudentJoins(party)
    assertEquals(MyChatsResult.UnexpectedError, denied.last())
    assertEquals(0, listening)
  }

  @Test
  fun aChatThatCannotReachTheServer_endsTheList_withoutWaitingForASilentOne() = runTest {
    val silent = chat(PARTY)
    val unreachable = chat(HIKE)
    val failing = flowOf(LatestMessageResult.Loading, LatestMessageResult.NetworkError)

    val results =
        startCollecting(
            ChatsRepository(groups, messagesWithLatest(silent to SILENT, unreachable to failing))
        )

    assertEquals(listOf(MyChatsResult.Loading, MyChatsResult.NetworkError), results)
    assertEquals(0, listening)
  }

  @Test
  fun aChatTheStudentCannotRead_isLeftOut_untilTheServerStillListsItsGroup() = runTest {
    val readable = chat(PARTY)
    // Like a deleted account still listed in a group: the group shows, its chat is denied.
    deny(HIKE, "group-2")
    val denied = groups.seedGroup(HIKE, listOf(STUDENT))
    messages.seedMessage(HIKE, denied.id, "a", OTHER, "Unreadable", NOON)
    val results = startCollecting()
    assertEquals(
        MyChatsResult.Success(listOf(ChatPreview(readable, null)), fromCache = false),
        results.last(),
    )

    anotherStudentJoins(denied)

    assertEquals(MyChatsResult.UnexpectedError, results.last())
    assertEquals(0, listening)
  }

  @Test
  fun aChatLeftWhileDisconnected_deniedBeforeTheGroupsDropIt_leavesTheOthersListed() = runTest {
    // Left on another device while this one was disconnected: on reconnection, Firestore reports
    // the chat's listener as denied at once, and the groups without it only in a later snapshot.
    val left = chat(PARTY)
    val kept = chat(HIKE)
    val myGroups =
        MutableStateFlow<MyGroupsResult>(
            MyGroupsResult.Success(listOf(left, kept), fromCache = false)
        )
    val leftLatest =
        MutableStateFlow<LatestMessageResult>(LatestMessageResult.Success(null, fromCache = false))
    val results =
        startCollecting(
            ChatsRepository(groupsFrom(myGroups), messagesWithLatest(left to leftLatest))
        )

    leftLatest.value = LatestMessageResult.UnexpectedError
    myGroups.value = MyGroupsResult.Success(listOf(kept), fromCache = false)
    val sent = message(kept, "a", "Still on?", NOON.plusSeconds(60))
    runCurrent()

    assertEquals(
        listOf(
            MyChatsResult.Loading,
            MyChatsResult.Success(
                listOf(ChatPreview(kept, null), ChatPreview(left, null)),
                fromCache = false,
            ),
            MyChatsResult.Success(listOf(ChatPreview(kept, null)), fromCache = false),
            MyChatsResult.Success(listOf(ChatPreview(kept, sent)), fromCache = false),
        ),
        results,
    )
    assertEquals(1, listening)
  }

  @Test
  fun aDeniedChat_isNoFailure_whileTheGroupsAfterItComeOnlyFromTheCache() = runTest {
    val left = chat(PARTY)
    val kept = chat(HIKE)
    val myGroups =
        MutableStateFlow<MyGroupsResult>(
            MyGroupsResult.Success(listOf(left, kept), fromCache = false)
        )
    val leftLatest =
        MutableStateFlow<LatestMessageResult>(LatestMessageResult.Success(null, fromCache = false))
    val results =
        startCollecting(
            ChatsRepository(groupsFrom(myGroups), messagesWithLatest(left to leftLatest))
        )
    // Offline, the groups come from the cache.
    myGroups.value = MyGroupsResult.Success(listOf(left, kept), fromCache = true)

    leftLatest.value = LatestMessageResult.UnexpectedError
    // A member joins, but from the cache: it says nothing about the server yet.
    val joinedByOther = kept.copy(memberUids = kept.memberUids + OTHER)
    myGroups.value = MyGroupsResult.Success(listOf(left, joinedByOther), fromCache = true)
    runCurrent()
    assertEquals(
        MyChatsResult.Success(listOf(ChatPreview(joinedByOther, null)), fromCache = true),
        results.last(),
    )

    myGroups.value = MyGroupsResult.Success(listOf(joinedByOther), fromCache = false)
    runCurrent()

    assertEquals(
        MyChatsResult.Success(listOf(ChatPreview(joinedByOther, null)), fromCache = false),
        results.last(),
    )
    assertTrue(MyChatsResult.UnexpectedError !in results)
  }

  @Test
  fun aDeniedChat_whoseGroupTheServerStillListsAfterwards_failsTheList() = runTest {
    val denied = chat(PARTY)
    val kept = chat(HIKE)
    val myGroups =
        MutableStateFlow<MyGroupsResult>(
            MyGroupsResult.Success(listOf(denied, kept), fromCache = false)
        )
    val deniedLatest =
        MutableStateFlow<LatestMessageResult>(LatestMessageResult.Success(null, fromCache = false))
    val results =
        startCollecting(
            ChatsRepository(groupsFrom(myGroups), messagesWithLatest(denied to deniedLatest))
        )

    deniedLatest.value = LatestMessageResult.UnexpectedError
    // From the cache, the groups say nothing about the server yet.
    myGroups.value = MyGroupsResult.Success(listOf(denied, kept), fromCache = true)
    runCurrent()
    assertEquals(
        MyChatsResult.Success(listOf(ChatPreview(kept, null)), fromCache = true),
        results.last(),
    )

    // The server still lists the student in the group whose chat denied them.
    myGroups.value = MyGroupsResult.Success(listOf(denied, kept), fromCache = false)
    runCurrent()

    assertEquals(MyChatsResult.UnexpectedError, results.last())
    assertEquals(0, listening)
  }

  @Test
  fun aDeniedChat_isNotHiddenBehindAChatThatHasNotAnswered() = runTest {
    val silent = chat(PARTY)
    deny(HIKE, "group-2")
    val denied = groups.seedGroup(HIKE, listOf(STUDENT))
    val results = startCollecting(ChatsRepository(groups, messagesWithLatest(silent to SILENT)))
    assertEquals(listOf<MyChatsResult>(MyChatsResult.Loading), results)

    anotherStudentJoins(denied)

    assertEquals(listOf(MyChatsResult.Loading, MyChatsResult.UnexpectedError), results)
    assertEquals(0, listening)
  }

  @Test
  fun afterAFailure_collectingTheSameFlowAgain_readsTheChats() = runTest {
    // What Retry does on the Chats tab.
    val group = chat(PARTY)
    val chats = repository.observeMyChats()
    groups.readFailure = FakeGroupRepository.ReadFailure.NETWORK
    assertEquals(MyChatsResult.NetworkError, chats.toList().last())
    groups.readFailure = null
    messages.readFailure = FakeMessageRepository.ReadFailure.UNEXPECTED
    val denied = startCollecting(chats)
    anotherStudentJoins(group)
    assertEquals(MyChatsResult.UnexpectedError, denied.last())

    messages.readFailure = null
    assertEquals(
        MyChatsResult.Success(
            listOf(ChatPreview(groups.groupsOf(PARTY).single(), null)),
            fromCache = false,
        ),
        chats.first { it is MyChatsResult.Success },
    )
    assertEquals(0, listening)
  }

  @Test
  fun aFailureAfterTheListShowed_isStillDeliveredLast() = runTest {
    val group = chat(PARTY)
    val failed = CompletableDeferred<Unit>()
    val failingMessages =
        object : MessageRepository by messages {
          override fun observeLatestMessage(
              eventId: String,
              groupId: String,
          ): Flow<LatestMessageResult> = flow {
            emit(LatestMessageResult.Success(null, fromCache = false))
            failed.await()
            emit(LatestMessageResult.UnexpectedError)
          }
        }
    val results = startCollecting(ChatsRepository(groups, failingMessages))
    assertEquals(listOf(ChatPreview(group, null)), results.latest.chats)

    failed.complete(Unit)
    runCurrent()
    // Denied, the chat is left out until the server still lists the student in its group.
    anotherStudentJoins(group)

    assertEquals(MyChatsResult.UnexpectedError, results.last())
  }

  @Test
  fun aSlowCollector_getsOnlyTheLatestResult_andAlwaysTheFinalError() = runTest {
    val group = chat(PARTY)
    val release = CompletableDeferred<Unit>()
    val results = mutableListOf<MyChatsResult>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeMyChats().collect {
        results += it
        // Busy with the first result while every new message and the failure arrive.
        if (results.size == 1) release.await()
      }
    }

    repeat(20) { message(group, "m$it", "Message $it", NOON.plusSeconds(60L + it)) }
    // A group whose chat the student can't read fails the list once the server still lists it.
    deny(HIKE, "group-2")
    val denied = groups.seedGroup(HIKE, listOf(STUDENT))
    anotherStudentJoins(denied)
    release.complete(Unit)
    runCurrent()

    assertEquals(2, results.size)
    assertEquals(MyChatsResult.UnexpectedError, results.last())
    assertEquals(0, listening)
  }

  @Test
  fun cancellingTheCollection_stopsEveryListener() = runTest {
    chat(PARTY)
    chat(HIKE)
    val collecting =
        launch(UnconfinedTestDispatcher(testScheduler)) { repository.observeMyChats().collect {} }
    assertEquals(2, listening)

    collecting.cancel()
    runCurrent()

    assertEquals(0, listening)
  }

  private companion object {
    const val STUDENT = "student"
    const val OTHER = "other"
    const val JOINER = "joiner"
    const val PARTY = "party"
    const val HIKE = "hike"
    const val STUDY = "study"

    /** When the fake group repository creates its first group, one second later. */
    val NOON: Instant = Instant.parse("2030-01-01T12:00:00Z")

    /** A latest-message listener that never answers. */
    val SILENT: Flow<LatestMessageResult> = flow {
      emit(LatestMessageResult.Loading)
      awaitCancellation()
    }
  }
}
