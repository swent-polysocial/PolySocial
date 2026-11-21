// Contributors: Claude Opus 5.5 (friend repository tests against the rules, #357); Konstantinos
// Perdikeas (directed, reviewed and owns them).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.friend.FRIENDSHIPS_COLLECTION
import com.polysocial.model.friend.FirestoreFriendRepository
import com.polysocial.model.friend.Friendship
import com.polysocial.model.friend.FriendshipActionResult
import com.polysocial.model.friend.FriendshipStatus
import com.polysocial.model.friend.FriendshipsResult
import com.polysocial.model.friend.SendFriendRequestResult
import com.polysocial.model.friend.friendshipId
import com.polysocial.model.user.PUBLIC_PROFILES_COLLECTION
import com.polysocial.model.user.USERS_COLLECTION
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreFriendRepository] against the Firestore and Auth emulators, so its transactions,
 * its query and the friendship rules (#357) run on the real Firestore. Other students are fictional
 * UIDs whose public profiles and requests are seeded as an admin would, bypassing the rules.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestoreFriendRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  private val repository by lazy {
    FirestoreFriendRepository(
        db = emulator.firestore,
        auth = EmulatorSignedInUser(emulator),
        network = AlwaysOnline,
    )
  }

  private suspend fun signInStudent(): String = emulator.signInVerifiedUser(STUDENT_EMAIL).uid

  /** Gives the fictional student [uid] a public profile, so they can be sent a request. */
  private suspend fun seedStudent(uid: String) =
      emulator.createAsAdmin(
          PUBLIC_PROFILES_COLLECTION,
          uid,
          mapOf(
              "displayName" to "Test Student",
              "section" to "IN",
              "year" to "BA3",
              "interests" to emptyList<String>(),
          ),
      )

  private suspend fun exists(from: String, to: String) =
      emulator.existsAsAdmin(FRIENDSHIPS_COLLECTION, friendshipId(from, to)!!)

  /** The first list of the student's friendships read from the server, not the cache. */
  private suspend fun myFriendships(): List<Friendship> =
      withTimeout(TIMEOUT_MS) {
        val result =
            repository.observeFriendships().first {
              it !is FriendshipsResult.Success || !it.fromCache
            }
        assertTrue("Expected friendships, got $result", result is FriendshipsResult.Success)
        (result as FriendshipsResult.Success).friendships
      }

  private suspend fun <T> timed(call: suspend () -> T): T = withTimeout(TIMEOUT_MS) { call() }

  @Test
  fun sendRequest_storesAPendingRequestTheStudentSees() = runBlocking {
    val uid = signInStudent()
    seedStudent(FRIEND_UID)

    assertEquals(SendFriendRequestResult.Sent, timed { repository.sendRequest(FRIEND_UID) })

    val sent = myFriendships().single()
    assertEquals(friendshipId(uid, FRIEND_UID), sent.id)
    assertEquals(listOf(uid, FRIEND_UID).sorted(), sent.uids)
    assertEquals(uid, sent.requestedBy)
    assertEquals(FriendshipStatus.PENDING, sent.status)
  }

  @Test
  fun aSecondRequest_inEitherDirection_alreadyExists() = runBlocking {
    signInStudent()
    seedStudent(FRIEND_UID)
    timed { repository.sendRequest(FRIEND_UID) }

    assertEquals(
        SendFriendRequestResult.AlreadyExists,
        timed { repository.sendRequest(FRIEND_UID) },
    )

    seedStudent(OTHER_UID)
    emulator.seedFriendship(from = OTHER_UID, to = currentUid())
    assertEquals(SendFriendRequestResult.AlreadyExists, timed { repository.sendRequest(OTHER_UID) })
  }

  @Test
  fun sendRequest_toAUidWithoutAPublicProfile_isRefusedByTheRules() = runBlocking {
    val uid = signInStudent()

    assertEquals(
        SendFriendRequestResult.UnexpectedError,
        timed { repository.sendRequest(FRIEND_UID) },
    )
    assertFalse(exists(uid, FRIEND_UID))
  }

  @Test
  fun accept_ofAnIncomingRequest_makesThemFriends() = runBlocking {
    val uid = signInStudent()
    emulator.seedFriendship(from = FRIEND_UID, to = uid)
    val incoming = myFriendships().single()

    assertEquals(FriendshipActionResult.Done, timed { repository.accept(incoming) })

    val friends = myFriendships().single()
    assertEquals(FriendshipStatus.ACCEPTED, friends.status)
    assertNotNull(friends.acceptedAt)
    assertEquals(incoming.createdAt, friends.createdAt)
  }

  @Test
  fun accept_ofARequestDeletedSinceItWasShown_isGone() = runBlocking {
    // The transaction reads the missing document, which the rules allow under the student's own
    // pair ID: without that, this would be an UnexpectedError.
    val uid = signInStudent()
    emulator.seedFriendship(from = FRIEND_UID, to = uid)
    val incoming = myFriendships().single()
    timed { repository.remove(incoming) }

    assertEquals(FriendshipActionResult.Gone, timed { repository.accept(incoming) })
    assertFalse(exists(uid, FRIEND_UID))
  }

  @Test
  fun aStaleCancel_afterTheRequestWasAccepted_isGoneAndKeepsTheFriendship() = runBlocking {
    val uid = signInStudent()
    emulator.seedFriendship(from = FRIEND_UID, to = uid)
    val shown = myFriendships().single()
    timed { repository.accept(shown) }

    assertEquals(FriendshipActionResult.Gone, timed { repository.remove(shown) })
    assertTrue(exists(uid, FRIEND_UID))
  }

  @Test
  fun remove_deletesAFriendship() = runBlocking {
    val uid = signInStudent()
    emulator.seedFriendship(from = uid, to = FRIEND_UID, accepted = true)

    assertEquals(FriendshipActionResult.Done, timed { repository.remove(myFriendships().single()) })

    assertFalse(exists(uid, FRIEND_UID))
    assertEquals(emptyList<Friendship>(), myFriendships())
  }

  @Test
  fun observe_showsOnlyTheStudentsOwnFriendships() = runBlocking {
    val uid = signInStudent()
    emulator.seedFriendship(from = FRIEND_UID, to = uid)
    emulator.seedFriendship(from = FRIEND_UID, to = OTHER_UID)

    assertEquals(listOf(friendshipId(uid, FRIEND_UID)), myFriendships().map { it.id })
  }

  @Test
  fun anAssociationCannotSendOrReadFriendships() = runBlocking {
    val uid = emulator.signInVerifiedUser(ASSOCIATION_EMAIL).uid
    emulator.createAsAdmin(
        USERS_COLLECTION,
        uid,
        mapOf("uid" to uid, "accountType" to "association", "isAssociationVerified" to true),
    )
    seedStudent(FRIEND_UID)

    assertEquals(
        SendFriendRequestResult.UnexpectedError,
        timed { repository.sendRequest(FRIEND_UID) },
    )
    assertFalse(exists(uid, FRIEND_UID))
    assertEquals(
        FriendshipsResult.Error,
        withTimeout(TIMEOUT_MS) { repository.observeFriendships().first() },
    )
  }

  private fun currentUid(): String = EmulatorSignedInUser(emulator).currentUser()!!.uid

  private companion object {
    /** Fictional accounts: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val ASSOCIATION_EMAIL = "test.association@example.com"
    const val FRIEND_UID = "fictional-friend"
    const val OTHER_UID = "fictional-other"
    const val TIMEOUT_MS = 10_000L
  }
}
