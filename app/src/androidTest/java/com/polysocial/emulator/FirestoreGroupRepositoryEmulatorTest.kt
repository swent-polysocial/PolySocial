// Contributors: Claude (Opus 5.5, Anthropic) (group repository tests against the rules, #232, and
// more after review).
package com.polysocial.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.EVENTS_COLLECTION
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.toFirestoreMap
import com.polysocial.model.group.FirestoreGroupRepository
import com.polysocial.model.group.GROUPS_COLLECTION
import com.polysocial.model.group.GROUP_CAPACITY
import com.polysocial.model.group.GROUP_MEMBERS_COLLECTION
import com.polysocial.model.group.Group
import com.polysocial.model.group.GroupWriteResult
import com.polysocial.model.group.GroupsResult
import com.polysocial.model.group.MyGroupResult
import com.polysocial.model.user.USERS_COLLECTION
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [FirestoreGroupRepository] against the Firestore and Auth emulators, so its transactions,
 * the `groupMembers` markers and the group rules (#232) run on the real Firestore. Events and other
 * students' groups are seeded as an admin would, bypassing the rules. The race for the last place
 * signs a second student in through a second Firebase app, as on a second phone.
 */
@EmulatorTest
@RunWith(AndroidJUnit4::class)
class FirestoreGroupRepositoryEmulatorTest {

  @get:Rule val emulator = FirebaseEmulator()

  private val repository by lazy {
    FirestoreGroupRepository(
        db = emulator.firestore,
        auth = EmulatorSignedInUser(emulator),
        network = AlwaysOnline,
    )
  }

  /** The second student's repository, on [RivalApp]. */
  private val rivalRepository by lazy {
    FirestoreGroupRepository(
        db = RivalApp.firestore,
        auth = EmulatorSignedInUser(emulator, RivalApp.auth),
        network = AlwaysOnline,
    )
  }

  @After fun signOutTheRival() = RivalApp.auth.signOut()

  private suspend fun signIn(email: String): String = emulator.signInVerifiedUser(email).uid

  private suspend fun seed(id: String, event: Event = event()) =
      emulator.createAsAdmin(EVENTS_COLLECTION, id, event.toFirestoreMap())

  /** Stores the group [groupId] of [eventId] with [members], and each member's marker. */
  private suspend fun seedGroup(eventId: String, groupId: String, members: List<String>) {
    emulator.createAsAdmin(
        groupsOf(eventId),
        groupId,
        mapOf(
            "capacity" to GROUP_CAPACITY.toLong(),
            "memberUids" to members,
            "createdBy" to members.first(),
            "createdAt" to Instant.parse("2030-02-01T12:00:00Z"),
        ),
    )
    members.forEach { emulator.createAsAdmin(markersOf(eventId), it, mapOf("groupId" to groupId)) }
  }

  /** The event's groups, as the server first reports them through the repository. */
  private suspend fun groups(eventId: String): List<Group> =
      withTimeout(TIMEOUT_MS) {
        val result =
            repository.observeGroups(eventId).first {
              it !is GroupsResult.Loading && (it !is GroupsResult.Success || !it.fromCache)
            }
        assertTrue("Expected groups, got $result", result is GroupsResult.Success)
        (result as GroupsResult.Success).groups
      }

  /** The signed-in student's group for [eventId], as the server first reports it. */
  private suspend fun myGroup(eventId: String): Group? =
      withTimeout(TIMEOUT_MS) {
        val result =
            repository.observeMyGroup(eventId).first {
              it !is MyGroupResult.Loading && (it !is MyGroupResult.Success || !it.fromCache)
            }
        assertTrue("Expected my group, got $result", result is MyGroupResult.Success)
        (result as MyGroupResult.Success).group
      }

  private suspend fun hasMarker(eventId: String, uid: String) =
      emulator.existsAsAdmin(markersOf(eventId), uid)

  private suspend fun <T> timed(block: suspend () -> T): T = withTimeout(TIMEOUT_MS) { block() }

  @Test
  fun createGroup_storesTheStudentAsItsOnlyMember_withTheirMarker() = runBlocking {
    val uid = signIn(STUDENT_EMAIL)
    seed("public")

    val result = timed { repository.createGroup("public") }

    assertTrue("Expected a new group, got $result", result is GroupWriteResult.Success)
    val group = groups("public").single()
    assertEquals((result as GroupWriteResult.Success).groupId, group.id)
    assertEquals(listOf(uid), group.memberUids)
    assertEquals(uid, group.createdBy)
    assertEquals(GROUP_CAPACITY, group.capacity)
    assertNotNull(group.createdAt)
    assertEquals(group, myGroup("public"))
    assertTrue(hasMarker("public", uid))
  }

  @Test
  fun createGroup_aSecondTime_isAlreadyInGroup() = runBlocking {
    signIn(STUDENT_EMAIL)
    seed("public")
    timed { repository.createGroup("public") }

    assertEquals(GroupWriteResult.AlreadyInGroup, timed { repository.createGroup("public") })
    assertEquals(1, groups("public").size)
  }

  @Test
  fun createGroup_forAnEventTheStudentCannotRead_isNotFound() = runBlocking {
    val uid = signIn(STUDENT_EMAIL)
    seed("private", event().copy(isPrivate = true))

    assertEquals(GroupWriteResult.NotFound, timed { repository.createGroup("private") })
    assertEquals(GroupWriteResult.NotFound, timed { repository.createGroup("missing") })
    assertFalse(hasMarker("private", uid))
  }

  @Test
  fun joinGroup_untilFull_thenTheNextStudentGetsFull() = runBlocking {
    seed("public")
    seedGroup("public", "group", listOf(FIRST_UID, SECOND_UID))
    val third = signIn(STUDENT_EMAIL)
    assertEquals(
        GroupWriteResult.Success("group"),
        timed { repository.joinGroup("public", "group") },
    )
    val fourth = signIn(OTHER_STUDENT_EMAIL)
    assertEquals(
        GroupWriteResult.Success("group"),
        timed { repository.joinGroup("public", "group") },
    )

    signIn(LATE_STUDENT_EMAIL)
    assertEquals(GroupWriteResult.Full, timed { repository.joinGroup("public", "group") })

    val group = groups("public").single()
    assertEquals(listOf(FIRST_UID, SECOND_UID, third, fourth), group.memberUids)
    assertTrue(group.isFull)
    assertTrue(hasMarker("public", third))
    assertTrue(hasMarker("public", fourth))
  }

  @Test
  fun joinGroup_twice_changesNothing() = runBlocking {
    seed("public")
    seedGroup("public", "group", listOf(FIRST_UID))
    val uid = signIn(STUDENT_EMAIL)
    timed { repository.joinGroup("public", "group") }

    assertEquals(
        GroupWriteResult.Success("group"),
        timed { repository.joinGroup("public", "group") },
    )
    assertEquals(listOf(FIRST_UID, uid), groups("public").single().memberUids)
  }

  @Test
  fun joinGroup_whileInAnotherGroupOfTheEvent_isAlreadyInGroup() = runBlocking {
    seed("public")
    seedGroup("public", "other", listOf(FIRST_UID))
    val uid = signIn(STUDENT_EMAIL)
    timed { repository.createGroup("public") }

    assertEquals(GroupWriteResult.AlreadyInGroup, timed { repository.joinGroup("public", "other") })
    assertEquals(listOf(FIRST_UID), groups("public").single { it.id == "other" }.memberUids)
    assertTrue(hasMarker("public", uid))
  }

  @Test
  fun joinGroup_twoStudentsRacingForTheLastPlace_onlyOneGetsIt() = runBlocking {
    seed("public")
    seedGroup("public", "group", listOf(FIRST_UID, SECOND_UID, THIRD_UID))
    val rival = emulator.signInVerifiedUser(OTHER_STUDENT_EMAIL, auth = RivalApp.auth).uid
    val uid = signIn(STUDENT_EMAIL)

    val results = timed {
      listOf(
              async { repository.joinGroup("public", "group") },
              async { rivalRepository.joinGroup("public", "group") },
          )
          .awaitAll()
    }

    assertEquals(setOf(GroupWriteResult.Success("group"), GroupWriteResult.Full), results.toSet())
    val winner = if (results[0] is GroupWriteResult.Success) uid else rival
    val loser = if (winner == uid) rival else uid
    assertEquals(
        listOf(FIRST_UID, SECOND_UID, THIRD_UID, winner),
        groups("public").single().memberUids,
    )
    assertTrue(hasMarker("public", winner))
    assertFalse(hasMarker("public", loser))
  }

  @Test
  fun leaveGroup_bothMembersLeavingAtOnce_deletesTheGroup() = runBlocking {
    seed("public")
    val rival = emulator.signInVerifiedUser(OTHER_STUDENT_EMAIL, auth = RivalApp.auth).uid
    val uid = signIn(STUDENT_EMAIL)
    seedGroup("public", "group", listOf(uid, rival))

    val results = timed {
      listOf(
              async { repository.leaveGroup("public", "group") },
              async { rivalRepository.leaveGroup("public", "group") },
          )
          .awaitAll()
    }

    assertEquals(List(2) { GroupWriteResult.Success("group") }, results)
    assertFalse(emulator.existsAsAdmin(groupsOf("public"), "group"))
    assertFalse(hasMarker("public", uid))
    assertFalse(hasMarker("public", rival))
  }

  @Test
  fun joinGroup_aMissingGroup_orOneOfAnEventTheStudentCannotRead_isNotFound() = runBlocking {
    seed("public")
    seed("private", event().copy(isPrivate = true))
    seedGroup("private", "group", listOf(FIRST_UID))
    val uid = signIn(STUDENT_EMAIL)

    assertEquals(GroupWriteResult.NotFound, timed { repository.joinGroup("public", "missing") })
    assertEquals(GroupWriteResult.NotFound, timed { repository.joinGroup("private", "group") })
    assertFalse(hasMarker("private", uid))
  }

  @Test
  fun leaveGroup_removesOnlyTheStudentAndTheirMarker() = runBlocking {
    seed("public")
    val uid = signIn(STUDENT_EMAIL)
    seedGroup("public", "group", listOf(FIRST_UID, uid, SECOND_UID))

    assertEquals(
        GroupWriteResult.Success("group"),
        timed { repository.leaveGroup("public", "group") },
    )

    assertEquals(listOf(FIRST_UID, SECOND_UID), groups("public").single().memberUids)
    assertFalse(hasMarker("public", uid))
    assertTrue(hasMarker("public", FIRST_UID))
    assertEquals(null, myGroup("public"))
  }

  @Test
  fun leaveGroup_theLastMember_deletesTheGroup_andCanStartAnother() = runBlocking {
    seed("public")
    val uid = signIn(STUDENT_EMAIL)
    timed { repository.createGroup("public") }
    val group = groups("public").single()

    assertEquals(
        GroupWriteResult.Success(group.id),
        timed { repository.leaveGroup("public", group.id) },
    )

    assertFalse(emulator.existsAsAdmin(groupsOf("public"), group.id))
    assertFalse(hasMarker("public", uid))
    assertEquals(GroupWriteResult.NotFound, timed { repository.leaveGroup("public", group.id) })
    assertTrue(timed { repository.createGroup("public") } is GroupWriteResult.Success)
  }

  @Test
  fun groupsOfDifferentEvents_areIndependent() = runBlocking {
    seed("first")
    seed("second")
    signIn(STUDENT_EMAIL)
    val first = timed { repository.createGroup("first") } as GroupWriteResult.Success
    seedGroup("second", "open", listOf(FIRST_UID))

    assertEquals(GroupWriteResult.Success("open"), timed { repository.joinGroup("second", "open") })

    assertEquals(first.groupId, myGroup("first")?.id)
    assertEquals("open", myGroup("second")?.id)
    assertEquals(listOf(first.groupId), groups("first").map { it.id })
  }

  @Test
  fun observeMyGroup_ofAPrivateEventTheMemberWasRemovedFrom_stillShowsTheGroup() = runBlocking {
    val uid = signIn(STUDENT_EMAIL)
    // Not in allowedUids, as once the organizers removed them, but still in the group.
    seed("private", event().copy(isPrivate = true))
    seedGroup("private", "group", listOf(FIRST_UID, uid))

    val group = myGroup("private")

    assertEquals("group", group?.id)
    assertEquals(listOf(FIRST_UID, uid), group?.memberUids)
    // The server's answer, not a cached copy of the group myGroup just read.
    val eventGroups = timed {
      repository.observeGroups("private").first {
        it !is GroupsResult.Loading && (it !is GroupsResult.Success || !it.fromCache)
      }
    }
    assertEquals(GroupsResult.UnexpectedError, eventGroups)
  }

  @Test
  fun leaveGroup_ofAPrivateEventTheMemberWasRemovedFrom_removesThemAndTheirMarker() = runBlocking {
    val outsider = emulator.signInVerifiedUser(OTHER_STUDENT_EMAIL, auth = RivalApp.auth).uid
    val uid = signIn(STUDENT_EMAIL)
    // Not in allowedUids, as once the organizers removed them, but still in the group.
    seed("private", event().copy(isPrivate = true))
    seedGroup("private", "group", listOf(FIRST_UID, uid))

    assertEquals(
        GroupWriteResult.NotFound,
        timed { rivalRepository.leaveGroup("private", "group") },
    )
    assertEquals(
        GroupWriteResult.Success("group"),
        timed { repository.leaveGroup("private", "group") },
    )

    assertEquals(null, myGroup("private"))
    assertTrue(emulator.existsAsAdmin(groupsOf("private"), "group"))
    assertFalse(hasMarker("private", uid))
    assertTrue(hasMarker("private", FIRST_UID))
    assertFalse(hasMarker("private", outsider))
  }

  @Test
  fun observeMyGroup_withoutAGroupOfAnEventTheStudentCannotRead_isNoGroup() = runBlocking {
    signIn(STUDENT_EMAIL)
    seed("private", event().copy(isPrivate = true))
    seedGroup("private", "group", listOf(FIRST_UID))

    assertEquals(null, myGroup("private"))
  }

  @Test
  fun anAssociation_cannotReachGroups() = runBlocking {
    val uid = signIn(ASSOCIATION_EMAIL)
    emulator.createAsAdmin(
        USERS_COLLECTION,
        uid,
        mapOf("uid" to uid, "accountType" to "association", "isAssociationVerified" to true),
    )
    seed(
        "own",
        event()
            .copy(
                createdBy = uid,
                organizerIds = listOf(uid),
                allowedUids = listOf(uid),
                isAssociationEvent = true,
            ),
    )
    seedGroup("own", "group", listOf(FIRST_UID))

    // It reads its own event, so the marker read is the first one refused.
    assertEquals(GroupWriteResult.UnexpectedError, timed { repository.createGroup("own") })
    assertEquals(GroupWriteResult.NotFound, timed { repository.joinGroup("own", "group") })
    assertEquals(
        GroupsResult.UnexpectedError,
        timed { repository.observeGroups("own").first { it !is GroupsResult.Loading } },
    )
    assertFalse(hasMarker("own", uid))
  }

  private fun groupsOf(eventId: String) = "$EVENTS_COLLECTION/$eventId/$GROUPS_COLLECTION"

  private fun markersOf(eventId: String) = "$EVENTS_COLLECTION/$eventId/$GROUP_MEMBERS_COLLECTION"

  /** A second app on the emulators, for a second student signed in at the same time. */
  private object RivalApp {
    val app: FirebaseApp =
        FirebaseApp.initializeApp(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirebaseOptions.Builder()
                .setProjectId(FirebaseEmulator.PROJECT_ID)
                .setApplicationId("1:000000000000:android:0000000000000000")
                .setApiKey("demo-api-key")
                .build(),
            "group-rival",
        )

    val firestore: FirebaseFirestore =
        FirebaseFirestore.getInstance(app).apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FIRESTORE_PORT)
          firestoreSettings = firestoreSettings { setLocalCacheSettings(memoryCacheSettings {}) }
        }

    val auth: FirebaseAuth =
        FirebaseAuth.getInstance(app).apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.AUTH_PORT)
        }
  }

  private companion object {
    /** Fictional accounts and events: no real user data in tests. */
    const val STUDENT_EMAIL = "test.student@epfl.ch"
    const val OTHER_STUDENT_EMAIL = "other.student@epfl.ch"
    const val LATE_STUDENT_EMAIL = "late.student@epfl.ch"
    const val ASSOCIATION_EMAIL = "test.association@example.com"
    const val ORGANIZER_UID = "fictional-organizer"
    const val FIRST_UID = "fictional-student-1"
    const val SECOND_UID = "fictional-student-2"
    const val THIRD_UID = "fictional-student-3"
    const val TIMEOUT_MS = 15_000L

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
