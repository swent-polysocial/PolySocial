// Contributors: Claude (Opus 5.5, Anthropic) wrote these tests of the verified association's header
// on its Events and Map tabs, with fake repositories, #322.
// Contributors: Claude Opus 5.5 (passed the new reminder scheduler fake, #123's second PR review).
package com.polysocial.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.firebase.CacheClearResult
import com.polysocial.model.firebase.SessionCache
import com.polysocial.model.reminder.FakeReminderScheduler
import com.polysocial.model.user.AccountType
import com.polysocial.resources.C
import com.polysocial.ui.association.AssociationTabHeader
import com.polysocial.ui.association.AssociationTags
import com.polysocial.ui.profile.ProfileViewModel
import com.polysocial.ui.theme.PolySocialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The verified association's Events and Map tabs show its header (#322) in AppShell, from its
 * identity in a fake repository; the tabs' contents are fixtures.
 */
@RunWith(AndroidJUnit4::class)
class AssociationHeaderShellTest {
  @get:Rule val compose = createComposeRule()
  private val identity =
      Association("club", "Example Club", AssociationType.CLUB, "Campus activities", null)
  private val associations = FakeAssociationRepository(AssociationResult.Found(identity))
  private val identities =
      ProfileViewModel(
          FakeAuthRepository(AuthUser("club", "club@example.org", true)),
          object : SessionCache {
            override suspend fun clear() = CacheClearResult.Cleared
          },
          associations,
          FakeReminderScheduler(java.time.Clock.systemUTC()),
      )

  private fun show(type: AccountType = AccountType.ASSOCIATION) {
    compose.setContent {
      PolySocialTheme {
        AppShell(
            accountType = type,
            eventsContent = { Text("Events fixture") },
            mapContent = { _, _ -> Text("Map fixture") },
            organizationContent = { _, _ -> Text("Organization fixture") },
            associationHeader = { title -> AssociationTabHeader(title, identities) },
        )
      }
    }
    compose.waitForIdle()
  }

  /** The tab's title in the header, not its label in the bottom bar. */
  private fun heading(title: String) = compose.onNode(isHeading() and hasText(title))

  private fun assertHeader(title: String) {
    compose.onNodeWithTag(AssociationTags.HEADER_NAME).assertTextEquals(identity.name)
    compose
        .onNodeWithTag(AssociationTags.HEADER_VERIFIED, useUnmergedTree = true)
        .assertContentDescriptionEquals("Verified association")
    heading(title).assertIsDisplayed()
  }

  @Test
  fun eventsAndMap_showTheAssociationsHeader_readOnce() {
    show()
    assertHeader("Events")
    compose.onNodeWithTag(C.Tag.app_bar_title).assertDoesNotExist()

    compose.onNodeWithTag(C.Tag.nav_item_map).performClick()
    compose.onNodeWithText("Map fixture").assertIsDisplayed()
    assertHeader("Map")

    assertEquals(listOf("club"), associations.requestedUids)
  }

  @Test
  fun beforeTheIdentityIsRead_onlyTheTitleShows() {
    associations.gate = CompletableDeferred()
    show()

    heading("Events").assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.HEADER_NAME).assertDoesNotExist()
    compose.onNodeWithTag(AssociationTags.HEADER_VERIFIED).assertDoesNotExist()

    compose.runOnIdle { associations.gate?.complete(Unit) }
    assertHeader("Events")
  }

  @Test
  fun anIdentityThatCantBeRead_leavesTheTitle() {
    associations.getAssociationResult = AssociationResult.NetworkError
    show()

    heading("Events").assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.HEADER_NAME).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.nav_item_map).performClick()
    heading("Map").assertIsDisplayed()
    compose.onNodeWithTag(AssociationTags.HEADER_NAME).assertDoesNotExist()
  }

  @Test
  fun organization_keepsItsTitle_andStudentsKeepThePlainAppBar() {
    show()
    compose.onNodeWithTag(C.Tag.nav_item_organization).performClick()
    compose.onNodeWithTag(C.Tag.app_bar_title).assertTextEquals("Organization")
    compose.onNodeWithTag(AssociationTags.HEADER_NAME).assertDoesNotExist()
  }

  @Test
  fun aStudentsEventsTab_keepsThePlainAppBar() {
    show(AccountType.STUDENT)

    compose.onNodeWithTag(C.Tag.app_bar_title).assertTextEquals("Events")
    compose.onNodeWithTag(AssociationTags.HEADER_NAME).assertDoesNotExist()
    assertEquals(emptyList<String>(), associations.requestedUids)
  }
}
