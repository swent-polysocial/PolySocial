// Contributors: OpenAI Codex (adapted retained-map-detail assertions to the real detail route).
// Contributors: OpenAI Codex (association tab graphs and retained stack isolation, #140).
// Contributors: Claude (Opus 5.5, Anthropic) replaced the Hilt-backed association header, #322;
// checked that logout clears its ViewModels, after review.
package com.polysocial.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.user.AccountType
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Stands for the header's `ProfileViewModel`: says whether its owner cleared it. */
class HeaderViewModelProbe : ViewModel() {
  var cleared = false

  override fun onCleared() {
    cleared = true
  }
}

@RunWith(AndroidJUnit4::class)
class AssociationAppShellTest {
  @get:Rule val compose = createComposeRule()
  private lateinit var nav: NavHostController
  private lateinit var logout: () -> Unit
  private var completions = 0
  /** Every ViewModel the header got, as the real header gets its `ProfileViewModel`. */
  private val headerViewModels = mutableSetOf<HeaderViewModelProbe>()

  private fun show(type: AccountType = AccountType.ASSOCIATION) {
    compose.setContent {
      nav = rememberNavController()
      PolySocialTheme {
        AppShell(
            accountType = type,
            navController = nav,
            eventsContent = { open ->
              TextButton({ open("fixture-event") }, Modifier.testTag("open_event")) {
                Text("Fixture event")
              }
            },
            mapContent = { details, _ ->
              TextButton({ details("fixture-event") }, Modifier.testTag("map_details")) {
                Text("Fixture map")
              }
            },
            eventDetailContent = { navigation ->
              Column {
                TextButton(navigation.onBack, Modifier.testTag("detail_back")) {
                  Text("Fixture detail")
                }
                TextButton(navigation.onCancelled, Modifier.testTag("cancel_event")) {
                  Text("Cancel fixture")
                }
              }
            },
            organizationContent = { done, _ ->
              logout = done
              Text("Organization fixture")
            },
            associationHeader = { title ->
              headerViewModels += viewModel<HeaderViewModelProbe>()
              Text(stringResource(title))
            },
            onLoggedOut = { completions++ },
        )
      }
    }
  }

  @Test
  fun associationHasThreeTabsAndNoStudentGraphsOrEditProfileAction() {
    show()
    compose.onNodeWithTag(C.Tag.nav_item_events).assertIsSelected()
    compose.onNodeWithTag(C.Tag.nav_item_map).assertExists()
    compose.onNodeWithTag(C.Tag.nav_item_organization).performClick().assertIsSelected()
    compose.onNodeWithTag(C.Tag.app_bar_title).assertTextEquals("Organization")
    compose.onNodeWithTag(C.Tag.nav_item_chats).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.nav_item_profile).assertDoesNotExist()
    compose.onNodeWithTag(TabAction.EDIT_PROFILE.tag).assertDoesNotExist()
    compose.runOnIdle { assertNull(nav.graph.findNode(Tab.CHATS.route)) }
  }

  @Test
  fun cancelledEventReturnsToEventsAndShowsConfirmation() {
    show()
    compose.onNodeWithTag("open_event").performClick()
    compose.onNodeWithTag("cancel_event").performClick()
    compose.onNodeWithTag("open_event").assertIsDisplayed()
    compose.onNodeWithText("Event cancelled", substring = true).assertIsDisplayed()
  }

  @Test
  fun studentRetainsAllFourTabsAndItsProfileAction() {
    show(AccountType.STUDENT)
    Tab.entries.forEach { compose.onNodeWithTag(it.navItemTag).assertExists() }
    compose.onNodeWithTag(C.Tag.nav_item_organization).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.nav_item_chats).performClick()
    compose.onNodeWithTag(TabAction.SEARCH_CHATS.tag).assertIsDisplayed()
  }

  private fun selectTab(tab: Tab) {
    compose.runOnIdle {
      nav.navigate(tab.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
      }
    }
    compose.waitForIdle()
  }

  @Test
  fun eachAssociationTabRestoresItsOwnDetailAndLogoutDropsAllSavedStacks() {
    show()
    compose.onNodeWithTag("open_event").performClick()
    compose.onNodeWithTag("detail_back").performClick()
    compose.onNodeWithTag(C.Tag.nav_item_map).performClick()
    compose.onNodeWithTag("map_details").performClick()
    compose.onNodeWithTag("detail_back").assertIsDisplayed()
    // Event details hide the bottom bar; drive tab selection to verify saved graph restoration.
    selectTab(Tab.PROFILE)
    compose.onNodeWithText("Organization fixture").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.nav_item_map).performClick()
    compose.onNodeWithTag("detail_back").assertIsDisplayed()
    compose.runOnIdle { assertEquals(Tab.MAP.eventDetailRoute, nav.currentDestination?.route) }
    selectTab(Tab.PROFILE)
    compose.runOnIdle { logout() }
    compose.runOnIdle {
      assertEquals(1, completions)
      assertNull(nav.currentBackStackEntry)
      assertFalse(nav.clearBackStack(Tab.MAP.route))
      assertFalse(nav.clearBackStack(Tab.PROFILE.route))
      assertFalse(nav.popBackStack())
    }
  }

  @Test
  fun theHeadersViewModel_isSharedByEventsAndMap_andClearedByLogout() {
    show()
    compose.onNodeWithTag(C.Tag.nav_item_map).performClick()
    compose.onNodeWithText("Fixture map").assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, headerViewModels.size) }

    // Otherwise the next account to sign in would see this one's identity.
    selectTab(Tab.PROFILE)
    compose.runOnIdle { logout() }
    compose.runOnIdle { assertTrue(headerViewModels.single().cleared) }
  }
}
