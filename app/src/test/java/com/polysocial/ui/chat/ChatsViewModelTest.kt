// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #364; one-to-one chats in the
// list, #432; one-to-one chats that don't answer in time, from #521's review).
package com.polysocial.ui.chat

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.chat.ChatsRepository
import com.polysocial.model.chat.FakeMessageRepository
import com.polysocial.model.chat.FakeOneToOneChatRepository
import com.polysocial.model.chat.MyOneToOneChatsResult
import com.polysocial.model.chat.OneToOneChatRepository
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.friend.FakeFriendRepository
import com.polysocial.model.friend.friendshipId
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.MyGroupsResult
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** [ChatsViewModel] on the group, message, event and profile fakes (#364). */
class ChatsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val groups = FakeGroupRepository(currentUid = ME)
  private val messages =
      FakeMessageRepository(currentUid = ME).apply { membersOf = groups::memberUidsOf }
  private val events = FakeEventRepository(currentUid = ME)
  private val profiles =
      FakePublicProfileRepository(
          listOf(
                  PublicProfile("lea", "Léa Martin", "IN", "BA3"),
                  PublicProfile("tom", "Tom Rossi", "SC", "BA3"),
              )
              .associateBy { it.uid }
              .toMutableMap()
      )
  private val network = FakeNetworkMonitor()
  private val friends = FakeFriendRepository(currentUid = ME)
  private val oneToOne =
      FakeOneToOneChatRepository(currentUid = ME).apply { friendshipOf = friends::friendship }

  /** 2030-01-02, 12:00 in Lausanne, a day after the fake groups' creation times. */
  private val clock = SettableClock(Instant.parse("2030-01-02T11:00:00Z"))

  init {
    events.seed(
        validEvent(title = "Balélec warm-up").copy(id = "party", category = EventCategory.PARTY),
        validEvent(title = "Climbing session").copy(id = "climb", category = EventCategory.SPORTS),
    )
  }

  private fun viewModel(
      groupRepository: GroupRepository = groups,
      oneToOneRepository: OneToOneChatRepository = oneToOne,
  ) =
      ChatsViewModel(
          ChatsRepository(groupRepository, messages),
          oneToOneRepository,
          events,
          profiles,
          FakeAuthRepository(AuthUser(ME, "me@epfl.ch", true)),
          network,
          clock,
      )

  private fun runPending() = mainDispatcherRule.dispatcher.scheduler.runCurrent()

  private fun ChatsViewModel.rows() =
      (state.value as ChatsUiState.Chats).rows.filterIsInstance<ChatRow>()

  @Test
  fun eachChat_hasItsEventMembersAndLatestMessage_newestFirst() {
    val party = groups.seedGroup("party", listOf(ME, "lea", "tom"))
    groups.seedGroup("climb", listOf("lea", ME))
    messages.seedMessage("party", party.id, "m1", "lea", "I'm at the entrance", NOON)

    val viewModel = viewModel()
    runPending()

    assertEquals(
        listOf(
            ChatRow(
                eventId = "party",
                groupId = party.id,
                event = ChatEvent.Known("Balélec warm-up", EventCategory.PARTY),
                otherNames = listOf("Léa", "Tom"),
                otherCount = 2,
                latest =
                    LatestMessage(
                        Sender.Named("Léa"),
                        "I'm at the entrance",
                        ChatTime.Today(LocalTime.of(12, 0)),
                    ),
            ),
            ChatRow(
                eventId = "climb",
                groupId = "group-2",
                event = ChatEvent.Known("Climbing session", EventCategory.SPORTS),
                otherNames = listOf("Léa"),
                otherCount = 1,
                latest = null,
            ),
        ),
        viewModel.rows(),
    )
    assertFalse((viewModel.state.value as ChatsUiState.Chats).isOffline)
  }

  @Test
  fun theLatestSender_isYou_aDeletedAccount_orUnnamedWithoutAProfile() {
    val mine = groups.seedGroup("party", listOf(ME, "lea"))
    val deleted = groups.seedGroup("climb", listOf(ME))
    val stranger = groups.seedGroup("party", listOf(ME, "ghost"))
    messages.seedMessage("party", mine.id, "m1", ME, "On my way", NOON)
    messages.seedMessage("climb", deleted.id, "m2", "", "Old message", NOON.minusSeconds(60))
    messages.seedMessage("party", stranger.id, "m3", "ghost", "Hi", NOON.minusSeconds(120))

    val viewModel = viewModel()
    runPending()

    val senders = viewModel.rows().associate { it.groupId to it.latest?.sender }
    assertEquals(Sender.You, senders[mine.id])
    assertEquals(Sender.DeletedAccount, senders[deleted.id])
    assertEquals(Sender.Unknown, senders[stranger.id])
    // Without a profile, the other member counts but has no name.
    val unnamed = viewModel.rows().single { it.groupId == stranger.id }
    assertEquals(emptyList<String>() to 1, unnamed.otherNames to unnamed.otherCount)
  }

  @Test
  fun aChatWhoseEventIsGone_isUnavailable() {
    groups.seedGroup("deleted-event", listOf(ME))

    val viewModel = viewModel()
    runPending()

    assertEquals(ChatEvent.Unavailable, viewModel.rows().single().event)
  }

  @Test
  fun aStudentInNoGroup_hasAnEmptyList() {
    val viewModel = viewModel()
    runPending()

    assertEquals(ChatsUiState.Chats(emptyList(), isOffline = false), viewModel.state.value)
  }

  @Test
  fun chatsThatDontAnswerInTime_showTheError_andRetryReadsThemAgain() {
    var silent = true
    val sometimesSilent =
        object : GroupRepository by groups {
          override fun observeMyGroups(): Flow<MyGroupsResult> =
              if (silent)
                  flow {
                    emit(MyGroupsResult.Loading)
                    awaitCancellation()
                  }
              else groups.observeMyGroups()
        }
    groups.seedGroup("party", listOf(ME))
    val viewModel = viewModel(sometimesSilent)
    runPending()
    assertEquals(ChatsUiState.Loading, viewModel.state.value)

    mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(
        ChatsViewModel.LOAD_TIMEOUT.inWholeMilliseconds + 1
    )
    assertEquals(ChatsUiState.Error, viewModel.state.value)

    silent = false
    viewModel.retry()
    assertEquals(ChatsUiState.Loading, viewModel.state.value)
    runPending()
    assertEquals(1, viewModel.rows().size)
  }

  @Test
  fun chatsThatCantBeRead_showTheError_andRetryWorksOnceTheyCan() {
    groups.seedGroup("party", listOf(ME))
    groups.readFailure = FakeGroupRepository.ReadFailure.NETWORK
    val viewModel = viewModel()
    runPending()
    assertEquals(ChatsUiState.Error, viewModel.state.value)

    groups.readFailure = null
    viewModel.retry()
    runPending()

    assertEquals(1, viewModel.rows().size)
  }

  @Test
  fun anUnexpectedFailure_showsTheErrorToo_andStopsReadingTheEvents() {
    groups.seedGroup("party", listOf(ME))
    groups.readFailure = FakeGroupRepository.ReadFailure.UNEXPECTED

    val viewModel = viewModel()
    runPending()

    assertEquals(ChatsUiState.Error, viewModel.state.value)
    // A later event change doesn't bring a list back.
    events.remove("party")
    runPending()
    assertEquals(ChatsUiState.Error, viewModel.state.value)
  }

  @Test
  fun retry_doesNothingWhileTheListShows() {
    groups.seedGroup("party", listOf(ME, "lea"))
    val viewModel = viewModel()
    runPending()
    val shown = viewModel.state.value

    viewModel.retry()

    assertEquals(shown, viewModel.state.value)
    assertEquals(1, profiles.requestedUids.size)
  }

  @Test
  fun aListFromTheCacheWhileOffline_showsTheBanner_butNotWhileOnline() {
    groups.seedGroup("party", listOf(ME))
    groups.readFromCache = true
    network.online = false
    assertTrue((viewModel().also { runPending() }.state.value as ChatsUiState.Chats).isOffline)

    network.online = true
    assertFalse((viewModel().also { runPending() }.state.value as ChatsUiState.Chats).isOffline)
  }

  @Test
  fun offlineWithNoChatSaved_saysSo_insteadOfNoGroup_andRetryReadsAgain() {
    groups.readFromCache = true
    network.online = false
    val viewModel = viewModel()
    runPending()
    assertEquals(ChatsUiState.OfflineNothingSaved, viewModel.state.value)

    groups.readFromCache = false
    network.online = true
    groups.seedGroup("party", listOf(ME))
    viewModel.retry()
    runPending()

    assertEquals(1, viewModel.rows().size)
  }

  @Test
  fun eachStudentsProfile_isReadOnce_andAgainOnlyAfterAFailure() {
    val party = groups.seedGroup("party", listOf(ME, "lea"))
    profiles.failure = FakePublicProfileRepository.Failure.Offline
    val viewModel = viewModel()
    runPending()
    assertEquals(emptyList<String>(), viewModel.rows().single().otherNames)

    profiles.failure = null
    messages.seedMessage("party", party.id, "m1", "lea", "Hello", NOON)
    runPending()
    messages.seedMessage("party", party.id, "m2", "lea", "Anyone?", NOON.plusSeconds(60))
    runPending()

    assertEquals(listOf("Léa"), viewModel.rows().single().otherNames)
    assertEquals(listOf(listOf("lea"), listOf("lea")), profiles.requestedUids)
  }

  @Test
  fun anEventChangingItsTitle_updatesTheRow() {
    groups.seedGroup("party", listOf(ME))
    val viewModel = viewModel()
    runPending()

    events.remove("party")
    events.seed(
        validEvent(title = "Balélec after-party").copy(id = "party", category = EventCategory.PARTY)
    )
    runPending()

    assertEquals(
        ChatEvent.Known("Balélec after-party", EventCategory.PARTY),
        viewModel.rows().single().event,
    )
  }

  @Test
  fun atMidnight_todaysTimeTurnsIntoYesterday() {
    // 23:00 in Lausanne; the message was sent at 22:58.
    clock.now = Instant.parse("2030-01-02T22:00:00Z")
    val party = groups.seedGroup("party", listOf(ME))
    messages.seedMessage(
        "party",
        party.id,
        "m1",
        ME,
        "Good night",
        Instant.parse("2030-01-02T21:58:00Z"),
    )
    val viewModel = viewModel()
    runPending()
    assertEquals(ChatTime.Today(LocalTime.of(22, 58)), viewModel.rows().single().latest?.time)

    clock.now = Instant.parse("2030-01-02T23:00:01Z")
    mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(60 * 60 * 1000L + 1)

    assertEquals(ChatTime.Yesterday, viewModel.rows().single().latest?.time)
  }

  // ---- One-to-one chats in the list (#432) ----

  private val leaChat = checkNotNull(friendshipId(ME, "lea"))
  private val tomChat = checkNotNull(friendshipId(ME, "tom"))

  private fun ChatsViewModel.allRows() = (state.value as ChatsUiState.Chats).rows

  private fun ChatsViewModel.oneToOneRows() = allRows().filterIsInstance<OneToOneChatRow>()

  @Test
  fun groupAndOneToOneChats_shareOneList_newestActivityFirst() {
    val party = groups.seedGroup("party", listOf(ME, "lea"))
    groups.seedGroup("climb", listOf(ME, "tom")) // no message: its creation time, a day earlier
    messages.seedMessage("party", party.id, "m1", "lea", "Group news", NOON.minusSeconds(60))
    oneToOne.seedChat(leaChat)
    oneToOne.seedMessage(leaChat, "p1", "lea", "Newest", NOON)
    oneToOne.seedChat(tomChat)
    oneToOne.seedMessage(tomChat, "p2", "tom", "Oldest message", NOON.minusSeconds(3_600))

    val viewModel = viewModel()
    runPending()

    assertEquals(
        listOf("pair/$leaChat", "group/${party.id}", "pair/$tomChat", "group/group-2"),
        viewModel.allRows().map {
          when (it) {
            is ChatRow -> "group/${it.groupId}"
            is OneToOneChatRow -> "pair/${it.pairId}"
          }
        },
    )
  }

  @Test
  fun aOneToOneRow_namesTheFriend_andPrefixesOnlyTheStudentsOwnMessage() {
    oneToOne.seedChat(leaChat)
    oneToOne.seedMessage(leaChat, "p1", ME, "On my way, 5 min", NOON)
    oneToOne.seedChat(tomChat)
    oneToOne.seedMessage(tomChat, "p2", "tom", "Thanks!", NOON.minusSeconds(60))

    val viewModel = viewModel()
    runPending()

    assertEquals(
        listOf(
            OneToOneChatRow(
                leaChat,
                ChatListFriend.Named("Léa Martin"),
                LatestMessage(Sender.You, "On my way, 5 min", ChatTime.Today(LocalTime.of(12, 0))),
            ),
            OneToOneChatRow(
                tomChat,
                ChatListFriend.Named("Tom Rossi"),
                LatestMessage(Sender.Unknown, "Thanks!", ChatTime.Today(LocalTime.of(11, 59))),
            ),
        ),
        viewModel.oneToOneRows(),
    )
  }

  @Test
  fun aDeletedAccount_isNamedSo_andAnUnreadableProfile_stillShowsItsRow() {
    oneToOne.seedChat(leaChat, uids = listOf(ME))
    oneToOne.seedMessage(leaChat, "p1", "", "Bye", NOON)
    val ghostChat = checkNotNull(friendshipId(ME, "ghost"))
    oneToOne.seedChat(ghostChat)
    oneToOne.seedMessage(ghostChat, "p2", "ghost", "Hi", NOON.minusSeconds(60))

    val viewModel = viewModel()
    runPending()

    assertEquals(
        listOf(ChatListFriend.Deleted, ChatListFriend.Unavailable),
        viewModel.oneToOneRows().map { it.friend },
    )
  }

  @Test
  fun aFriendsProfileThatFailsToLoad_saysSo_andTheNextListReadsItAgain() {
    oneToOne.seedChat(leaChat)
    oneToOne.seedMessage(leaChat, "p1", "lea", "Hi", NOON)
    profiles.failure = FakePublicProfileRepository.Failure.Unexpected
    val viewModel = viewModel()
    runPending()
    assertEquals(ChatListFriend.Unavailable, viewModel.oneToOneRows().single().friend)

    profiles.failure = null
    oneToOne.seedMessage(leaChat, "p2", ME, "Still there?", NOON.plusSeconds(60))
    runPending()

    assertEquals(ChatListFriend.Named("Léa Martin"), viewModel.oneToOneRows().single().friend)
  }

  @Test
  fun aOneToOneChatWithoutAMessage_isNotListed() {
    oneToOne.seedChat(leaChat)

    val viewModel = viewModel()
    runPending()

    assertEquals(ChatsUiState.Chats(emptyList(), isOffline = false), viewModel.state.value)
  }

  @Test
  fun oneToOneChatsThatCantBeRead_leaveTheGroupsListed_andRetryReadsBoth() {
    groups.seedGroup("party", listOf(ME))
    oneToOne.seedChat(leaChat)
    oneToOne.seedMessage(leaChat, "p1", "lea", "Hi", NOON)
    oneToOne.readFailure = FakeMessageRepository.ReadFailure.UNEXPECTED
    val viewModel = viewModel()
    runPending()

    val partial = viewModel.state.value as ChatsUiState.Chats
    assertTrue(partial.partlyFailed)
    assertEquals(1, viewModel.rows().size)
    assertEquals(emptyList<OneToOneChatRow>(), viewModel.oneToOneRows())

    oneToOne.readFailure = null
    viewModel.retry()
    runPending()

    assertFalse((viewModel.state.value as ChatsUiState.Chats).partlyFailed)
    assertEquals(2, viewModel.allRows().size)
  }

  @Test
  fun groupChatsThatCantBeRead_leaveTheOneToOneChatsListed() {
    groups.readFailure = FakeGroupRepository.ReadFailure.NETWORK
    oneToOne.seedChat(leaChat)
    oneToOne.seedMessage(leaChat, "p1", "lea", "Hi", NOON)

    val viewModel = viewModel()
    runPending()

    val partial = viewModel.state.value as ChatsUiState.Chats
    assertTrue(partial.partlyFailed)
    assertEquals(listOf(leaChat), viewModel.oneToOneRows().map { it.pairId })
  }

  @Test
  fun oneToOneChatsThatDontAnswerInTime_leaveTheGroupsListed_andRetryReadsThemAgain() {
    var silent = true
    val sometimesSilent =
        object : OneToOneChatRepository by oneToOne {
          override fun observeMyChats(): Flow<MyOneToOneChatsResult> =
              if (silent)
                  flow {
                    emit(MyOneToOneChatsResult.Loading)
                    awaitCancellation()
                  }
              else oneToOne.observeMyChats()
        }
    groups.seedGroup("party", listOf(ME))
    oneToOne.seedChat(leaChat)
    oneToOne.seedMessage(leaChat, "p1", "lea", "Hi", NOON)
    val viewModel = viewModel(oneToOneRepository = sometimesSilent)
    runPending()
    assertEquals(ChatsUiState.Loading, viewModel.state.value)

    mainDispatcherRule.dispatcher.scheduler.advanceTimeBy(
        ChatsViewModel.LOAD_TIMEOUT.inWholeMilliseconds + 1
    )
    val partial = viewModel.state.value as ChatsUiState.Chats
    assertTrue(partial.partlyFailed)
    assertEquals(1, viewModel.rows().size)
    assertEquals(emptyList<OneToOneChatRow>(), viewModel.oneToOneRows())

    silent = false
    viewModel.retry()
    runPending()

    assertFalse((viewModel.state.value as ChatsUiState.Chats).partlyFailed)
    assertEquals(listOf(leaChat), viewModel.oneToOneRows().map { it.pairId })
  }

  @Test
  fun bothKindsFailing_isTheError() {
    groups.readFailure = FakeGroupRepository.ReadFailure.NETWORK
    oneToOne.readFailure = FakeMessageRepository.ReadFailure.NETWORK

    val viewModel = viewModel()
    runPending()

    assertEquals(ChatsUiState.Error, viewModel.state.value)
  }

  @Test
  fun offline_theCachedOneToOneChatsShow_withTheBanner() {
    oneToOne.seedChat(leaChat)
    oneToOne.seedMessage(leaChat, "p1", "lea", "Saved", NOON)
    oneToOne.readFromCache = true
    network.online = false

    val viewModel = viewModel()
    runPending()

    val chats = viewModel.state.value as ChatsUiState.Chats
    assertTrue(chats.isOffline)
    assertEquals(1, chats.rows.size)
  }

  /** A clock tests move by hand, in Lausanne. */
  private class SettableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("Europe/Zurich")

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
  }

  private companion object {
    const val ME = "me"

    /** 12:00 in Lausanne on the clock's day. */
    val NOON: Instant = Instant.parse("2030-01-02T11:00:00Z")
  }
}
