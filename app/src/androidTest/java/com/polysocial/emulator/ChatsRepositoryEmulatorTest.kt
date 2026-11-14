// Contributors: Claude (Fable 5.2, Anthropic) (the chat list against the rules, #363; the open
// list's live changes, an account change and a deleted account, from a test audit).
// Contributors: Claude (Opus 5.5, Anthropic) (a denied chat left out until the server still lists
// its group, after review, #363).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.chat.ChatsRepository
import com.polysocial.model.chat.FirestoreMessageRepository
import com.polysocial.model.chat.MESSAGES_COLLECTION
import com.polysocial.model.chat.MyChatsResult
import com.polysocial.model.chat.SendMessageResult
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.group.FirestoreGroupRepository
import com.polysocial.model.group.GROUPS_COLLECTION
import com.polysocial.model.group.GROUP_CAPACITY
import com.polysocial.model.group.GroupWriteResult
import com.polysocial.model.user.USERS_COLLECTION
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [ChatsRepository] on the real Firestore repositories against the Firestore and Auth
 * emulators, so the collection-group query on the student's groups and each group's latest-message
 * query (#363) run under the real Security Rules. Groups and other members' messages are seeded as
 * an admin would, bypassing the rules. No event is seeded: a member reads their groups and chats
 * without the event, as after it was deleted.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class ChatsRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  private val auth by lazy { EmulatorSignedInUser(emulator) }

  private val messages by lazy {
    FirestoreMessageRepository(db = emulator.firestore, auth = auth, network = AlwaysOnline)
  }

  private val groups by lazy {
    FirestoreGroupRepository(db = emulator.firestore, auth = auth, network = AlwaysOnline)
  }

  private val repository by lazy { ChatsRepository(groups, messages) }

  private suspend fun seedGroup(
      eventId: String,
      groupId: String,
      members: List<String>,
      createdAt: Instant,
  ) =
      emulator.createAsAdmin(
          groupsOf(eventId),
          groupId,
          mapOf(
              "capacity" to GROUP_CAPACITY.toLong(),
              "memberUids" to members,
              "createdBy" to members.first(),
              "createdAt" to createdAt,
          ),
      )

  private suspend fun seedMessage(eventId: String, groupId: String, text: String, at: Instant) =
      emulator.createAsAdmin(
          "${groupsOf(eventId)}/$groupId/$MESSAGES_COLLECTION",
          "message-${at.epochSecond}",
          mapOf("senderId" to OTHER_UID, "text" to text, "createdAt" to at),
      )

  /** The chats once the groups and every latest message have answered from the server. */
  private suspend fun chats(): MyChatsResult =
      withTimeout(TIMEOUT_MS) {
        repository.observeMyChats().first {
          it !is MyChatsResult.Loading && (it !is MyChatsResult.Success || !it.fromCache)
        }
      }

  /** The chats as "event/group: latest text", in the order they are listed. */
  private fun MyChatsResult.listed(): List<String> {
    assertTrue("Expected chats, got $this", this is MyChatsResult.Success)
    return (this as MyChatsResult.Success).chats.map {
      "${it.group.eventId}/${it.group.id}: ${it.latestMessage?.text}"
    }
  }

  /**
   * Waits until an open list shows exactly [expected], in order. A list that failed, or one that
   * never gets there, fails with what it shows instead.
   */
  private suspend fun StateFlow<MyChatsResult>.awaitList(vararg expected: String) {
    val result =
        withTimeoutOrNull(TIMEOUT_MS) {
          first {
            it !is MyChatsResult.Loading &&
                (it !is MyChatsResult.Success || it.listed() == expected.toList())
          }
        }
    assertEquals(expected.toList(), (result ?: value).listed())
  }

  @Test
  fun theStudentsChatsAcrossEvents_newestActivityFirst_withTheirLatestMessage() = runBlocking {
    val uid = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    seedGroup("hike", "busy", listOf(OTHER_UID, uid), createdAt = MORNING)
    seedGroup("party", "quiet", listOf(uid), createdAt = MORNING.plusSeconds(3600))
    seedGroup("party", "theirs", listOf(OTHER_UID), createdAt = MORNING)
    seedMessage("hike", "busy", "First", MORNING.plusSeconds(7200))
    seedMessage("hike", "busy", "Latest", MORNING.plusSeconds(10_800))
    seedMessage("party", "theirs", "Not for the student", MORNING.plusSeconds(14_400))

    assertEquals(listOf("hike/busy: Latest", "party/quiet: null"), chats().listed())

    // A message sent now gets the server's time, after every seeded one.
    assertEquals(
        SendMessageResult.Sent,
        withTimeout(TIMEOUT_MS) { messages.sendMessage("party", "quiet", "Anyone there?") },
    )
    assertEquals(listOf("party/quiet: Anyone there?", "hike/busy: Latest"), chats().listed())
  }

  @Test
  fun theOpenList_followsNewMessages_andTheGroupsJoinedAndLeft() = runBlocking {
    val uid = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    seedGroup("hike", "busy", listOf(OTHER_UID, uid), createdAt = MORNING)
    seedMessage("hike", "busy", "First", MORNING.plusSeconds(3600))
    // As the Chats tab does: one collection, kept open while the chats change.
    val shown = MutableStateFlow<MyChatsResult>(MyChatsResult.Loading)
    val open = launch { repository.observeMyChats().collect { shown.value = it } }
    try {
      shown.awaitList("hike/busy: First")

      seedMessage("hike", "busy", "Second", MORNING.plusSeconds(7200))
      shown.awaitList("hike/busy: Second")

      seedGroup("party", "new", listOf(uid), createdAt = MORNING.plusSeconds(10_800))
      shown.awaitList("party/new: null", "hike/busy: Second")

      // Its last member leaving deletes the group.
      assertEquals(
          GroupWriteResult.Success("new"),
          withTimeout(TIMEOUT_MS) { groups.leaveGroup("party", "new") },
      )
      shown.awaitList("hike/busy: Second")
    } finally {
      open.cancel()
    }
  }

  @Test
  fun anotherAccountSigningIn_endsTheOpenList_andANewOneListsItsOwnChats() = runBlocking {
    val first = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    seedGroup("hike", "busy", listOf(OTHER_UID, first), createdAt = MORNING)
    seedMessage("hike", "busy", "For the first student", MORNING.plusSeconds(3600))
    val shown = MutableStateFlow<MyChatsResult>(MyChatsResult.Loading)
    val open = launch { repository.observeMyChats().collect { shown.value = it } }
    try {
      shown.awaitList("hike/busy: For the first student")

      val second = emulator.signInVerifiedUser(SECOND_STUDENT_EMAIL).uid

      // The rules deny the first student's listeners to anyone else, so their chats stop there.
      val ended = withTimeoutOrNull(TIMEOUT_MS) { open.join() } != null
      assertTrue("The list should have ended, but shows ${shown.value}", ended)
      assertEquals(MyChatsResult.UnexpectedError, shown.value)

      seedGroup("party", "theirs", listOf(second), createdAt = MORNING)
      assertEquals(listOf("party/theirs: null"), chats().listed())
    } finally {
      open.cancel()
    }
  }

  @Test
  fun aDeletedAccountStillListedInAGroup_cannotReadItsChats() = runBlocking {
    val uid = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    seedGroup("hike", "busy", listOf(OTHER_UID, uid), createdAt = MORNING)
    seedMessage("hike", "busy", "Latest", MORNING.plusSeconds(3600))
    assertEquals(listOf("hike/busy: Latest"), chats().listed())

    // deleteAccount writes this marker before it removes the memberships, and the account's ID
    // token stays valid for up to an hour (#241).
    emulator.createAsAdmin("deletedAccounts", uid, emptyMap())

    val shown = MutableStateFlow<MyChatsResult>(MyChatsResult.Loading)
    val seen = mutableListOf<MyChatsResult>()
    val open = launch {
      repository.observeMyChats().collect {
        seen += it
        shown.value = it
      }
    }
    try {
      // The chat is denied, so the list leaves it out...
      withTimeout(TIMEOUT_MS) { shown.first { it !is MyChatsResult.Loading } }
      // ...and fails once the server still lists the account in that group after the denial: here
      // when the account is added to another group.
      if (open.isActive) {
        seedGroup("party", "new", listOf(uid), createdAt = MORNING.plusSeconds(7200))
      }
      val ended = withTimeoutOrNull(TIMEOUT_MS) { open.join() } != null
      assertTrue("The list should have ended, but shows ${shown.value}", ended)
      assertEquals(MyChatsResult.UnexpectedError, shown.value)
      assertTrue(
          "The server let a deleted account read a chat: $seen",
          seen.none { it is MyChatsResult.Success && !it.fromCache && it.chats.isNotEmpty() },
      )
    } finally {
      open.cancel()
    }
  }

  @Test
  fun aStudentInNoGroup_hasNoChats() = runBlocking {
    emulator.signInVerifiedUser(STUDENT_EMAIL)
    seedGroup("party", "theirs", listOf(OTHER_UID), createdAt = MORNING)

    assertEquals(MyChatsResult.Success(emptyList(), fromCache = false), chats())
  }

  @Test
  fun anAssociation_hasNoChats_evenListedInAGroup() = runBlocking {
    val uid = emulator.signInVerifiedUser(ASSOCIATION_EMAIL).uid
    emulator.createAsAdmin(
        USERS_COLLECTION,
        uid,
        mapOf("uid" to uid, "accountType" to "association", "isAssociationVerified" to true),
    )
    seedGroup("party", "group", listOf(OTHER_UID, uid), createdAt = MORNING)

    assertEquals(MyChatsResult.UnexpectedError, chats())
  }

  @Test
  fun offline_theChatsComeFromTheCache() = runBlocking {
    val uid = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    seedGroup("hike", "busy", listOf(OTHER_UID, uid), createdAt = MORNING)
    seedGroup("party", "quiet", listOf(uid), createdAt = MORNING.plusSeconds(3600))
    seedMessage("hike", "busy", "Latest", MORNING.plusSeconds(7200))
    // As in the app: the Chats tab stays open, so the in-memory cache keeps what it listed.
    val open = launch { repository.observeMyChats().collect {} }
    val cached =
        try {
          chats()
          whileOffline(emulator.firestore.document("${groupsOf("hike")}/busy")) {
            // Firestore notices it is offline some seconds after Android does; until then, its
            // results still count as from the server. Wait for the first list from the cache.
            withTimeout(OFFLINE_TIMEOUT_MS) {
              repository.observeMyChats().first {
                it !is MyChatsResult.Loading && (it !is MyChatsResult.Success || it.fromCache)
              }
            }
          }
        } finally {
          open.cancel()
        }

    assertEquals(listOf("hike/busy: Latest", "party/quiet: null"), cached.listed())
  }

  private fun groupsOf(eventId: String) = "$EVENTS_COLLECTION/$eventId/$GROUPS_COLLECTION"

  private companion object {
    /** Fictional accounts: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val SECOND_STUDENT_EMAIL = "other.student@epfl.ch"
    const val ASSOCIATION_EMAIL = "test.association@example.com"
    const val OTHER_UID = "fictional-student-1"
    const val TIMEOUT_MS = 15_000L
    const val OFFLINE_TIMEOUT_MS = 30_000L
    val MORNING: Instant = Instant.parse("2026-01-01T08:00:00Z")
  }
}
