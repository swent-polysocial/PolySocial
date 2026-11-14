// Contributors: Claude (Fable 5.2, Anthropic) (the chat list against the rules, #363).
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
import com.polysocial.model.user.USERS_COLLECTION
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

  private val repository by lazy {
    ChatsRepository(
        FirestoreGroupRepository(db = emulator.firestore, auth = auth, network = AlwaysOnline),
        messages,
    )
  }

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
    const val ASSOCIATION_EMAIL = "test.association@example.com"
    const val OTHER_UID = "fictional-student-1"
    const val TIMEOUT_MS = 15_000L
    const val OFFLINE_TIMEOUT_MS = 30_000L
    val MORNING: Instant = Instant.parse("2026-01-01T08:00:00Z")
  }
}
