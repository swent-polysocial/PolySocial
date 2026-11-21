// Contributors: Claude Opus 5.5 (wrote these tests, #357); Konstantinos Perdikeas (directed,
// reviewed and owns them).
// Contributors: Claude (Opus 5.5, Anthropic) (tests of seedAcceptedFriendship, #428).
package com.polysocial.model.friend

import java.time.Instant
import java.util.concurrent.CyclicBarrier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The fake follows the [FriendRepository] contracts the friends screens will be tested against. */
class FakeFriendRepositoryTest {
  private val now = Instant.parse("2026-11-16T10:00:00Z")
  private val later = Instant.parse("2026-11-16T11:00:00Z")

  private val repository = FakeFriendRepository(currentUid = "alice", now = now)

  private suspend fun myFriendships() = repository.observeFriendships().first()

  private fun pending(requestedBy: String = "bob", createdAt: Instant = now) =
      Friendship(
          id = "alice~bob",
          uids = listOf("alice", "bob"),
          requestedBy = requestedBy,
          status = FriendshipStatus.PENDING,
          createdAt = createdAt,
          acceptedAt = null,
      )

  private fun accepted(requestedBy: String = "bob") =
      pending(requestedBy).copy(status = FriendshipStatus.ACCEPTED, acceptedAt = later)

  @Test
  fun sendRequest_storesAPendingRequestFromTheSender() = runTest {
    assertEquals(SendFriendRequestResult.Sent, repository.sendRequest("bob"))

    val expected = pending(requestedBy = "alice")
    assertEquals(expected, repository.friendship("alice~bob"))
    assertEquals(FriendshipsResult.Success(listOf(expected), false), myFriendships())
  }

  @Test
  fun sendRequest_toAHigherOrLowerUid_storesTheUidsSorted() = runTest {
    repository.currentUid = "bob"

    assertEquals(SendFriendRequestResult.Sent, repository.sendRequest("alice"))
    assertEquals(pending(requestedBy = "bob"), repository.friendship("alice~bob"))
  }

  @Test
  fun aSecondRequest_inEitherDirection_alreadyExistsAndChangesNothing() = runTest {
    repository.sendRequest("bob")

    assertEquals(SendFriendRequestResult.AlreadyExists, repository.sendRequest("bob"))
    repository.currentUid = "bob"
    repository.now = later
    assertEquals(SendFriendRequestResult.AlreadyExists, repository.sendRequest("alice"))

    assertEquals(pending(requestedBy = "alice"), repository.friendship("alice~bob"))
  }

  @Test
  fun aRequestToAFriend_alreadyExists() = runTest {
    repository.seedFriendship(accepted())

    assertEquals(SendFriendRequestResult.AlreadyExists, repository.sendRequest("bob"))
    assertEquals(accepted(), repository.friendship("alice~bob"))
  }

  @Test
  fun aRequestToYourselfOrAnInvalidUid_isUnexpectedAndWritesNothing() = runTest {
    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("alice"))
    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("b~ob"))
    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest(""))

    assertEquals(FriendshipsResult.Success(emptyList(), false), myFriendships())
  }

  @Test
  fun accept_byTheRecipient_makesThemFriendsAtTheServerTime() = runTest {
    repository.seedFriendship(pending(requestedBy = "bob"))
    repository.now = later

    assertEquals(FriendshipActionResult.Done, repository.accept(pending(requestedBy = "bob")))

    assertEquals(accepted(), repository.friendship("alice~bob"))
  }

  @Test
  fun accept_byTheSender_isUnexpectedAndChangesNothing() = runTest {
    repository.seedFriendship(pending(requestedBy = "alice"))

    assertEquals(
        FriendshipActionResult.UnexpectedError,
        repository.accept(pending(requestedBy = "alice")),
    )
    assertEquals(pending(requestedBy = "alice"), repository.friendship("alice~bob"))
  }

  @Test
  fun accept_ofAnAcceptedFriendship_isUnexpectedAndChangesNothing() = runTest {
    repository.seedFriendship(accepted())

    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(accepted()))
    assertEquals(accepted(), repository.friendship("alice~bob"))
  }

  @Test
  fun accept_ofARequestThatIsGone_isGone() = runTest {
    assertEquals(FriendshipActionResult.Gone, repository.accept(pending()))

    assertNull(repository.friendship("alice~bob"))
  }

  @Test
  fun accept_ofARequestSentAgainSinceItWasShown_isGoneAndChangesNothing() = runTest {
    val resent = pending(createdAt = later)
    repository.seedFriendship(resent)

    assertEquals(FriendshipActionResult.Gone, repository.accept(pending()))
    assertEquals(resent, repository.friendship("alice~bob"))
  }

  @Test
  fun remove_deletesARequestOrFriendship_forEitherStudent() = runTest {
    repository.seedFriendship(pending(requestedBy = "bob"))
    assertEquals(FriendshipActionResult.Done, repository.remove(pending(requestedBy = "bob")))
    assertNull(repository.friendship("alice~bob"))

    repository.seedFriendship(pending(requestedBy = "alice"))
    assertEquals(FriendshipActionResult.Done, repository.remove(pending(requestedBy = "alice")))
    assertNull(repository.friendship("alice~bob"))

    repository.seedFriendship(accepted())
    repository.currentUid = "bob"
    assertEquals(FriendshipActionResult.Done, repository.remove(accepted()))
    assertNull(repository.friendship("alice~bob"))
  }

  @Test
  fun aStaleCancel_afterTheRequestWasAccepted_isGoneAndKeepsTheFriendship() = runTest {
    repository.seedFriendship(accepted(requestedBy = "alice"))

    assertEquals(FriendshipActionResult.Gone, repository.remove(pending(requestedBy = "alice")))
    assertEquals(accepted(requestedBy = "alice"), repository.friendship("alice~bob"))
  }

  @Test
  fun aStaleRemove_ofARequestSentAgain_isGoneAndKeepsTheNewRequest() = runTest {
    val resent = pending(createdAt = later)
    repository.seedFriendship(resent)

    assertEquals(FriendshipActionResult.Gone, repository.remove(pending()))
    assertEquals(resent, repository.friendship("alice~bob"))
  }

  @Test
  fun remove_ofAFriendshipThatIsGone_isGone() = runTest {
    assertEquals(FriendshipActionResult.Gone, repository.remove(accepted()))
  }

  @Test
  fun aStudentOutsideThePair_cannotAcceptOrRemove() = runTest {
    repository.seedFriendship(pending())
    repository.currentUid = "carol"

    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(pending()))
    assertEquals(FriendshipActionResult.UnexpectedError, repository.remove(pending()))
    assertEquals(pending(), repository.friendship("alice~bob"))
  }

  @Test
  fun aStudentOutsideThePair_ofAMissingFriendship_isUnexpected() = runTest {
    repository.currentUid = "carol"

    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(pending()))
    assertEquals(FriendshipActionResult.UnexpectedError, repository.remove(pending()))
  }

  @Test
  fun thePairIsReadFromTheId_notFromTheShownUids() = runTest {
    // Like the Security Rules, which read the document under its ID: a shown friendship whose uids
    // disagree with its ID can't act on another pair's document.
    val aliceAndCarol =
        pending().copy(id = "alice~carol", uids = listOf("alice", "carol"), requestedBy = "carol")
    repository.seedFriendship(aliceAndCarol)
    repository.currentUid = "bob"
    val mislabelled = aliceAndCarol.copy(uids = listOf("alice", "bob"))

    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(mislabelled))
    assertEquals(FriendshipActionResult.UnexpectedError, repository.remove(mislabelled))
    assertEquals(aliceAndCarol, repository.friendship("alice~carol"))
  }

  @Test
  fun anIdThatFriendshipIdWouldNotBuild_isNotAPair() = runTest {
    // The student's UID must be exactly one half of an ID friendshipId builds, not just one of its
    // parts: three parts, unsorted halves and a missing half are all refused.
    repository.currentUid = "bob"

    for (id in listOf("alice~bob~carol", "bob~alice", "bob~", "bob")) {
      val shown = pending().copy(id = id)
      assertEquals(id, FriendshipActionResult.UnexpectedError, repository.accept(shown))
      assertEquals(id, FriendshipActionResult.UnexpectedError, repository.remove(shown))
    }
  }

  @Test
  fun observeFriendships_showsOnlyTheSignedInStudentsFriendships() = runTest {
    val carolAndDan =
        pending().copy(id = "carol~dan", uids = listOf("carol", "dan"), requestedBy = "carol")
    repository.seedFriendship(pending())
    repository.seedFriendship(carolAndDan)

    assertEquals(FriendshipsResult.Success(listOf(pending()), false), myFriendships())
  }

  @Test
  fun observeFriendships_showsTheCurrentStateOnEachCollection() = runTest {
    repository.seedFriendship(pending())
    val emissions = mutableListOf<FriendshipsResult>()
    val flow = repository.observeFriendships()

    emissions += flow.first()
    repository.now = later
    repository.accept(pending())
    emissions += flow.first()
    repository.remove(accepted())
    emissions += flow.first()

    assertEquals(
        listOf(
            FriendshipsResult.Success(listOf(pending()), false),
            FriendshipsResult.Success(listOf(accepted()), false),
            FriendshipsResult.Success(emptyList(), false),
        ),
        emissions,
    )
  }

  @Test
  fun observeFriendships_reportsTheCache() = runTest {
    repository.readFromCache = true

    assertEquals(FriendshipsResult.Success(emptyList(), true), myFriendships())
  }

  @Test
  fun observeFriendships_readsTheUserAndReadFailureWhenCollectionStarts() = runTest {
    repository.seedFriendship(pending())
    repository.currentUid = null
    repository.readFailure = true
    val flow = repository.observeFriendships()

    repository.currentUid = "alice"
    repository.readFailure = false

    assertEquals(FriendshipsResult.Success(listOf(pending()), false), flow.first())
  }

  @Test
  fun aFailedRead_emitsErrorAndEnds() = runTest {
    repository.readFailure = true

    assertEquals(listOf(FriendshipsResult.Error), repository.observeFriendships().toList())
  }

  @Test
  fun nobodySignedIn_writesNothingAndCannotObserve() = runTest {
    repository.seedFriendship(pending())
    repository.currentUid = null

    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("bob"))
    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(pending()))
    assertEquals(FriendshipActionResult.UnexpectedError, repository.remove(pending()))
    assertEquals(listOf(FriendshipsResult.Error), repository.observeFriendships().toList())
    assertEquals(pending(), repository.friendship("alice~bob"))
  }

  @Test
  fun aFailedWrite_returnsTheFailureAndWritesNothing() = runTest {
    repository.sendFailure = SendFriendRequestResult.NetworkError
    assertEquals(SendFriendRequestResult.NetworkError, repository.sendRequest("bob"))
    assertNull(repository.friendship("alice~bob"))

    repository.seedFriendship(pending())
    repository.acceptFailure = FriendshipActionResult.NetworkError
    repository.removeFailure = FriendshipActionResult.UnexpectedError
    assertEquals(FriendshipActionResult.NetworkError, repository.accept(pending()))
    assertEquals(FriendshipActionResult.UnexpectedError, repository.remove(pending()))
    assertEquals(pending(), repository.friendship("alice~bob"))
  }

  @Test
  fun aFailedWrite_comesBeforeTheStoredState_likeTheRealOrderOfChecks() = runTest {
    // Offline is checked before the document is read, so even a request that already exists or is
    // gone reports the failure.
    repository.seedFriendship(pending())
    repository.sendFailure = SendFriendRequestResult.NetworkError
    repository.acceptFailure = FriendshipActionResult.NetworkError
    repository.removeFailure = FriendshipActionResult.NetworkError

    assertEquals(SendFriendRequestResult.NetworkError, repository.sendRequest("bob"))
    assertEquals(FriendshipActionResult.NetworkError, repository.accept(accepted()))
    assertEquals(FriendshipActionResult.NetworkError, repository.remove(accepted()))
  }

  @Test
  fun aRemovedRequest_canBeSentAgain_asANewRequest() = runTest {
    repository.sendRequest("bob")
    repository.remove(pending(requestedBy = "alice"))
    repository.now = later

    assertEquals(SendFriendRequestResult.Sent, repository.sendRequest("bob"))
    assertEquals(
        pending(requestedBy = "alice", createdAt = later),
        repository.friendship("alice~bob"),
    )
  }

  @Test
  fun accept_ofAStaleRequest_isGoneBeforeCheckingWhoSentItOrItsStatus() = runTest {
    // The document is read again first, so a request that changed is Gone even if accepting the
    // shown one would have been refused.
    assertEquals(FriendshipActionResult.Gone, repository.accept(accepted()))
    assertEquals(FriendshipActionResult.Gone, repository.accept(pending(requestedBy = "alice")))

    val resentByMe = pending(requestedBy = "alice", createdAt = later)
    repository.seedFriendship(resentByMe)
    assertEquals(FriendshipActionResult.Gone, repository.accept(pending(requestedBy = "alice")))
    assertEquals(resentByMe, repository.friendship("alice~bob"))
  }

  @Test
  fun observeFriendships_oneCollectorGetsEveryChange_includingACacheFlagChange() = runTest {
    val emissions = mutableListOf<FriendshipsResult>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeFriendships().toList(emissions)
    }

    repository.sendRequest("bob")
    repository.readFromCache = true
    repository.remove(pending(requestedBy = "alice"))

    assertEquals(
        listOf(
            FriendshipsResult.Success(emptyList(), false),
            FriendshipsResult.Success(listOf(pending(requestedBy = "alice")), false),
            FriendshipsResult.Success(listOf(pending(requestedBy = "alice")), true),
            FriendshipsResult.Success(emptyList(), true),
        ),
        emissions,
    )
  }

  @Test
  fun nobodySignedIn_comesBeforeAFailedWrite() = runTest {
    repository.seedFriendship(pending())
    repository.currentUid = null
    repository.sendFailure = SendFriendRequestResult.NetworkError
    repository.acceptFailure = FriendshipActionResult.NetworkError
    repository.removeFailure = FriendshipActionResult.NetworkError

    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("bob"))
    assertEquals(FriendshipActionResult.UnexpectedError, repository.accept(pending()))
    assertEquals(FriendshipActionResult.UnexpectedError, repository.remove(pending()))
  }

  @Test
  fun anOwnOrInvalidUid_comesBeforeAFailedSend() = runTest {
    repository.sendFailure = SendFriendRequestResult.NetworkError

    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("alice"))
    assertEquals(SendFriendRequestResult.UnexpectedError, repository.sendRequest("b~ob"))
  }

  @Test
  fun aFailedWrite_comesBeforeTheCheckThatTheStudentIsInThePair() = runTest {
    // Offline is checked before the document is read, and a student outside the pair is refused
    // only when reading it.
    repository.seedFriendship(pending())
    repository.currentUid = "carol"
    repository.acceptFailure = FriendshipActionResult.NetworkError
    repository.removeFailure = FriendshipActionResult.NetworkError

    assertEquals(FriendshipActionResult.NetworkError, repository.accept(pending()))
    assertEquals(FriendshipActionResult.NetworkError, repository.remove(pending()))
    assertEquals(pending(), repository.friendship("alice~bob"))
  }

  @Test
  fun aStaleCancel_ofARequestSentAgain_isGoneAndKeepsTheNewRequest() = runTest {
    // Without changing now: each write gets a later server time, so the resent request differs.
    repository.sendRequest("bob")
    val shown = repository.friendship("alice~bob")!!
    assertEquals(FriendshipActionResult.Done, repository.remove(shown))
    repository.sendRequest("bob")
    val resent = repository.friendship("alice~bob")!!

    assertNotEquals(shown.createdAt, resent.createdAt)
    assertEquals(FriendshipActionResult.Gone, repository.remove(shown))
    assertEquals(resent, repository.friendship("alice~bob"))
  }

  @Test
  fun twoConcurrentSends_sendOnlyOneRequest() = runTest {
    // Two taps on two threads at the same moment; like the transaction, only one sends.
    repeat(RACES) {
      val fake = FakeFriendRepository(currentUid = "alice", now = now)
      val results = race({ fake.sendRequest("bob") }, { fake.sendRequest("bob") })

      assertEquals(
          setOf(SendFriendRequestResult.Sent, SendFriendRequestResult.AlreadyExists),
          results.toSet(),
      )
      assertEquals(pending(requestedBy = "alice"), fake.friendship("alice~bob"))
    }
  }

  @Test
  fun aConcurrentAcceptAndRemove_oneWinsAndTheOtherIsGone_neverACrash() = runTest {
    repeat(RACES) {
      val fake = FakeFriendRepository(currentUid = "alice", now = later)
      fake.seedFriendship(pending())
      val (accepted, removed) = race({ fake.accept(pending()) }, { fake.remove(pending()) })

      if (accepted == FriendshipActionResult.Done) {
        assertEquals(FriendshipActionResult.Gone, removed)
        assertEquals(accepted(), fake.friendship("alice~bob"))
      } else {
        assertEquals(FriendshipActionResult.Gone, accepted)
        assertEquals(FriendshipActionResult.Done, removed)
        assertNull(fake.friendship("alice~bob"))
      }
    }
  }

  @Test
  fun seedAcceptedFriendship_storesTheTwoAsFriends_atTheServersTimes() = runTest {
    val friendship = repository.seedAcceptedFriendship("bob", "alice")

    val expected =
        Friendship(
            id = "alice~bob",
            uids = listOf("alice", "bob"),
            requestedBy = "bob",
            status = FriendshipStatus.ACCEPTED,
            createdAt = now,
            acceptedAt = now.plusMillis(1),
        )
    assertEquals(expected, friendship)
    assertEquals(FriendshipsResult.Success(listOf(expected), false), myFriendships())
    // The next write is stamped after it, as on the server.
    assertEquals(now.plusMillis(2), repository.now)
  }

  @Test
  fun seedAcceptedFriendship_refusesTwoUidsThatCannotBeAPair() {
    assertThrows(IllegalArgumentException::class.java) {
      repository.seedAcceptedFriendship("alice", "alice")
    }
    assertThrows(IllegalArgumentException::class.java) {
      repository.seedAcceptedFriendship("alice", "b~ob")
    }
    assertNull(repository.friendship("alice~alice"))
  }

  /** Runs [first] and [second] on two threads, started at the same moment, and returns both. */
  private suspend fun <T> race(first: suspend () -> T, second: suspend () -> T): List<T> {
    val start = CyclicBarrier(2)
    return withContext(Dispatchers.Default) {
      listOf(first, second)
          .map { action ->
            async {
              start.await()
              action()
            }
          }
          .awaitAll()
    }
  }

  private companion object {
    /** Enough races that an unsynchronized fake loses at least one. */
    const val RACES = 2_000
  }
}
