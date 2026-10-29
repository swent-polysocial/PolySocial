// Contributors: OpenAI Codex (association tab graphs and retained stack isolation, #140).
package com.polysocial.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.user.AccountType
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssociationAppShellTest {
  @get:Rule val compose = createComposeRule()
  private lateinit var nav: NavHostController
  private lateinit var logout: () -> Unit
  private var completions = 0

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
              TextButton(navigation.onBack, Modifier.testTag("detail_back")) {
                Text("Fixture detail")
              }
            },
            organizationContent = { done, _ ->
              logout = done
              Text("Organization fixture")
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
    compose.onNodeWithTag("nav_item_organization").performClick().assertIsSelected()
    compose.onNodeWithTag(C.Tag.app_bar_title).assertTextEquals("Organization")
    compose.onNodeWithTag(C.Tag.nav_item_chats).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.nav_item_profile).assertDoesNotExist()
    compose.onNodeWithTag(TabAction.EDIT_PROFILE.tag).assertDoesNotExist()
    compose.runOnIdle { assertNull(nav.graph.findNode(Tab.CHATS.route)) }
  }

  @Test
  fun studentRetainsAllFourTabsAndItsProfileAction() {
    show(AccountType.STUDENT)
    Tab.entries.forEach { compose.onNodeWithTag(it.navItemTag).assertExists() }
    compose.onNodeWithTag("nav_item_organization").assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.nav_item_chats).performClick()
    compose.onNodeWithTag(TabAction.SEARCH_CHATS.tag).assertIsDisplayed()
  }

  @Test
  fun eachAssociationTabRestoresItsOwnDetailAndLogoutDropsAllSavedStacks() {
    show()
    compose.onNodeWithTag("open_event").performClick()
    compose.onNodeWithTag("detail_back").performClick()
    compose.onNodeWithTag(C.Tag.nav_item_map).performClick()
    compose.onNodeWithTag("map_details").performClick()
    compose.onNodeWithTag("nav_item_organization").performClick()
    compose.onNodeWithText("Organization fixture").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.nav_item_map).performClick()
    compose.onNodeWithTag(C.Tag.event_detail_placeholder).assertIsDisplayed()
    compose.onNodeWithTag("nav_item_organization").performClick()
    compose.runOnIdle { logout() }
    compose.runOnIdle {
      assertEquals(1, completions)
      assertNull(nav.currentBackStackEntry)
      assertFalse(nav.clearBackStack(Tab.MAP.route))
      assertFalse(nav.clearBackStack(Tab.PROFILE.route))
      assertFalse(nav.popBackStack())
    }
  }
}
