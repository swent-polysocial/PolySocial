// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests of the student's group on the event
// detail, #233.
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.MyGroupResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.ui.group.GroupMember
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailYourGroupTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val events = FakeEventRepository()
  private val groups = FakeGroupRepository(currentUid = ME).apply { readableEventIds += EVENT }
  private val lea = PublicProfile("lea", "Léa Martin", "IN", "BA3", setOf("climbing"))
  private val tom = PublicProfile("tom", "Tom Rossi", "SC", "BA3", setOf("cinema"))
  private val profiles = FakePublicProfileRepository(mutableMapOf("lea" to lea, "tom" to tom))
  private var me = AuthUser(ME, "fictional.student@epfl.ch", true)

  private fun viewModel(groupRepository: GroupRepository = groups) =
      EventDetailViewModel(
          SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT)),
          events,
          object : NetworkMonitor {
            override fun isOnline() = true
          },
          FakeAuthRepository(me),
          Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneId.of("Europe/Zurich")),
          FakeAssociationNames(),
          FakeRegistrationRepository(),
          groupRepository,
          profiles,
      )

  /** Collects [vm]'s state like the screen does. */
  private fun TestScope.observe(vm: EventDetailViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    runCurrent()
  }

  private val EventDetailViewModel.yourGroup
    get() = (uiState.value as EventDetailUiState.Loaded).yourGroup

  @Test
  fun inAGroup_theDetailHasTheGroupWithItsMembersProfiles() = runTest {
    events.seed(validEvent().copy(id = EVENT))
    val group = groups.seedGroup(EVENT, listOf("lea", "tom", ME))
    val vm = viewModel()
    observe(vm)

    assertEquals(
        YourGroup(
            group.id,
            capacity = 4,
            members =
                listOf(GroupMember("lea", lea), GroupMember("tom", tom), GroupMember(ME, null)),
            myUid = ME,
        ),
        vm.yourGroup,
    )
    // Only the other members' profiles are read.
    assertEquals(listOf(listOf("lea", "tom")), profiles.requestedUids.map { it.toList() })
  }

  @Test
  fun notInAGroup_thereIsNone_andJoiningOrLeavingShowsLive() = runTest {
    events.seed(validEvent().copy(id = EVENT))
    val group = groups.seedGroup(EVENT, listOf("lea"))
    val vm = viewModel()
    observe(vm)
    assertNull(vm.yourGroup)

    groups.joinGroup(EVENT, group.id)
    runCurrent()
    assertEquals(listOf("lea", ME), vm.yourGroup?.members?.map { it.uid })

    groups.leaveGroup(EVENT, group.id)
    runCurrent()
    assertNull(vm.yourGroup)
  }

  @Test
  fun profilesThatCantBeRead_leaveTheMembersUnnamed() = runTest {
    events.seed(validEvent().copy(id = EVENT))
    groups.seedGroup(EVENT, listOf("lea", ME))
    profiles.failure = FakePublicProfileRepository.Failure.Offline
    val vm = viewModel()
    observe(vm)

    assertEquals(
        listOf(GroupMember("lea", null), GroupMember(ME, null)),
        vm.yourGroup?.members,
    )
  }

  @Test
  fun aGroupThatCantBeRead_leavesFindAGroup() = runTest {
    events.seed(validEvent().copy(id = EVENT))
    groups.seedGroup(EVENT, listOf("lea", ME))
    groups.readFailure = FakeGroupRepository.ReadFailure.NETWORK
    val vm = viewModel()
    observe(vm)

    assertNull(vm.yourGroup)
  }

  @Test
  fun onlyAChangeOfTheCache_doesntReadTheProfilesAgain() = runTest {
    events.seed(validEvent().copy(id = EVENT))
    val stored = groups.seedGroup(EVENT, listOf("lea", ME))
    val results = MutableStateFlow<MyGroupResult>(MyGroupResult.Success(stored, true))
    val vm =
        viewModel(
            object : GroupRepository by groups {
              override fun observeMyGroup(eventId: String): Flow<MyGroupResult> = results
            }
        )
    observe(vm)

    results.value = MyGroupResult.Success(stored, false)
    runCurrent()

    assertEquals(1, profiles.requestedUids.size)
    assertEquals(listOf("lea", ME), vm.yourGroup?.members?.map { it.uid })
  }

  @Test
  fun anAssociationsManagedEvent_neverReadsGroups() = runTest {
    me = AuthUser("association", "fictional.association@example.com", true)
    events.seed(
        validEvent()
            .copy(
                id = EVENT,
                createdBy = "association",
                organizerIds = listOf("association"),
                allowedUids = listOf("association"),
                isAssociationEvent = true,
            )
    )
    var observed = 0
    val vm =
        viewModel(
            object : GroupRepository by groups {
              override fun observeMyGroup(eventId: String): Flow<MyGroupResult> {
                observed++
                return groups.observeMyGroup(eventId)
              }
            }
        )
    observe(vm)

    assertEquals(true, (vm.uiState.value as EventDetailUiState.Loaded).isManaged)
    assertNull(vm.yourGroup)
    assertEquals(0, observed)
  }

  private companion object {
    const val EVENT = "party"
    const val ME = "me"
  }
}
