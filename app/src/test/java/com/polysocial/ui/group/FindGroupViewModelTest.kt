// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests of Find a group's ViewModel, #233.
package com.polysocial.ui.group

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.GroupWriteResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FindGroupViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val events = FakeEventRepository()
  private val groups = FakeGroupRepository(currentUid = ME).apply { readableEventIds += EVENT }
  private val profiles = FakePublicProfileRepository()
  private var online = true

  private lateinit var vm: FindGroupViewModel

  /** The sample data of #227's frames, which matches #231's scores: 15, 7 and 1. */
  private fun seedFigmaGroups() {
    profiles.profiles +=
        listOf(
                profile(ME, "You", "IN", "BA3", "climbing", "hiking", "techno"),
                profile("julia", "Julia", "IN", "BA1", "climbing", "hiking"),
                profile("ana", "Ana", "IN", "MA1", "techno"),
                profile("noe", "Noé", "IN", "BA5", "cinema"),
                profile("lea", "Léa", "IN", "BA3", "climbing", "board games"),
                profile("tom", "Tom", "SC", "BA3", "cinema", "football"),
                profile("marc", "Marc", "GC", "BA3", "football"),
            )
            .associateBy { it.uid }
    // Oldest first: Marc's group, then Léa's, then Julia's, so the order shown comes from the
    // score.
    groups.seedGroup(EVENT, listOf("marc"))
    groups.seedGroup(EVENT, listOf("lea", "tom"))
    groups.seedGroup(EVENT, listOf("julia", "ana", "noe"))
  }

  private fun profile(
      uid: String,
      name: String,
      section: String,
      year: String,
      vararg interests: String,
  ) = PublicProfile(uid, name, section, year, interests.toSet())

  /** Builds the ViewModel and collects its state, like the screen does. */
  private fun TestScope.open(groupRepository: GroupRepository = groups) {
    events.seed(validEvent(title = "Balélec warm-up").copy(id = EVENT))
    vm =
        FindGroupViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT)),
            events,
            groupRepository,
            profiles,
            FakeAuthRepository(AuthUser(ME, "fictional.student@epfl.ch", true)),
            object : NetworkMonitor {
              override fun isOnline() = online
            },
            Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Europe/Zurich")),
        )
    backgroundScope.launch { vm.uiState.collect {} }
    advanceUntilIdle()
  }

  private val loaded
    get() = vm.uiState.value as FindGroupUiState.Loaded

  @Test
  fun theOpenGroups_areRankedBestMatchFirst_withWhyEachFits() =
      runTest(main.dispatcher) {
        seedFigmaGroups()
        open()

        val (julia, lea, marc) = loaded.suggestions
        assertEquals(listOf("group-3", "group-2", "group-1"), loaded.suggestions.map { it.groupId })
        assertEquals(
            Triple(3, "IN", null),
            Triple(julia.sharedInterests, julia.sameSection, julia.sameYear),
        )
        assertEquals(
            Triple(1, "IN", "BA3"),
            Triple(lea.sharedInterests, lea.sameSection, lea.sameYear),
        )
        assertEquals(
            Triple(0, null, "BA3"),
            Triple(marc.sharedInterests, marc.sameSection, marc.sameYear),
        )
        assertEquals(listOf("Léa", "Tom"), lea.members.map { it.profile?.displayName })
        assertEquals(4, lea.capacity)
        assertEquals("Balélec warm-up", loaded.event?.event?.title)
        assertEquals(false, loaded.isOffline)
      }

  @Test
  fun fullGroupsAndTheStudentsOwn_areLeftOut() =
      runTest(main.dispatcher) {
        groups.seedGroup(EVENT, listOf("a", "b", "c", "d"))
        groups.seedGroup(EVENT, listOf("e", ME))
        val open = groups.seedGroup(EVENT, listOf("f"))
        open()

        assertEquals(listOf(open.id), loaded.suggestions.map { it.groupId })
      }

  @Test
  fun noOpenGroup_isAnEmptyList() =
      runTest(main.dispatcher) {
        open()

        assertEquals(emptyList<GroupSuggestion>(), loaded.suggestions)
      }

  @Test
  fun profilesThatCantBeRead_leaveTheGroupsShownOldestFirst_withoutNames() =
      runTest(main.dispatcher) {
        seedFigmaGroups()
        profiles.failure = FakePublicProfileRepository.Failure.Offline
        open()

        assertEquals(listOf("group-1", "group-2", "group-3"), loaded.suggestions.map { it.groupId })
        assertTrue(loaded.suggestions.flatMap { it.members }.all { it.profile == null })
        assertEquals(0, loaded.suggestions.sumOf { it.sharedInterests })
      }

  @Test
  fun aFailedRead_couldNotLoad_andRetryReadsAgain() =
      runTest(main.dispatcher) {
        groups.readFailure = FakeGroupRepository.ReadFailure.NETWORK
        groups.seedGroup(EVENT, listOf("lea"))
        open()
        assertEquals(FindGroupUiState.CouldNotLoad, vm.uiState.value)

        groups.readFailure = null
        vm.retry()
        advanceUntilIdle()

        assertEquals(listOf("group-1"), loaded.suggestions.map { it.groupId })
      }

  @Test
  fun joining_addsTheStudent_andReportsJoined() =
      runTest(main.dispatcher) {
        val group = groups.seedGroup(EVENT, listOf("lea"))
        open()

        vm.join(group.id)
        advanceUntilIdle()

        assertEquals(FindGroupDone.JOINED, vm.done.value)
        assertEquals(listOf("lea", ME), groups.groupsOf(EVENT).single().memberUids)
        assertNull(vm.message.value)
        assertNull(loaded.pending)
      }

  @Test
  fun startingAGroup_reportsStarted() =
      runTest(main.dispatcher) {
        open()

        vm.start()
        advanceUntilIdle()

        assertEquals(FindGroupDone.STARTED, vm.done.value)
        assertEquals(listOf(ME), groups.groupsOf(EVENT).single().memberUids)
      }

  @Test
  fun aGroupThatFilledUpMeanwhile_saysSo_andReportsNoSuccess() =
      runTest(main.dispatcher) {
        val group = groups.seedGroup(EVENT, listOf("a", "b", "c"))
        open()
        // Someone else takes the last place before the student's Join arrives.
        groups.currentUid = "d"
        groups.joinGroup(EVENT, group.id)
        groups.currentUid = ME

        vm.join(group.id)
        advanceUntilIdle()

        assertEquals(FindGroupMessage.GROUP_FULL, vm.message.value)
        assertNull(vm.done.value)
        assertEquals(emptyList<GroupSuggestion>(), loaded.suggestions)
      }

  @Test
  fun eachRefusal_hasItsMessage_andNeverReportsASuccess() =
      runTest(main.dispatcher) {
        val group = groups.seedGroup(EVENT, listOf("lea"))
        open()
        val cases =
            listOf(
                GroupWriteResult.AlreadyInGroup to FindGroupMessage.ALREADY_IN_GROUP,
                GroupWriteResult.NotFound to FindGroupMessage.GROUP_GONE,
                GroupWriteResult.NetworkError to FindGroupMessage.OFFLINE,
                GroupWriteResult.UnexpectedError to FindGroupMessage.UNEXPECTED,
            )
        for ((result, message) in cases) {
          groups.writeFailure = result
          vm.join(group.id)
          advanceUntilIdle()
          assertEquals(message, vm.message.value)
          vm.onMessageShown()
          assertNull(vm.message.value)
        }
        groups.writeFailure = GroupWriteResult.NotFound
        vm.start()
        advanceUntilIdle()

        assertEquals(FindGroupMessage.EVENT_GONE, vm.message.value)
        assertNull(vm.done.value)
        assertEquals(listOf("lea"), groups.groupsOf(EVENT).single().memberUids)
      }

  @Test
  fun aWriteThatThrows_isUnexpected() =
      runTest(main.dispatcher) {
        val group = groups.seedGroup(EVENT, listOf("lea"))
        open(
            object : GroupRepository by groups {
              override suspend fun joinGroup(eventId: String, groupId: String) =
                  throw IllegalStateException("test failure")
            }
        )

        vm.join(group.id)
        advanceUntilIdle()

        assertEquals(FindGroupMessage.UNEXPECTED, vm.message.value)
        assertNull(loaded.pending)
      }

  @Test
  fun offline_disablesJoinAndStart_withoutAnyWrite() =
      runTest(main.dispatcher) {
        val group = groups.seedGroup(EVENT, listOf("lea"))
        online = false
        open()
        assertTrue(loaded.isOffline)

        vm.join(group.id)
        vm.start()
        advanceUntilIdle()

        assertEquals(listOf("lea"), groups.groupsOf(EVENT).single().memberUids)
        assertNull(vm.done.value)
        assertNull(vm.message.value)
      }

  @Test
  fun aSecondTapWhileAWriteRuns_isIgnored() =
      runTest(main.dispatcher) {
        val group = groups.seedGroup(EVENT, listOf("lea"))
        var joins = 0
        val gate = CompletableDeferred<Unit>()
        open(
            object : GroupRepository by groups {
              override suspend fun joinGroup(eventId: String, groupId: String): GroupWriteResult {
                joins++
                gate.await()
                return groups.joinGroup(eventId, groupId)
              }
            }
        )

        vm.join(group.id)
        advanceUntilIdle()
        assertEquals(PendingGroupWrite.Join(group.id), loaded.pending)
        vm.join(group.id)
        vm.start()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, joins)
        assertEquals(FindGroupDone.JOINED, vm.done.value)
        assertEquals(1, groups.groupsOf(EVENT).size)
      }

  private companion object {
    const val EVENT = "party"
    const val ME = "me"
  }
}
