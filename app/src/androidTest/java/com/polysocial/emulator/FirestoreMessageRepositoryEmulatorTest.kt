// Contributors: Claude (Opus 5.5, Anthropic) (group chat repository tests against the rules, #237).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.chat.FirestoreMessageRepository
import com.polysocial.model.chat.MESSAGES_COLLECTION
import com.polysocial.model.chat.Message
import com.polysocial.model.chat.MessagesResult
import com.polysocial.model.chat.SendMessageResult
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.toFirestoreMap
import com.polysocial.model.group.GROUPS_COLLECTION
import com.polysocial.model.group.GROUP_CAPACITY
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreMessageRepository] against the Firestore and Auth emulators, so the message rules
 * (#237) and the server's ordering run on the real Firestore. The event, the group and other
 * members' messages are seeded as an admin would, bypassing the rules.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestoreMessageRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

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
      withTimeout(TIMEOUT_MS) {
        repository.observeMessages(EVENT, GROUP).first {
          it !is MessagesResult.Loading && (it !is MessagesResult.Success || !it.fromCache)
        }
      }

  private suspend fun messages(): List<Message> {
    val result = chat()
    assertTrue("Expected messages, got $result", result is MessagesResult.Success)
    return (result as MessagesResult.Success).messages
  }

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
  fun aStudentOutsideTheGroup_cannotReadOrSend() = runBlocking {
    val member = signIn(STUDENT_EMAIL)
    seedGroup(listOf(member))
    seedMessage("hello", member, "Hello", Instant.parse("2026-01-01T10:00:00Z"))
    signIn(OUTSIDER_EMAIL)

    assertEquals(MessagesResult.UnexpectedError, chat())
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
