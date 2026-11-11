// Contributors: OpenAI Codex (roster, confirmation and connectivity tests, #234; chat route wiring,
// #238).
package com.polysocial.ui.group

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.group.FakeGroupRepository
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.GroupWriteResult
import com.polysocial.model.group.MyGroupResult
import com.polysocial.model.network.FakeNetworkMonitor
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class YourGroupScreenTest {
  @get:Rule(order = 0) val main = MainDispatcherRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val events =
      FakeEventRepository().apply { seed(validEvent(title = "Campus concert").copy(id = EVENT)) }
  private val groups = FakeGroupRepository(ME).apply { readableEventIds += EVENT }
  private val profiles =
      FakePublicProfileRepository(
          mutableMapOf(
              ME to PublicProfile(ME, "Test Student", "IN", "BA3"),
              "peer" to PublicProfile("peer", "Test Peer", "SC", "BA4", setOf("cinema")),
          )
      )
  private val network = FakeNetworkMonitor()
  private var left = 0
  private var backs = 0

  @Test
  fun rosterShowsPublicDetailsAndUpdatesAsMembersJoinAndLeave() {
    val group = groups.seedGroup(EVENT, listOf(ME, "peer"))
    show()

    compose.onNodeWithTag(YourGroupTags.EVENT).assertIsDisplayed()
    compose.onNodeWithText("Campus concert").assertIsDisplayed()
    compose.onNodeWithText("Test Peer").assertIsDisplayed()
    compose.onNodeWithText("SC · BA4 · Cinema").assertIsDisplayed()
    compose.onAllNodesWithText(context.getString(R.string.your_group_you)).assertCountEquals(2)

    asStudent("missing") { groups.joinGroup(EVENT, group.id) }
    text(R.string.group_unknown_member).assertIsDisplayed()
    text(R.string.group_profile_unavailable).assertIsDisplayed()
    asStudent("another") { groups.joinGroup(EVENT, group.id) }
    compose.onNodeWithText("Members · 4 of 4").assertIsDisplayed()
    asStudent("peer") { groups.leaveGroup(EVENT, group.id) }

    compose.onNodeWithText("Test Peer").assertDoesNotExist()
    compose.onNodeWithText("Members · 3 of 4").assertIsDisplayed()
  }

  @Test
  fun confirmationCanBeDismissedAndLeavingLastDeletesGroupBeforeReturning() {
    groups.seedGroup(EVENT, listOf(ME))
    show()
    tap(YourGroupTags.LEAVE)
    text(R.string.group_leave_title).assertIsDisplayed()
    tap(YourGroupTags.STAY)
    text(R.string.group_leave_title).assertDoesNotExist()
    assertEquals(listOf(ME), groups.groupsOf(EVENT).single().memberUids)
    assertEquals(0, left)

    tap(YourGroupTags.LEAVE)
    tap(YourGroupTags.CONFIRM)
    assertTrue(groups.groupsOf(EVENT).isEmpty())
    assertEquals(1, left)
  }

  @Test
  fun unreadableEventStillLetsTheStudentLeaveAndLocksTheSheetWhileWriting() {
    groups.seedGroup(EVENT, listOf(ME))
    events.deniedIds += EVENT
    val finish = CompletableDeferred<Unit>()
    var writes = 0
    val viewModel =
        show(
            object : GroupRepository by groups {
              override suspend fun leaveGroup(eventId: String, groupId: String): GroupWriteResult {
                writes++
                finish.await()
                return groups.leaveGroup(eventId, groupId)
              }
            }
        )
    compose.onNodeWithTag(YourGroupTags.EVENT).assertDoesNotExist()
    tap(YourGroupTags.LEAVE)
    text(R.string.group_leave_body).assertIsDisplayed()
    tap(YourGroupTags.CONFIRM)
    compose.onNodeWithTag(YourGroupTags.CONFIRM).assertIsNotEnabled()
    compose.onNodeWithTag(YourGroupTags.STAY).assertIsNotEnabled()
    assertEquals(0, left)
    viewModel.confirmLeave()
    viewModel.stay()
    settle()
    assertEquals(1, writes)

    finish.complete(Unit)
    settle()
    assertEquals(1, left)
    viewModel.confirmLeave()
    viewModel.stay()
    settle()
    assertEquals(1, writes)
    assertTrue(groups.groupsOf(EVENT).isEmpty())
  }

  @Test
  fun cachedRosterStaysVisibleOfflineAndLeaveIsDisabled() {
    groups.seedGroup(EVENT, listOf(ME, "peer", "missing"))
    network.online = false
    groups.online = false
    groups.readFromCache = true
    val viewModel = show()

    viewModel.askToLeave()
    viewModel.confirmLeave()
    viewModel.stay()
    settle()
    text(R.string.group_leave_title).assertDoesNotExist()
    compose.onNodeWithTag(YourGroupTags.OFFLINE).assertIsDisplayed()
    compose.onNodeWithText("Test Peer").assertIsDisplayed()
    text(R.string.group_profile_unavailable).assertIsDisplayed()
    compose.onNodeWithTag(YourGroupTags.LEAVE).assertIsNotEnabled()
  }

  @Test
  fun leaveFollowsConnectivityWithoutAnotherGroupSnapshot() {
    groups.seedGroup(EVENT, listOf(ME, "peer"))
    network.online = false
    show()

    compose.onNodeWithTag(YourGroupTags.OFFLINE).assertIsDisplayed()
    compose.onNodeWithTag(YourGroupTags.LEAVE).assertIsNotEnabled()

    network.online = true
    settle()
    compose.onNodeWithTag(YourGroupTags.OFFLINE).assertDoesNotExist()
    compose.onNodeWithTag(YourGroupTags.LEAVE).assertIsEnabled()

    network.online = false
    settle()
    compose.onNodeWithTag(YourGroupTags.OFFLINE).assertIsDisplayed()
    compose.onNodeWithTag(YourGroupTags.LEAVE).assertIsNotEnabled()
  }

  @Test
  fun aConnectionLostAfterOpeningConfirmationCanBeRetried() {
    groups.seedGroup(EVENT, listOf(ME, "peer"))
    show()
    tap(YourGroupTags.LEAVE)
    groups.online = false
    tap(YourGroupTags.CONFIRM)

    text(R.string.group_leave_offline).assertIsDisplayed()
    assertEquals(listOf(ME, "peer"), groups.groupsOf(EVENT).single().memberUids)
    assertEquals(0, left)

    groups.online = true
    tap(YourGroupTags.CONFIRM)
    assertEquals(listOf("peer"), groups.groupsOf(EVENT).single().memberUids)
    assertEquals(1, left)
  }

  @Test
  fun anUnexpectedLeaveFailureKeepsTheGroupAndCanBeDismissed() {
    groups.seedGroup(EVENT, listOf(ME, "peer"))
    show(
        object : GroupRepository by groups {
          override suspend fun leaveGroup(eventId: String, groupId: String): GroupWriteResult =
              error("Repository boundary failed")
        }
    )
    tap(YourGroupTags.LEAVE)
    tap(YourGroupTags.CONFIRM)

    text(R.string.group_leave_failed).assertIsDisplayed()
    tap(YourGroupTags.STAY)
    compose.onNodeWithText("Test Peer").assertIsDisplayed()
    assertEquals(0, left)
  }

  @Test
  fun aFailedListenerRestartsOnRetry() {
    groups.readFailure = FakeGroupRepository.ReadFailure.UNEXPECTED
    show()
    text(R.string.group_error_title).assertIsDisplayed()

    groups.readFailure = null
    groups.seedGroup(EVENT, listOf(ME, "peer"))
    tap(YourGroupTags.RETRY)
    compose.onNodeWithText("Test Peer").assertIsDisplayed()
  }

  @Test
  fun aCacheMissLoadsUntilTheServerConfirmsThereIsNoGroup() {
    val snapshots = MutableStateFlow<MyGroupResult>(MyGroupResult.Success(null, fromCache = true))
    val viewModel =
        show(
            object : GroupRepository by groups {
              override fun observeMyGroup(eventId: String) = snapshots
            }
        )
    text(R.string.group_loading).assertIsDisplayed()
    compose.onNodeWithTag(YourGroupTags.LEAVE).assertIsNotEnabled()
    viewModel.askToLeave()
    settle()
    text(R.string.group_leave_title).assertDoesNotExist()

    main.dispatcher.scheduler.advanceTimeBy(10_001)
    settle()
    text(R.string.group_error_title).assertIsDisplayed()

    snapshots.value = MyGroupResult.Success(null, fromCache = false)
    settle()
    text(R.string.group_missing_title).assertIsDisplayed()
    tap(YourGroupTags.BACK_TO_EVENT)
    assertEquals(1, backs)
  }

  @Test
  fun aGroupUnavailableOfflineShowsRetryAndRecovers() {
    groups.readFailure = FakeGroupRepository.ReadFailure.NETWORK
    show()
    text(R.string.group_not_saved).assertIsDisplayed()

    groups.readFailure = null
    groups.seedGroup(EVENT, listOf(ME))
    tap(YourGroupTags.RETRY)
    compose.onNodeWithText("Members · 1 of 4").assertIsDisplayed()
  }

  @Test
  fun aCancelledLeaveRestoresTheConfirmationAndCanBeRetried() {
    groups.seedGroup(EVENT, listOf(ME, "peer"))
    var attempts = 0
    show(
        object : GroupRepository by groups {
          override suspend fun leaveGroup(eventId: String, groupId: String): GroupWriteResult {
            if (attempts++ == 0) throw CancellationException("Test cancelled leave")
            return groups.leaveGroup(eventId, groupId)
          }
        }
    )
    tap(YourGroupTags.LEAVE)
    tap(YourGroupTags.CONFIRM)
    text(R.string.group_leave_title).assertIsDisplayed()
    assertEquals(listOf(ME, "peer"), groups.groupsOf(EVENT).single().memberUids)
    tap(YourGroupTags.CONFIRM)
    assertEquals(listOf("peer"), groups.groupsOf(EVENT).single().memberUids)
    assertEquals(1, left)
  }

  private fun show(repository: GroupRepository = groups): YourGroupViewModel {
    val viewModel =
        YourGroupViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT)),
            events,
            repository,
            profiles,
            FakeAuthRepository(AuthUser(ME, "test@epfl.ch", true)),
            network,
            Clock.fixed(Instant.parse("2030-01-01T10:00:00Z"), ZoneId.of("Europe/Zurich")),
        )
    compose.setContent {
      PolySocialTheme { YourGroupRoute({ backs++ }, { left++ }, viewModel = viewModel) }
    }
    settle()
    return viewModel
  }

  private fun asStudent(uid: String, write: suspend () -> GroupWriteResult) {
    groups.currentUid = uid
    try {
      assertTrue(runBlocking { write() } is GroupWriteResult.Success)
    } finally {
      groups.currentUid = ME
    }
    settle()
  }

  private fun tap(tag: String) {
    compose.onNodeWithTag(tag).performClick()
    settle()
  }

  private fun text(resource: Int, useUnmergedTree: Boolean = false) =
      compose.onNodeWithText(context.getString(resource), useUnmergedTree = useUnmergedTree)

  private fun settle() {
    repeat(2) {
      compose.waitForIdle()
      main.dispatcher.scheduler.runCurrent()
    }
    compose.waitForIdle()
  }

  private companion object {
    const val ME = "me"
    const val EVENT = "concert"
  }
}
