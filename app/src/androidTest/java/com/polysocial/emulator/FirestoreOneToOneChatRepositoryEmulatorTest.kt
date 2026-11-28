// Contributors: Claude
// Contributors: Claude (Opus 5.5, Anthropic) (unsend against the real rules, #435).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.Source
import com.polysocial.model.chat.CHATS_COLLECTION
import com.polysocial.model.chat.DELETED_MESSAGE_SENDER
import com.polysocial.model.chat.FirestoreOneToOneChatRepository
import com.polysocial.model.chat.LatestMessageResult
import com.polysocial.model.chat.MESSAGES_COLLECTION
import com.polysocial.model.chat.Message
import com.polysocial.model.chat.MessagesResult
import com.polysocial.model.chat.MyOneToOneChatsResult
import com.polysocial.model.chat.OneToOneChat
import com.polysocial.model.chat.OneToOneChatRepository
import com.polysocial.model.chat.SendMessageResult
import com.polysocial.model.chat.UnsendMessageResult
import com.polysocial.model.chat.isUnsent
import com.polysocial.model.friend.FRIENDSHIPS_COLLECTION
import com.polysocial.model.friend.friendshipId
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreOneToOneChatRepository] against the Firestore and Auth emulators, so the chat
 * rules (#428) and the server's ordering run on the real Firestore. The student signs in on
 * [FirebaseEmulator]'s app and their friend on one of [StudentApps], so both are signed in at once;
 * friendships and other students' data are seeded as an admin would, bypassing the rules.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestoreOneToOneChatRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  @After fun signOutTheFriend() = StudentApps.signOutAll()

  /** Always online, so the offline test reaches the transaction instead of the network check. */
  private val mine by lazy {
    FirestoreOneToOneChatRepository(
        db = emulator.firestore,
        auth = EmulatorSignedInUser(emulator),
        network = AlwaysOnline,
    )
  }

  private val friendApp
    get() = StudentApps.all.first()

  private val theirs by lazy {
    FirestoreOneToOneChatRepository(
        db = friendApp.firestore,
        auth = EmulatorSignedInUser(emulator, friendApp.auth),
        network = AlwaysOnline,
    )
  }

  /** The two students, signed in at once, and their pair ID. */
  private class Students(val me: String, val friend: String) {
    val id: String = checkNotNull(friendshipId(me, friend))
  }

  private suspend fun signInPair(): Students =
      Students(
          me = emulator.signInVerifiedUser(STUDENT_EMAIL).uid,
          friend = emulator.signInVerifiedUser(FRIEND_EMAIL, auth = friendApp.auth).uid,
      )

  private suspend fun befriend(pair: Students) = emulator.seedFriendship(pair.friend, pair.me, true)

  private suspend fun unfriend(pair: Students) =
      emulator.deleteAsAdmin(FRIENDSHIPS_COLLECTION, pair.id)

  private suspend fun <T> timed(call: suspend () -> T): T = withTimeout(TIMEOUT_MS) { call() }

  /** The student's chats, as the server first reports them through [repository]. */
  private suspend fun myChats(repository: OneToOneChatRepository = mine) =
      repository.observeMyChats().firstFromServer()

  /** The chat [pairId]'s messages, as the server first reports them through [repository]. */
  private suspend fun chat(pairId: String, repository: OneToOneChatRepository = mine) =
      repository.observeMessages(pairId).firstFromServer()

  private suspend fun messages(pairId: String, repository: OneToOneChatRepository = mine) =
      repository.observeMessages(pairId).messagesFromServer()

  private suspend fun latest(pairId: String) = mine.observeLatestMessage(pairId).firstFromServer()

  /** The stored chat's `uids`, read as the student from the server. */
  private suspend fun storedUids(pairId: String): Any? =
      emulator.firestore
          .collection(CHATS_COLLECTION)
          .document(pairId)
          .get(Source.SERVER)
          .await()["uids"]

  @Test
  fun twoFriends_startTheChatWithItsFirstMessage_andBothReadIt() = runBlocking {
    val pair = signInPair()
    befriend(pair)
    assertEquals(MessagesResult.Success(emptyList(), fromCache = false), chat(pair.id))
    assertEquals(LatestMessageResult.Success(null, fromCache = false), latest(pair.id))

    assertEquals(SendMessageResult.Sent, timed { mine.sendMessage(pair.id, "  Coffee at 4?\n") })
    assertEquals(SendMessageResult.Sent, timed { theirs.sendMessage(pair.id, "Sure!") })

    assertEquals(listOf(pair.me, pair.friend).sorted(), storedUids(pair.id))
    assertEquals(
        MyOneToOneChatsResult.Success(listOf(OneToOneChat(pair.id, pair.friend)), false),
        myChats(),
    )
    assertEquals(
        MyOneToOneChatsResult.Success(listOf(OneToOneChat(pair.id, pair.me)), false),
        myChats(theirs),
    )
    val chat = messages(pair.id, theirs)
    assertEquals(listOf("Coffee at 4?", "Sure!"), chat.map { it.text })
    assertEquals(listOf(pair.me, pair.friend), chat.map { it.senderId })
    assertEquals(chat.last(), (latest(pair.id) as LatestMessageResult.Success).message)
  }

  @Test
  fun withoutAnAcceptedFriendship_noChatIsStarted() = runBlocking {
    val pair = signInPair()

    assertEquals(SendMessageResult.UnexpectedError, timed { mine.sendMessage(pair.id, "Hi") })
    emulator.seedFriendship(pair.me, pair.friend)
    assertEquals(SendMessageResult.UnexpectedError, timed { mine.sendMessage(pair.id, "Hi") })
    assertEquals(SendMessageResult.UnexpectedError, timed { theirs.sendMessage(pair.id, "Hi") })

    assertFalse(emulator.existsAsAdmin(CHATS_COLLECTION, pair.id))
    assertEquals(MyOneToOneChatsResult.Success(emptyList(), fromCache = false), myChats())
  }

  @Test
  fun afterUnfriending_bothStillRead_neitherSends_butARetryOfAStoredDraftIsSent() = runBlocking {
    val pair = signInPair()
    befriend(pair)
    assertEquals(SendMessageResult.Sent, timed { mine.sendMessage(pair.id, "See you", DRAFT) })

    unfriend(pair)

    // The draft is stored: its transaction only reads, which the rules allow without friendship.
    assertEquals(SendMessageResult.Sent, timed { mine.sendMessage(pair.id, "See you", DRAFT) })
    assertEquals(SendMessageResult.UnexpectedError, timed { mine.sendMessage(pair.id, "Hey?") })
    assertEquals(SendMessageResult.UnexpectedError, timed { theirs.sendMessage(pair.id, "Hey?") })
    assertEquals(listOf("See you"), messages(pair.id).map { it.text })
    assertEquals(listOf("See you"), messages(pair.id, theirs).map { it.text })
    assertEquals(
        MyOneToOneChatsResult.Success(listOf(OneToOneChat(pair.id, pair.me)), false),
        myChats(theirs),
    )

    // Friends again: the same chat goes on after its history.
    befriend(pair)
    assertEquals(SendMessageResult.Sent, timed { theirs.sendMessage(pair.id, "Friends again") })
    assertEquals(listOf("See you", "Friends again"), messages(pair.id).map { it.text })
  }

  @Test
  fun unsending_emptiesTheText_andTheFriendsOpenChatReceivesItLive() = runBlocking {
    val pair = signInPair()
    befriend(pair)
    assertEquals(SendMessageResult.Sent, timed { theirs.sendMessage(pair.id, "Hi") })
    assertEquals(SendMessageResult.Sent, timed { mine.sendMessage(pair.id, "See you", DRAFT) })
    // The friend's chat is open on the text before it is unsent.
    val shown = CompletableDeferred<Unit>()
    val unsent = async {
      theirs.observeMessages(pair.id).first { result ->
        val messages = (result as? MessagesResult.Success)?.messages.orEmpty()
        if (messages.any { it.text == "See you" }) shown.complete(Unit)
        messages.any { it.isUnsent }
      }
    }
    timed { shown.await() }

    assertEquals(UnsendMessageResult.Unsent, timed { mine.unsendMessage(pair.id, DRAFT) })

    val received = (timed { unsent.await() } as MessagesResult.Success).messages
    assertEquals(listOf("Hi", ""), received.map { it.text })
    assertEquals(DRAFT, received.last().id)
    assertEquals(pair.me, received.last().senderId)
    // A retry reads it unsent and writes nothing.
    assertEquals(UnsendMessageResult.Unsent, timed { mine.unsendMessage(pair.id, DRAFT) })
  }

  @Test
  fun unsendingTheFriendsMessage_orAfterUnfriending_isUnexpected_andChangesNothing() = runBlocking {
    val pair = signInPair()
    befriend(pair)
    assertEquals(SendMessageResult.Sent, timed { theirs.sendMessage(pair.id, "Hi", "theirs") })
    assertEquals(SendMessageResult.Sent, timed { mine.sendMessage(pair.id, "See you", DRAFT) })

    assertEquals(
        UnsendMessageResult.UnexpectedError,
        timed { mine.unsendMessage(pair.id, "theirs") },
    )
    unfriend(pair)
    // The rules refuse the write: the attempt and its rerun both fail.
    assertEquals(
        UnsendMessageResult.UnexpectedError,
        timed { mine.unsendMessage(pair.id, DRAFT) },
    )

    assertEquals(listOf("Hi", "See you"), messages(pair.id, theirs).map { it.text })
  }

  @Test
  fun aRetryIdHoldingAnotherText_isUnexpected_andChangesNothing() = runBlocking {
    val pair = signInPair()
    befriend(pair)

    val results = timed {
      listOf(
          mine.sendMessage(pair.id, "Meet at six", DRAFT),
          mine.sendMessage(pair.id, " Meet at six ", DRAFT),
          mine.sendMessage(pair.id, "Different text", DRAFT),
          theirs.sendMessage(pair.id, "Meet at six", DRAFT),
      )
    }

    assertEquals(
        listOf(
            SendMessageResult.Sent,
            SendMessageResult.Sent,
            SendMessageResult.UnexpectedError,
            SendMessageResult.UnexpectedError,
        ),
        results,
    )
    val message = messages(pair.id).single()
    assertEquals(DRAFT, message.id)
    assertEquals("Meet at six", message.text)
    assertEquals(pair.me, message.senderId)
  }

  @Test
  fun bothFriendsSendingTheirFirstMessageAtOnce_areBothSent_inOneChat() = runBlocking {
    val pair = signInPair()
    befriend(pair)

    // Both first messages are sent at once. This checks the outcome (both Sent, one chat with both
    // messages) whether or not the two transactions overlapped on this run; the unit tests pin
    // the rerun itself (FirestoreOneToOneChatRepositoryTest), which the overlap needs.
    val results = timed {
      listOf(
              async { mine.sendMessage(pair.id, "Hi from me") },
              async { theirs.sendMessage(pair.id, "Hi from my friend") },
          )
          .awaitAll()
    }

    assertEquals(listOf(SendMessageResult.Sent, SendMessageResult.Sent), results)
    assertEquals(listOf(pair.me, pair.friend).sorted(), storedUids(pair.id))
    assertEquals(
        setOf("Hi from me", "Hi from my friend"),
        messages(pair.id).map { it.text }.toSet(),
    )
  }

  @Test
  fun aChatWhoseOtherAccountWasDeleted_staysReadable_withoutTheirUid() = runBlocking {
    val me = emulator.signInVerifiedUser(STUDENT_EMAIL).uid
    val pairId = checkNotNull(friendshipId(me, DELETED_UID))
    val sentAt = Instant.parse("2030-02-01T14:00:00Z")
    // What deleteAccount leaves: the deleted UID out of uids, its messages without a sender.
    emulator.createAsAdmin(CHATS_COLLECTION, pairId, mapOf("uids" to listOf(me)))
    emulator.createAsAdmin(
        "$CHATS_COLLECTION/$pairId/$MESSAGES_COLLECTION",
        "kept",
        mapOf("senderId" to DELETED_MESSAGE_SENDER, "text" to "Bye", "createdAt" to sentAt),
    )

    assertEquals(
        MyOneToOneChatsResult.Success(listOf(OneToOneChat(pairId, null)), fromCache = false),
        myChats(),
    )
    assertEquals(listOf(Message("kept", DELETED_MESSAGE_SENDER, "Bye", sentAt)), messages(pairId))
    assertEquals(SendMessageResult.UnexpectedError, timed { mine.sendMessage(pairId, "Hello?") })
  }

  @Test
  fun anotherPairsChat_isNotListed() = runBlocking {
    val pair = signInPair()
    befriend(pair)
    assertEquals(SendMessageResult.Sent, timed { theirs.sendMessage(pair.id, "Hi") })
    emulator.signInVerifiedUser(OUTSIDER_EMAIL)

    assertEquals(MyOneToOneChatsResult.Success(emptyList(), fromCache = false), myChats())
    // The repository's own guard refuses another pair's ID before any read or write, so the rules
    // aren't reached here; the rules tests (firestore.rules.test.js) cover a third student.
    assertEquals(MessagesResult.UnexpectedError, chat(pair.id))
    assertEquals(SendMessageResult.UnexpectedError, timed { mine.sendMessage(pair.id, "Hi") })
    assertEquals(listOf("Hi"), messages(pair.id, theirs).map { it.text })
  }

  @Test
  fun theFirstMessage_offline_isANetworkError_andStartsNoChat() = runBlocking {
    val pair = signInPair()
    befriend(pair)
    val friendship = emulator.firestore.collection(FRIENDSHIPS_COLLECTION).document(pair.id)

    // The network check says online, so this is the transaction failing, not being queued. It
    // fails once Firestore gives up on the server, about 8 s into airplane mode (#119).
    val result =
        whileOffline(friendship) {
          withTimeout(OFFLINE_TIMEOUT_MS) { mine.sendMessage(pair.id, "Sent while offline") }
        }

    assertEquals(SendMessageResult.NetworkError, result)
    assertFalse(emulator.existsAsAdmin(CHATS_COLLECTION, pair.id))
    assertEquals(emptyList<Message>(), messages(pair.id))
  }

  private companion object {
    /** Fictional accounts: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val FRIEND_EMAIL = "test.friend@epfl.ch"
    const val OUTSIDER_EMAIL = "other.student@epfl.ch"
    const val DELETED_UID = "fictional-deleted-student"
    const val DRAFT = "draft-1"
    const val TIMEOUT_MS = 15_000L
    const val OFFLINE_TIMEOUT_MS = 30_000L
  }
}
