// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #232, and more after review).
package com.polysocial.model.group

import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fake follows the [GroupRepository] contracts the screens (#233) will be tested against. */
class FakeGroupRepositoryTest {
  private val repository =
      FakeGroupRepository(currentUid = "student").apply { readableEventIds += setOf(EVENT, OTHER) }

  /** The first result after [GroupsResult.Loading]. */
  private suspend fun groups(eventId: String = EVENT) =
      repository.observeGroups(eventId).drop(1).first()

  private suspend fun myGroup(eventId: String = EVENT) =
      repository.observeMyGroup(eventId).drop(1).first()

  private fun membersOf(groupId: String, eventId: String = EVENT) =
      repository.groupsOf(eventId).single { it.id == groupId }.memberUids

  @Test
  fun createGroup_makesTheStudentItsOnlyMemberWithFourPlaces() = runTest {
    val result = repository.createGroup(EVENT)

    val group = repository.groupsOf(EVENT).single()
    assertEquals(GroupWriteResult.Success(group.id), result)
    assertEquals(listOf("student"), group.memberUids)
    assertEquals("student", group.createdBy)
    assertEquals(GROUP_CAPACITY, group.capacity)
    assertEquals(EVENT, group.eventId)
    assertEquals(MyGroupResult.Success(group, fromCache = false), myGroup())
  }

  @Test
  fun createGroup_whileInAGroupOfTheEvent_isAlreadyInGroup() = runTest {
    repository.seedGroup(EVENT, listOf("other", "student"))

    assertEquals(GroupWriteResult.AlreadyInGroup, repository.createGroup(EVENT))

    assertEquals(1, repository.groupsOf(EVENT).size)
  }

  @Test
  fun aGroupOfAnotherEvent_doesNotCount() = runTest {
    repository.seedGroup(OTHER, listOf("student"))

    assertTrue(repository.createGroup(EVENT) is GroupWriteResult.Success)
    assertEquals(MyGroupResult.Success(repository.groupsOf(OTHER).single(), false), myGroup(OTHER))
  }

  @Test
  fun joinGroup_addsTheStudentLast() = runTest {
    val group = repository.seedGroup(EVENT, listOf("creator", "other"))

    assertEquals(GroupWriteResult.Success(group.id), repository.joinGroup(EVENT, group.id))

    assertEquals(listOf("creator", "other", "student"), membersOf(group.id))
  }

  @Test
  fun joinGroup_twice_changesNothing() = runTest {
    val group = repository.seedGroup(EVENT, listOf("creator"))
    repository.joinGroup(EVENT, group.id)

    assertEquals(GroupWriteResult.Success(group.id), repository.joinGroup(EVENT, group.id))

    assertEquals(listOf("creator", "student"), membersOf(group.id))
  }

  @Test
  fun joinGroup_aFullGroup_isFullAndChangesNothing() = runTest {
    val group = repository.seedGroup(EVENT, listOf("a", "b", "c", "d"))
    assertTrue(group.isFull)

    assertEquals(GroupWriteResult.Full, repository.joinGroup(EVENT, group.id))

    assertEquals(listOf("a", "b", "c", "d"), membersOf(group.id))
  }

  @Test
  fun joinGroup_theLastPlace_fillsTheGroup() = runTest {
    val group = repository.seedGroup(EVENT, listOf("a", "b", "c"))

    repository.joinGroup(EVENT, group.id)
    repository.currentUid = "late"

    assertEquals(GroupWriteResult.Full, repository.joinGroup(EVENT, group.id))
    assertTrue(repository.groupsOf(EVENT).single().isFull)
  }

  @Test
  fun joinGroup_whileInAnotherGroupOfTheEvent_isAlreadyInGroup() = runTest {
    repository.seedGroup(EVENT, listOf("student"))
    val other = repository.seedGroup(EVENT, listOf("other"))

    assertEquals(GroupWriteResult.AlreadyInGroup, repository.joinGroup(EVENT, other.id))

    assertEquals(listOf("other"), membersOf(other.id))
  }

  @Test
  fun joinGroup_aMissingGroupOrAnUnreadableEvent_isNotFound() = runTest {
    repository.seedGroup("private", listOf("other"))

    assertEquals(GroupWriteResult.NotFound, repository.joinGroup(EVENT, "missing"))
    assertEquals(GroupWriteResult.NotFound, repository.joinGroup("private", "group-1"))
    assertEquals(GroupWriteResult.NotFound, repository.createGroup("private"))
    assertEquals(listOf("other"), membersOf("group-1", "private"))
  }

  @Test
  fun leaveGroup_removesOnlyTheStudent() = runTest {
    val group = repository.seedGroup(EVENT, listOf("creator", "student", "other"))

    assertEquals(GroupWriteResult.Success(group.id), repository.leaveGroup(EVENT, group.id))

    assertEquals(listOf("creator", "other"), membersOf(group.id))
    assertEquals(MyGroupResult.Success(null, false), myGroup())
  }

  @Test
  fun leaveGroup_theLastMember_deletesTheGroup_andLeavingAgainIsNotFound() = runTest {
    val group = repository.seedGroup(EVENT, listOf("student"))

    assertEquals(GroupWriteResult.Success(group.id), repository.leaveGroup(EVENT, group.id))

    assertEquals(emptyList<Group>(), repository.groupsOf(EVENT))
    assertEquals(GroupWriteResult.NotFound, repository.leaveGroup(EVENT, group.id))
  }

  @Test
  fun leaveGroup_aGroupTheStudentIsNotIn_changesNothing() = runTest {
    val group = repository.seedGroup(EVENT, listOf("other"))

    assertEquals(GroupWriteResult.Success(group.id), repository.leaveGroup(EVENT, group.id))

    assertEquals(listOf("other"), membersOf(group.id))
  }

  @Test
  fun leaveGroup_ofAnEventNoLongerReadable_isNotFound() = runTest {
    val group = repository.seedGroup(EVENT, listOf("student", "other"))
    repository.readableEventIds -= EVENT

    assertEquals(GroupWriteResult.NotFound, repository.leaveGroup(EVENT, group.id))
    assertEquals(listOf("student", "other"), membersOf(group.id))
  }

  @Test
  fun afterLeaving_theStudentCanJoinAnotherGroup() = runTest {
    val first = repository.seedGroup(EVENT, listOf("student", "other"))
    val second = repository.seedGroup(EVENT, listOf("someone"))
    repository.leaveGroup(EVENT, first.id)

    assertEquals(GroupWriteResult.Success(second.id), repository.joinGroup(EVENT, second.id))
  }

  @Test
  fun offline_everyWriteIsANetworkErrorAndChangesNothing() = runTest {
    val group = repository.seedGroup(EVENT, listOf("student", "other"))
    val open = repository.seedGroup(OTHER, listOf("other"))
    repository.online = false

    assertEquals(GroupWriteResult.NetworkError, repository.createGroup(OTHER))
    assertEquals(GroupWriteResult.NetworkError, repository.joinGroup(OTHER, open.id))
    assertEquals(GroupWriteResult.NetworkError, repository.leaveGroup(EVENT, group.id))

    assertEquals(listOf("student", "other"), membersOf(group.id))
    assertEquals(listOf("other"), membersOf(open.id, OTHER))
  }

  @Test
  fun aWriteFailure_isReturnedWithoutChangingAnything() = runTest {
    val group = repository.seedGroup(EVENT, listOf("other"))
    repository.writeFailure = GroupWriteResult.UnexpectedError

    assertEquals(GroupWriteResult.UnexpectedError, repository.joinGroup(EVENT, group.id))

    assertEquals(listOf("other"), membersOf(group.id))
  }

  @Test
  fun idsThatCannotBeDocumentIds_areCheckedFirst_thenTheUser() = runTest {
    repository.currentUid = null

    assertEquals(GroupWriteResult.NotFound, repository.createGroup(" "))
    assertEquals(GroupWriteResult.NotFound, repository.joinGroup(EVENT, "a/b"))
    assertEquals(GroupWriteResult.NotFound, repository.leaveGroup("", "group-1"))
    assertEquals(GroupWriteResult.UnexpectedError, repository.createGroup(EVENT))
    assertEquals(
        listOf(MyGroupResult.Loading, MyGroupResult.UnexpectedError),
        repository.observeMyGroup(EVENT).toList(),
    )
  }

  @Test
  fun observeGroups_emitsLoadingThenEveryGroupOldestFirst_fullOnesIncluded() = runTest {
    val full = repository.seedGroup(EVENT, listOf("a", "b", "c", "d"))
    val open = repository.seedGroup(EVENT, listOf("e"))
    repository.seedGroup(OTHER, listOf("f"))
    repository.readFromCache = true

    assertEquals(GroupsResult.Loading, repository.observeGroups(EVENT).first())
    assertEquals(GroupsResult.Success(listOf(full, open), fromCache = true), groups())
    assertFalse(open.isFull)
  }

  @Test
  fun observing_anUnreadableEvent_isAnUnexpectedError() = runTest {
    assertEquals(
        listOf(GroupsResult.Loading, GroupsResult.UnexpectedError),
        repository.observeGroups("private").toList(),
    )
  }

  @Test
  fun observeGroups_withNobodySignedIn_isAnUnexpectedError() = runTest {
    repository.seedGroup(EVENT, listOf("other"))
    repository.currentUid = null

    assertEquals(
        listOf(GroupsResult.Loading, GroupsResult.UnexpectedError),
        repository.observeGroups(EVENT).toList(),
    )
  }

  @Test
  fun observeGroups_checksTheEventWhenCollectionStarts() = runTest {
    val group = repository.seedGroup("private", listOf("other"))
    val privateGroups = repository.observeGroups("private")

    repository.readableEventIds += "private"

    assertEquals(GroupsResult.Success(listOf(group), false), privateGroups.drop(1).first())
  }

  @Test
  fun observeMyGroup_ofAnEventNoLongerReadable_stillShowsTheGroup() = runTest {
    // Like a student removed from a private event: the rules still let them list their own group.
    val group = repository.seedGroup(EVENT, listOf("student", "other"))
    repository.readableEventIds -= EVENT

    assertEquals(MyGroupResult.Success(group, fromCache = false), myGroup())
    assertEquals(GroupsResult.UnexpectedError, groups())
  }

  @Test
  fun observeMyGroup_readsTheStudentWhenCollectionStarts() = runTest {
    val group = repository.seedGroup(EVENT, listOf("later"))
    val mine = repository.observeMyGroup(EVENT)

    repository.currentUid = "later"
    assertEquals(MyGroupResult.Success(group, fromCache = false), mine.drop(1).first())
    repository.currentUid = null
    assertEquals(listOf(MyGroupResult.Loading, MyGroupResult.UnexpectedError), mine.toList())
  }

  @Test
  fun readFailures_endBothFlowsWithTheirOwnError() = runTest {
    repository.readFailure = FakeGroupRepository.ReadFailure.NETWORK
    assertEquals(GroupsResult.NetworkError, groups())
    assertEquals(MyGroupResult.NetworkError, myGroup())

    repository.readFailure = FakeGroupRepository.ReadFailure.UNEXPECTED
    assertEquals(GroupsResult.UnexpectedError, groups())
    assertEquals(MyGroupResult.UnexpectedError, myGroup())
  }

  @Test
  fun observeMyGroup_withoutAGroup_isNull() = runTest {
    repository.seedGroup(EVENT, listOf("other"))

    assertNull((myGroup() as MyGroupResult.Success).group)
  }

  @Test
  fun seedGroup_withoutMembers_isRejected() {
    assertThrows(IllegalArgumentException::class.java) { repository.seedGroup(EVENT, emptyList()) }

    assertEquals(emptyList<Group>(), repository.groupsOf(EVENT))
  }

  private companion object {
    const val EVENT = "event-1"
    const val OTHER = "event-2"
  }
}
