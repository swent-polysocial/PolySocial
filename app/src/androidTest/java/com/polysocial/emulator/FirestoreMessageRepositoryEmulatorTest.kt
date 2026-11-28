// Contributors: Claude (Opus 5.5, Anthropic) (group chat repository tests against the rules, #237).
// Contributors: OpenAI Codex (verified same-ID retry against Firestore rules, #238).
// Contributors: Claude (Fable 5.2, Anthropic) (a group's latest message, #363).
// Contributors: Claude (Opus 5.5, Anthropic) (unsend against the real rules, #439).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.chat.FirestoreMessageRepository
import com.polysocial.model.chat.LatestMessageResult
import com.polysocial.model.chat.MESSAGES_COLLECTION
import com.polysocial.model.chat.Message
import com.polysocial.model.chat.MessagesResult
import com.polysocial.model.chat.SendMessageResult
import com.polysocial.model.chat.UnsendMessageResult
import com.polysocial.model.chat.isUnsent
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.toFirestoreMap
import com.polysocial.model.group.GROUPS_COLLECTION
import com.polysocial.model.group.GROUP_CAPACITY
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreMessageRepository] against the Firestore and Auth emulators, so the message rules
 * (#237) and the server's ordering run on the real Firestore. The event, the group and other
 * members' messages are seeded as an admin would, bypassing the rules. For unsend (#439), another
 * member signs in on one of [StudentApps], so both are signed in at once.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestoreMessageRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  @After fun signOutTheOtherMember() = StudentApps.signOutAll()

  /** Always online, so the offline test reaches the transaction instead of the network check. */
  private val repository by lazy {
    FirestoreMessageRepository(
        db = emulator.firestore,
        auth = EmulatorSignedInUser(emulator),
        network = AlwaysOnline,
    )
  }

  private suspend fun signIn(email: String): String = emulator.signInVerifiedUser(email).uid

  /** Stores a public event with the group [GROUP], whose members are [members]. */
  private suspend fun seedGroup(members: List<String>) {
    emulator.createAsAdmin(EVENTS_COLLECTION, EVENT, event().toFirestoreMap())
    storeGroup(members)
  }

  /** Stores the group [GROUP] with [members], as an admin would. */
  private suspend fun storeGroup(members: List<String>) {
    emulator.createAsAdmin(
        "$EVENTS_COLLECTION/$EVENT/$GROUPS_COLLECTION",
        GROUP,
        mapOf(
            "capacity" to GROUP_CAPACITY.toLong(),
            "memberUids" to members,
            "createdBy" to members.first(),
            "createdAt" to Instant.parse("2026-01-01T09:00:00Z"),
        ),
    )
  }

  private suspend fun seedMessage(id: String, senderId: String, text: String, at: Instant) =
      emulator.createAsAdmin(
          "$EVENTS_COLLECTION/$EVENT/$GROUPS_COLLECTION/$GROUP/$MESSAGES_COLLECTION",
          id,
          mapOf("senderId" to senderId, "text" to text, "createdAt" to at),
      )

  /** The group's chat, as the server first reports it through the repository. */
  private suspend fun chat(): MessagesResult =
      repository.observeMessages(EVENT, GROUP).firstFromServer()

  /** The group's latest message, as the server first reports it through the repository. */
  private suspend fun latest(): LatestMessageResult =
      repository.observeLatestMessage(EVENT, GROUP).firstFromServer()

  private suspend fun messages(): List<Message> =
      repository.observeMessages(EVENT, GROUP).messagesFromServer()

  private suspend fun send(text: String) =
      withTimeout(TIMEOUT_MS) { repository.sendMessage(EVENT, GROUP, text) }

  @Test
  fun aMember_sendsMessages_andSeesTheChatInTheServersTimeOrder() = runBlocking {
    val uid = signIn(STUDENT_EMAIL)
    seedGroup(listOf(OTHER_UID, uid))
    seedMessage("earlier", OTHER_UID, "Who's coming?", Instant.parse("2026-01-01T10:00:00Z"))

    assertEquals(SendMessageResult.Sent, send("  Me!\n"))
    assertEquals(SendMessageResult.Sent, send("See you at 6"))

    val chat = messages()
    assertEquals(listOf("Who's coming?", "Me!", "See you at 6"), chat.map { it.text })
    assertEquals(listOf(OTHER_UID, uid, uid), chat.map { it.senderId })
    assertTrue(
        "Expected the server's times in order: ${chat.map { it.createdAt }}",
        chat.zipWithNext().all { (first, next) -> first.createdAt < next.createdAt },
    )
  }

  @Test
  fun observeLatestMessage_isOnlyTheNewestMessage_orNoneBeforeTheFirst() = runBlocking {
    val uid = signIn(STUDENT_EMAIL)
    seedGroup(listOf(OTHER_UID, uid))
    assertEquals(LatestMessageResult.Success(null, fromCache = false), latest())

    seedMessage("newer", OTHER_UID, "See you there", Instant.parse("2026-01-01T11:00:00Z"))
    seedMessage("older", OTHER_UID, "Who's coming?", Instant.parse("2026-01-01T10:00:00Z"))
    assertEquals(
        LatestMessageResult.Success(
            Message("newer", OTHER_UID, "See you there", Instant.parse("2026-01-01T11:00:00Z")),
            fromCache = false,
        ),
        latest(),
    )

    // The server's time puts a message sent now after the seeded ones.
    assertEquals(SendMessageResult.Sent, send("On my way"))
    val sent = (latest() as LatestMessageResult.Success).message
    assertEquals("On my way", sent?.text)
    assertEquals(uid, sent?.senderId)
  }

  @Test
  fun retryingAStoredDraftKeepsOneMessageWithTheOriginalText() = runBlocking {
    val uid = signIn(STUDENT_EMAIL)
    seedGroup(listOf(uid))
    val result =
        withTimeout(TIMEOUT_MS) {
          listOf(
              repository.sendMessage(EVENT, GROUP, "Meet at six", "retry-draft"),
              repository.sendMessage(EVENT, GROUP, " Meet at six ", "retry-draft"),
              repository.sendMessage(EVENT, GROUP, "Different text", "retry-draft"),
          )
        }

    assertEquals(
        listOf(SendMessageResult.Sent, SendMessageResult.Sent, SendMessageResult.UnexpectedError),
        result,
    )
    val message = messages().single()
    assertEquals("retry-draft", message.id)
    assertEquals("Meet at six", message.text)
    assertEquals(uid, message.senderId)
  }

  // unsendMessage (#439)

  private val otherApp
    get() = StudentApps.all.first()

  /** The other member's repository, on their own signed-in app. */
  private val others by lazy {
    FirestoreMessageRepository(
        db = otherApp.firestore,
        auth = EmulatorSignedInUser(emulator, otherApp.auth),
        network = AlwaysOnline,
    )
  }

  private suspend fun signInOtherMember(): String =
      emulator.signInVerifiedUser(OUTSIDER_EMAIL, auth = otherApp.auth).uid

  private suspend fun unsend(id: String) =
      withTimeout(TIMEOUT_MS) { repository.unsendMessage(EVENT, GROUP, id) }

  @Test
  fun unsending_emptiesTheText_andAnotherMembersChatAndLatestMessageReceiveIt() = runBlocking {
    val uid = signIn(STUDENT_EMAIL)
    val other = signInOtherMember()
    seedGroup(listOf(other, uid))
    seedMessage("theirs", other, "Who's coming?", Instant.parse("2026-01-01T10:00:00Z"))
    assertEquals(
        SendMessageResult.Sent,
        withTimeout(TIMEOUT_MS) { repository.sendMessage(EVENT, GROUP, "Me!", DRAFT) },
    )
    // The other member's chat and Chats row are open on the text before it is unsent.
    val shown = CompletableDeferred<Unit>()
    val unsentInChat = async {
      others.observeMessages(EVENT, GROUP).first { result ->
        val messages = (result as? MessagesResult.Success)?.messages.orEmpty()
        if (messages.any { it.text == "Me!" }) shown.complete(Unit)
        messages.any { it.isUnsent }
      }
    }
    val unsentAsLatest = async {
      others.observeLatestMessage(EVENT, GROUP).first {
        (it as? LatestMessageResult.Success)?.message?.isUnsent == true
      }
    }
    withTimeout(TIMEOUT_MS) { shown.await() }

    assertEquals(UnsendMessageResult.Unsent, unsend(DRAFT))

    val received = (withTimeout(TIMEOUT_MS) { unsentInChat.await() } as MessagesResult.Success)
    assertEquals(listOf("Who's coming?", ""), received.messages.map { it.text })
    assertEquals(DRAFT, received.messages.last().id)
    assertEquals(uid, received.messages.last().senderId)
    val latest = withTimeout(TIMEOUT_MS) { unsentAsLatest.await() } as LatestMessageResult.Success
    assertEquals(DRAFT, latest.message?.id)
    // A retry reads it unsent and writes nothing.
    assertEquals(UnsendMessageResult.Unsent, unsend(DRAFT))
  }

  @Test
  fun unsendingAnotherMembersMessage_orAfterLeaving_isUnexpected_andChangesNothing() = runBlocking {
    val uid = signIn(STUDENT_EMAIL)
    seedGroup(listOf(OTHER_UID, uid))
    seedMessage("theirs", OTHER_UID, "Who's coming?", Instant.parse("2026-01-01T10:00:00Z"))
    seedMessage("mine", uid, "Me!", Instant.parse("2026-01-01T10:01:00Z"))

    assertEquals(UnsendMessageResult.UnexpectedError, unsend("theirs"))

    // Out of the group, the rules refuse even the read.
    emulator.deleteAsAdmin("$EVENTS_COLLECTION/$EVENT/$GROUPS_COLLECTION", GROUP)
    storeGroup(listOf(OTHER_UID))
    assertEquals(UnsendMessageResult.UnexpectedError, unsend("mine"))

    // Back in the group, both messages are unchanged.
    emulator.deleteAsAdmin("$EVENTS_COLLECTION/$EVENT/$GROUPS_COLLECTION", GROUP)
    storeGroup(listOf(OTHER_UID, uid))
    assertEquals(listOf("Who's coming?", "Me!"), messages().map { it.text })
  }

  @Test
  fun aStudentOutsideTheGroup_cannotReadOrSend() = runBlocking {
    val member = signIn(STUDENT_EMAIL)
    seedGroup(listOf(member))
    seedMessage("hello", member, "Hello", Instant.parse("2026-01-01T10:00:00Z"))
    signIn(OUTSIDER_EMAIL)

    assertEquals(MessagesResult.UnexpectedError, chat())
    assertEquals(LatestMessageResult.UnexpectedError, latest())
    assertEquals(SendMessageResult.UnexpectedError, send("Can I join?"))

    // Back as the member: the outsider's message was never stored.
    emulator.auth.signInWithEmailAndPassword(STUDENT_EMAIL, FirebaseEmulator.TEST_PASSWORD).await()
    assertEquals(listOf("Hello"), messages().map { it.text })
  }

  @Test
  fun sendMessage_offline_isANetworkErrorAndQueuesNothing() = runBlocking {
    val uid = signIn(STUDENT_EMAIL)
    seedGroup(listOf(uid))
    val group =
        emulator.firestore
            .collection(EVENTS_COLLECTION)
            .document(EVENT)
            .collection(GROUPS_COLLECTION)
            .document(GROUP)

    // The network check says online, so this is the transaction failing, not being queued. It
    // fails once Firestore gives up on the server, about 8 s into airplane mode (#119).
    val result =
        whileOffline(group) {
          withTimeout(OFFLINE_TIMEOUT_MS) {
            repository.sendMessage(EVENT, GROUP, "Sent while offline")
          }
        }

    assertEquals(SendMessageResult.NetworkError, result)
    val chat = messages()
    assertFalse("Nothing should be sent later: $chat", chat.any { it.text == "Sent while offline" })
  }

  private companion object {
    /** Fictional accounts and events: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val OUTSIDER_EMAIL = "other.student@epfl.ch"
    const val OTHER_UID = "fictional-student-1"
    const val ORGANIZER_UID = "fictional-organizer"
    const val EVENT = "public"
    const val GROUP = "group"
    const val DRAFT = "draft-1"
    const val TIMEOUT_MS = 15_000L
    const val OFFLINE_TIMEOUT_MS = 30_000L

    fun event() =
        Event(
            title = "Test event",
            description = "A fictional event.",
            category = EventCategory.STUDY,
            location = Coordinates(46.5191, 6.5668),
            startTime = Instant.parse("2030-03-02T18:00:00Z"),
            isPrivate = false,
            createdBy = ORGANIZER_UID,
            organizerIds = listOf(ORGANIZER_UID),
            allowedUids = listOf(ORGANIZER_UID),
        )
  }
}
