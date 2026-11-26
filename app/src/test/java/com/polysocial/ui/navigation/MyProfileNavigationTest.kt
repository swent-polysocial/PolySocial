// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of opening My profile from the
// Profile tab or any tab, and Edit profile above it, #495).
// Contributors: Claude (Opus 5.5, Anthropic) (the Chats tab's stand-in takes the one-to-one opener,
// #432).
// Contributors: Claude Opus 5.5 (your own UID from a friends list opens My profile, #501);
// Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.resources.C
import com.polysocial.ui.profile.LogoutState
import com.polysocial.ui.profile.ProfileScreen
import com.polysocial.ui.profile.StudentProfileViewModel
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Profile tab's first row opens My profile (#495), which opens Edit profile and gets back to it
 * after Save or Back. The screens themselves are replaced by buttons: they are tested apart.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class MyProfileNavigationTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var navController: NavHostController

  /** Whether the fake Edit profile closes twice at once, like a Back tapped twice. */
  private var editClosesTwice = false

  @Before
  fun setUp() {
    rule.setContent {
      navController = rememberNavController()
      PolySocialTheme {
        AppShell(
            navController = navController,
            signedInUid = { ME },
            eventsContent = { TabRootScreen(Tab.EVENTS) },
            mapContent = { _, _ -> TabRootScreen(Tab.MAP) },
            chatsContent = { _, _ -> TabRootScreen(Tab.CHATS) },
            profileContent = {
              ProfileScreen(
                  LogoutState.Idle,
                  {},
                  {},
                  {},
                  onOpenMyProfile = { navController.navigate(Tab.PROFILE.myProfileRoute) },
              )
            },
            myProfileContent = { onBack, onEdit ->
              Column(Modifier.testTag(C.Tag.my_profile_screen)) {
                Button(onClick = onBack, modifier = Modifier.testTag(C.Tag.my_profile_back)) {
                  Text("back")
                }
                Button(onClick = onEdit, modifier = Modifier.testTag(C.Tag.my_profile_edit)) {
                  Text("edit")
                }
              }
            },
            friendsContent = { navigation ->
              Column {
                Button(
                    onClick = { navigation.onOpenProfile(ME) },
                    modifier = Modifier.testTag(FRIENDS_OPEN_ME),
                ) {}
                Button(
                    onClick = { navigation.onOpenProfile(PEER) },
                    modifier = Modifier.testTag(FRIENDS_OPEN_PEER),
                ) {}
              }
            },
            studentProfileContent = { onBack ->
              Button(onClick = onBack, modifier = Modifier.testTag(STUDENT_PROFILE_BACK)) {}
            },
            editProfileContent = { onBack ->
              Button(
                  onClick = {
                    onBack()
                    if (editClosesTwice) onBack()
                  },
                  modifier = Modifier.testTag(EDIT_CLOSE),
              ) {
                Text("close")
              }
            },
        )
      }
    }
    rule.onNodeWithTag(C.Tag.nav_item_profile).performClick()
  }

  private fun route() = navController.currentDestination?.route

  @Test
  fun theFirstRowOpensMyProfileFullScreen_andBackReturnsToTheProfileTab() {
    rule.onNodeWithTag(C.Tag.profile_my_profile_entry).performClick()

    rule.onNodeWithTag(C.Tag.my_profile_screen).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.bottom_nav).assertDoesNotExist()
    rule.onNodeWithTag(C.Tag.app_bar_title).assertDoesNotExist()
    assertEquals(Tab.PROFILE.myProfileRoute, route())

    rule.onNodeWithTag(C.Tag.my_profile_back).performClick()

    rule.onNodeWithTag(C.Tag.profile_my_profile_entry).assertIsDisplayed()
    assertEquals(Tab.PROFILE.rootRoute, route())
  }

  @Test
  fun editProfileOpensAboveMyProfile_andItsSaveOrBackReturnsThere() {
    rule.onNodeWithTag(C.Tag.profile_my_profile_entry).performClick()
    rule.onNodeWithTag(C.Tag.my_profile_edit).performClick()

    rule.onNodeWithTag(EDIT_CLOSE).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.bottom_nav).assertDoesNotExist()
    assertEquals(Tab.PROFILE.editProfileRoute, route())

    rule.onNodeWithTag(EDIT_CLOSE).performClick()

    rule.onNodeWithTag(C.Tag.my_profile_screen).assertIsDisplayed()
    assertEquals(Tab.PROFILE.myProfileRoute, route())
  }

  @Test
  fun editProfileClosingTwice_leavesOnlyEditProfile_notMyProfileToo() {
    editClosesTwice = true
    rule.onNodeWithTag(C.Tag.profile_my_profile_entry).performClick()
    rule.onNodeWithTag(C.Tag.my_profile_edit).performClick()

    rule.onNodeWithTag(EDIT_CLOSE).performClick()

    rule.onNodeWithTag(C.Tag.my_profile_screen).assertIsDisplayed()
    assertEquals(Tab.PROFILE.myProfileRoute, route())
  }

  @Test
  fun myProfileOpensFromAnotherTab_andBackReturnsThere() {
    rule.onNodeWithTag(C.Tag.nav_item_events).performClick()
    rule.runOnIdle { navController.navigate(Tab.EVENTS.myProfileRoute) }

    rule.onNodeWithTag(C.Tag.my_profile_screen).assertIsDisplayed()
    rule.onNodeWithTag(C.Tag.my_profile_back).performClick()

    assertEquals(Tab.EVENTS.rootRoute, route())
  }

  @Test
  fun yourOwnUidFromAFriendsList_opensMyProfile_andAnotherUidTheirPublicProfile() {
    rule.runOnIdle { navController.navigate(Tab.PROFILE.friendsRoute) }

    rule.onNodeWithTag(FRIENDS_OPEN_ME).performClick()
    rule.onNodeWithTag(C.Tag.my_profile_screen).assertIsDisplayed()
    rule.onNodeWithTag(STUDENT_PROFILE_BACK).assertDoesNotExist()
    assertEquals(Tab.PROFILE.myProfileRoute, route())
    rule.onNodeWithTag(C.Tag.my_profile_back).performClick()
    assertEquals(Tab.PROFILE.friendsRoute, route())

    rule.onNodeWithTag(FRIENDS_OPEN_PEER).performClick()
    rule.onNodeWithTag(STUDENT_PROFILE_BACK).assertIsDisplayed()
    assertEquals(Tab.PROFILE.studentProfileRoute, route())
    assertEquals(
        PEER,
        navController.currentBackStackEntry?.arguments?.getString(StudentProfileViewModel.UID_ARG),
    )
  }

  private companion object {
    const val ME = "me"
    const val PEER = "peer 1/2"
    const val FRIENDS_OPEN_ME = "fake_friends_open_me"
    const val FRIENDS_OPEN_PEER = "fake_friends_open_peer"
    const val STUDENT_PROFILE_BACK = "fake_student_profile_back"
    const val EDIT_CLOSE = "fake_edit_profile_close"
  }
}
