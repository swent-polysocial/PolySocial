// Contributors: Claude Opus 5.5 (drafted the My friend code navigation tests, #339); Konstantinos
// Perdikeas (directed, reviewed and owns them).
// Contributors: Claude Opus 5.5 (test WorkManager initialization, needed once ProfileViewModel
// depends on ReminderScheduler for real DI, #123).
package com.polysocial.ui.profile

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.polysocial.MainActivity
import com.polysocial.resources.C
import com.polysocial.ui.navigation.AppShell
import com.polysocial.ui.navigation.Tab
import com.polysocial.ui.navigation.TabAction
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.FirebaseStartupRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class FriendCodeNavigationTest {
  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
  @get:Rule(order = 1) val firebaseStartupRule = FirebaseStartupRule()
  @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()
  private lateinit var navController: NavHostController

  /** The real `ProfileViewModel` needs a WorkManager-backed `ReminderScheduler` (#123). */
  @Before
  fun initializeTestWorkManager() {
    WorkManagerTestInitHelper.initializeTestWorkManager(
        ApplicationProvider.getApplicationContext(),
        Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
  }

  // The test app has no signed-in account (FirebaseStartupRule), so the screen shows its "can't
  // show" state: these tests cover navigation; the code itself is covered by FriendCodeScreenTest
  // and the end-to-end test.
  private fun showShell() {
    rule.activity.setContent {
      PolySocialTheme {
        navController = rememberNavController()
        AppShell(
            navController = navController,
            eventsContent = { Text("Events", Modifier.testTag(Tab.EVENTS.screenTag)) },
            mapContent = { _, _ -> Text("Map", Modifier.testTag(Tab.MAP.screenTag)) },
            createEventContent = {
              Text("Create event", Modifier.testTag(C.Tag.create_event_screen))
            },
        )
      }
    }
    rule.onNodeWithTag(C.Tag.nav_item_profile).performClick()
  }

  private fun openFromProfile() {
    showShell()
    rule.onNodeWithTag(C.Tag.profile_friend_code_entry).performClick()
  }

  /** Taps [tag] twice, the second time 50 ms later, before the first tap's navigation settles. */
  private fun doubleTap(tag: String) {
    rule.mainClock.autoAdvance = false
    try {
      rule.onNodeWithTag(tag).performClick()
      rule.mainClock.advanceTimeBy(50)
      rule.onNodeWithTag(tag).performClick()
    } finally {
      rule.mainClock.autoAdvance = true
    }
    rule.waitForIdle()
  }

  @Test
  fun profileOpensTheScreenFullScreenAndCloseReturnsToProfile() {
    openFromProfile()

    rule.onNodeWithTag(C.Tag.friend_code_screen).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.friend_code_heading).assertTextEquals("My friend code")
    assertEquals(Tab.PROFILE.friendCodeRoute, navController.currentDestination?.route)
    rule.onNodeWithTag(C.Tag.bottom_nav).assertDoesNotExist()
    rule.onNodeWithTag(C.Tag.app_bar_title).assertDoesNotExist()
    rule.onNodeWithTag(TabAction.EDIT_PROFILE.tag).assertDoesNotExist()

    rule.onNodeWithTag(C.Tag.friend_code_close).performClick()
    rule.onNodeWithTag(C.Tag.profile_friend_code_entry).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.nav_item_profile).assertIsSelected()
    rule.onNodeWithTag(C.Tag.bottom_nav).assertIsDisplayed()
  }

  @Test
  fun systemBackFromTheScreenReturnsToProfile() {
    openFromProfile()

    rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
    rule.onNodeWithTag(C.Tag.profile_friend_code_entry).assertIsDisplayed()
    assertEquals(Tab.PROFILE.rootRoute, navController.currentDestination?.route)
  }

  @Test
  fun withoutASignedInStudentTheScreenLeadsBackToProfile() {
    openFromProfile()

    rule.onNodeWithTag(C.Tag.friend_code_error).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.friend_code_back_to_profile).performClick()
    rule.onNodeWithTag(C.Tag.profile_friend_code_entry).assertIsDisplayed()
  }

  @Test
  fun aDoubleTapOnCloseReturnsToProfileOnlyOnce() {
    openFromProfile()
    rule.onNodeWithTag(C.Tag.friend_code_close).assertIsDisplayed()

    doubleTap(C.Tag.friend_code_close)

    rule.onNodeWithTag(C.Tag.profile_friend_code_entry).assertIsDisplayed()
    rule.runOnIdle { assertEquals(Tab.PROFILE.rootRoute, navController.currentDestination?.route) }
  }
}
