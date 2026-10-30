// Contributors: OpenAI Codex (adapted pending tests to the shared session controller).
// Contributors: Claude (wrote these tests, #127; testing agent: each "not available" entry, the
// replaced Snackbar, the logout dialog's states, #127).
package com.polysocial.ui.association

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.SessionCache
import com.polysocial.resources.C
import com.polysocial.ui.profile.ProfileTags
import com.polysocial.ui.profile.ProfileViewModel
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pending association app (#127) with its real ViewModels over fake repositories: its tabs, the
 * "not available yet" Snackbar, its details and logging out.
 */
@RunWith(AndroidJUnit4::class)
class PendingAssociationAppTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val auth = FakeAuthRepository(clubUser)
  private val associations = FakeAssociationRepository(AssociationResult.Found(testClub))
  private var clears = 0
  private var clearResult = CacheClearResult.Cleared
  private val cache =
      object : SessionCache {
        override suspend fun clear(): CacheClearResult {
          clears++
          return clearResult
        }
      }
  private var loggedOut = 0

  private fun launch() {
    val logout = ProfileViewModel(auth, cache, associations)
    compose.setContent {
      PolySocialTheme {
        PendingAssociationApp(
            onLoggedOut = { loggedOut++ },
            logoutViewModel = logout,
        )
      }
    }
    compose.waitForIdle()
  }

  private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

  private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

  private fun openOrganization() {
    compose.onNodeWithTag(AssociationTags.NAV_ORGANIZATION).performClick()
    compose.onNodeWithTag(AssociationTags.ORGANIZATION_SCREEN).assertIsDisplayed()
  }

  // --- The tabs ---

  @Test
  fun opensOnEvents_withTheAssociationsTabs_andNoStudentTab() {
    launch()

    compose.onNodeWithTag(AssociationTags.EVENTS_SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.NAV_EVENTS).assertIsSelected().assertTextEquals("Events")
    compose.onNodeWithTag(AssociationTags.NAV_MAP).assertTextEquals("Map")
    compose.onNodeWithTag(AssociationTags.NAV_ORGANIZATION).assertTextEquals("Organization")
    // Associations get no Chats or Profile, nor the students' Events list or app shell.
    for (studentTag in
        listOf(
            C.Tag.nav_item_chats,
            C.Tag.nav_item_profile,
            C.Tag.nav_item_events,
            C.Tag.app_shell,
        )) {
      compose.onNodeWithTag(studentTag).assertDoesNotExist()
    }
  }

  @Test
  fun theEventsTab_showsTheReviewSteps_underTheAssociationsName() {
    launch()

    node(AssociationTags.HEADER_NAME).assertTextEquals("Test Club")
    compose.onNodeWithTag(AssociationTags.HEADER_INITIALS).assertTextEquals("TC")
    compose.onNodeWithTag(AssociationTags.UNDER_REVIEW).assertIsDisplayed()
    for (text in
        listOf(
            "Under review",
            "You can't publish events yet.",
            "Request sent",
            "PolySocial team checks it",
            "Verified: your drafts can go live",
        )) {
      compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
    }
    // TalkBack reads where each step is.
    for (step in
        listOf(
            "Request sent, done",
            "PolySocial team checks it, in progress",
            "Verified: your drafts can go live, not yet",
        )) {
      compose.onNodeWithContentDescription(step).assertIsDisplayed()
    }
  }

  @Test
  fun drafts_newDraft_andInviteMembers_eachSayNotAvailableYet_andStayOnTheTab() {
    launch()
    val message = string(R.string.association_not_available_yet)
    compose.onAllNodesWithText(message).assertCountEquals(0)

    for (tag in
        listOf(AssociationTags.DRAFTS, AssociationTags.NEW_DRAFT, AssociationTags.INVITE_MEMBERS)) {
      compose.onNodeWithTag(tag).performScrollTo().performClick()
      compose.waitForIdle()

      // One Snackbar at a time, even after several taps.
      compose.onAllNodesWithText(message).assertCountEquals(1)
      compose.onNodeWithTag(AssociationTags.SNACKBAR).assertIsDisplayed()
      compose.onNodeWithTag(AssociationTags.EVENTS_SCREEN).assertIsDisplayed()
    }
  }

  @Test
  fun theNotAvailableYetMessage_leavesWithItsScreen() {
    launch()
    compose.onNodeWithTag(AssociationTags.DRAFTS).performClick()
    compose.onNodeWithTag(AssociationTags.SNACKBAR).assertIsDisplayed()

    compose.onNodeWithTag(AssociationTags.NAV_ORGANIZATION).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(AssociationTags.SNACKBAR).assertDoesNotExist()
  }

  @Test
  fun theMapTab_saysItOpensOnceVerified() {
    launch()

    compose.onNodeWithTag(AssociationTags.NAV_MAP).performClick()

    compose.onNodeWithTag(AssociationTags.MAP_SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.NAV_MAP).assertIsSelected()
    compose.onNodeWithText("The map opens once you're verified").assertIsDisplayed()
    compose
        .onNodeWithText(
            "PolySocial checks every association by hand. Once you are verified, students see your events here."
        )
        .assertIsDisplayed()
    node(AssociationTags.HEADER_NAME).assertTextEquals("Test Club")
  }

  @Test
  fun theOrganizationTab_showsItsDetails_underReview_neverVerified() {
    launch()

    openOrganization()

    node(AssociationTags.NAME).assertTextEquals("Test Club")
    node(AssociationTags.TYPE).assertTextEquals("Club · EPFL")
    node(AssociationTags.ABOUT).assertTextEquals("A fictional club for tests.")
    compose.onNodeWithTag(AssociationTags.BADGE).assertTextEquals("Under review")
    compose.onAllNodesWithText("Verified association").assertCountEquals(0)
    compose.onNodeWithTag(AssociationTags.LOG_OUT).assertTextEquals("Log out")
    compose.onNodeWithTag(AssociationTags.NAV_ORGANIZATION).assertIsSelected()
  }

  // --- Details that can't be read ---

  @Test
  fun detailsThatCantBeRead_leaveLogOut_andRetryReadsThemAgain() {
    associations.getAssociationResult = AssociationResult.NetworkError
    launch()

    // The Events tab works without the name.
    compose.onNodeWithTag(AssociationTags.UNDER_REVIEW).assertIsDisplayed()
    node(AssociationTags.HEADER_NAME).assertDoesNotExist()
    openOrganization()
    compose.onNodeWithTag(AssociationTags.LOAD_ERROR).assertIsDisplayed()
    compose.onNodeWithText("Couldn't load your association").assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.LOG_OUT).assertIsDisplayed()

    associations.getAssociationResult = AssociationResult.Found(testClub)
    compose.onNode(hasText(string(R.string.retry))).performClick()
    compose.waitForIdle()

    node(AssociationTags.NAME).assertTextEquals("Test Club")
    assertEquals(2, associations.requestedUids.size)
  }

  // --- Log out ---

  @Test
  fun logOut_confirms_clearsTheCache_signsOut_andLeaves() {
    launch()
    openOrganization()

    compose.onNodeWithTag(AssociationTags.LOG_OUT).performClick()
    compose.onNodeWithTag(ProfileTags.Dialog).assertIsDisplayed()
    assertEquals(0, clears)
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitForIdle()

    assertEquals(1, clears)
    assertEquals(1, auth.logOutCalls)
    assertNull(auth.user)
    assertEquals(1, loggedOut)
  }

  @Test
  fun logOut_cancelled_staysSignedIn() {
    launch()
    openOrganization()

    compose.onNodeWithTag(AssociationTags.LOG_OUT).performClick()
    compose.onNodeWithTag(ProfileTags.Cancel).performClick()

    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    assertEquals(0, clears)
    assertEquals(0, loggedOut)
    assertEquals(clubUser, auth.user)
  }

  @Test
  fun aCacheThatCantBeCleared_keepsTheAccountSignedIn_andOffersRetry() {
    clearResult = CacheClearResult.UnexpectedError
    launch()
    openOrganization()

    compose.onNodeWithTag(AssociationTags.LOG_OUT).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(ProfileTags.Error).assertIsDisplayed()
    assertEquals(0, auth.logOutCalls)
    assertEquals(0, loggedOut)
  }

  // --- Added by the testing agent (#127) ---

  /** Taps [tag] alone on a fresh Events tab: the Snackbar says "Not available yet". */
  private fun assertSaysNotAvailableYet(tag: String) {
    launch()
    compose.onNodeWithTag(AssociationTags.SNACKBAR).assertDoesNotExist()

    compose.onNodeWithTag(tag).performScrollTo().performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(AssociationTags.SNACKBAR).assertIsDisplayed()
    compose.onNodeWithText("Not available yet").assertIsDisplayed()
  }

  @Test fun drafts_alone_saysNotAvailableYet() = assertSaysNotAvailableYet(AssociationTags.DRAFTS)

  @Test
  fun newDraft_alone_saysNotAvailableYet() = assertSaysNotAvailableYet(AssociationTags.NEW_DRAFT)

  @Test
  fun inviteMembers_alone_saysNotAvailableYet() =
      assertSaysNotAvailableYet(AssociationTags.INVITE_MEMBERS)

  @Test
  fun aSecondTap_replacesTheMessage_insteadOfQueueingAnother() {
    launch()
    compose.mainClock.autoAdvance = false
    repeat(3) {
      compose.onNodeWithTag(AssociationTags.DRAFTS).performClick()
      compose.mainClock.advanceTimeBy(100)
    }
    assertTrue(compose.onAllNodesWithText("Not available yet").fetchSemanticsNodes().isNotEmpty())

    // A short Snackbar lasts 4 s. Queued messages would show one after the other for 12 s; the
    // replaced ones leave with the last one.
    compose.mainClock.advanceTimeBy(5_000)

    compose.onAllNodesWithTag(AssociationTags.SNACKBAR).assertCountEquals(0)
  }

  @Test
  fun whileTheCacheIsCleared_theDialogShowsProgress_withoutCancelOrConfirm() {
    val gate = kotlinx.coroutines.CompletableDeferred<CacheClearResult>()
    val slowCache =
        object : SessionCache {
          override suspend fun clear(): CacheClearResult = gate.await()
        }
    val logout = ProfileViewModel(auth, slowCache, associations)
    compose.setContent {
      PolySocialTheme {
        PendingAssociationApp(
            onLoggedOut = { loggedOut++ },
            logoutViewModel = logout,
        )
      }
    }
    openOrganization()
    compose.onNodeWithTag(AssociationTags.LOG_OUT).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(ProfileTags.Loading).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTags.Confirm).assertIsNotEnabled()
    compose.onNodeWithTag(ProfileTags.Cancel).assertDoesNotExist()
    assertEquals(0, loggedOut)

    gate.complete(CacheClearResult.Cleared)
    compose.waitForIdle()

    assertEquals(1, loggedOut)
  }

  @Test
  fun aFailedClear_offersRetry_withoutCancel_andRetryLogsOut() {
    clearResult = CacheClearResult.UnexpectedError
    launch()
    openOrganization()
    compose.onNodeWithTag(AssociationTags.LOG_OUT).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(ProfileTags.Confirm).assertTextEquals("Retry")
    compose.onNodeWithTag(ProfileTags.Cancel).assertDoesNotExist()

    clearResult = CacheClearResult.Cleared
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitForIdle()

    assertEquals(2, clears)
    assertEquals(1, loggedOut)
  }
}
