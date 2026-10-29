// Contributors: OpenAI Codex (Organization loading, identity, retry and shared actions, #140).
package com.polysocial.ui.association

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.*
import com.polysocial.model.auth.*
import com.polysocial.model.firebase.*
import com.polysocial.resources.C
import com.polysocial.ui.profile.*
import com.polysocial.ui.theme.PolySocialTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrganizationScreenTest {
  @get:Rule val compose = createComposeRule()
  private val identity =
      Association(
          "club",
          "Example Club",
          AssociationType.CLUB,
          "Campus activities",
          "https://example.org",
      )

  @Test
  fun ownIdentityAndOptionalWebsiteRenderWithoutInventedStatistics() {
    var state by mutableStateOf<OrganizationUiState>(OrganizationUiState.Ready(identity))
    var privacy = 0
    compose.setContent {
      PolySocialTheme { OrganizationScreen(state, LogoutState.Idle, {}, {}, {}, {}, { privacy++ }) }
    }
    compose.onNodeWithTag(OrganizationTags.Initials).assertTextEquals("EC")
    compose.onNodeWithText(identity.name).assertIsDisplayed()
    compose.onNodeWithTag(OrganizationTags.Verified).assertTextEquals("Verified association")
    compose.onNodeWithText(identity.about).assertIsDisplayed()
    compose.onNodeWithText("https://example.org").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.profile_privacy_entry).performScrollTo().performClick()
    assertEquals(1, privacy)
    compose.runOnIdle { state = OrganizationUiState.Ready(identity.copy(website = null)) }
    compose.onNodeWithText("https://example.org").assertDoesNotExist()
  }

  @Test
  fun unbuiltEntriesExplainTheirAvailabilityWithoutNavigation() {
    compose.setContent {
      PolySocialTheme {
        OrganizationScreen(
            OrganizationUiState.Ready(identity),
            LogoutState.Idle,
            {},
            {},
            {},
            {},
            {},
        )
      }
    }
    for (tag in listOf(OrganizationTags.Members, OrganizationTags.Qr, OrganizationTags.Reports)) {
      compose.onNodeWithTag(tag).performScrollTo().performClick()
      compose.onNodeWithText("Not available yet").assertIsDisplayed()
      compose.onNodeWithTag(OrganizationTags.Screen).assertIsDisplayed()
    }
  }

  @Test
  fun routeLoadsRetriesAndUsesTheSharedBlockingLogoutAndCancel() {
    val auth = FakeAuthRepository(AuthUser("club", "club@example.org", true))
    val repository = FakeAssociationRepository(AssociationResult.NetworkError)
    var cacheResult = CacheClearResult.UnexpectedError
    val cache =
        object : SessionCache {
          override suspend fun clear() = cacheResult
        }
    val model = ProfileViewModel(auth, cache, repository)
    var done = 0
    var privacy = 0
    compose.setContent { PolySocialTheme { OrganizationRoute({ done++ }, { privacy++ }, model) } }
    compose.onNodeWithTag(C.Tag.error_state).assertExists()
    compose.runOnIdle { repository.getAssociationResult = AssociationResult.Found(identity) }
    compose.onNodeWithTag(C.Tag.error_retry).performScrollTo().performClick()
    compose.onNodeWithText(identity.name).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.profile_privacy_entry).performScrollTo().performClick()
    assertEquals(1, privacy)
    compose.onNodeWithTag(ProfileTags.Logout).performScrollTo().performClick()
    compose.onNodeWithTag(ProfileTags.Cancel).performClick()
    compose.onNodeWithTag(ProfileTags.Dialog).assertDoesNotExist()
    compose.onNodeWithTag(ProfileTags.Logout).performClick()
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.onNodeWithTag(ProfileTags.Error).assertIsDisplayed()
    compose.onNodeWithTag(ProfileTags.Cancel).assertDoesNotExist()
    assertEquals(0, done)
    assertNotNull(auth.currentUser())
    compose.runOnIdle { cacheResult = CacheClearResult.Cleared }
    compose.onNodeWithTag(ProfileTags.Confirm).performClick()
    compose.waitForIdle()
    assertEquals(1, done)
    assertNull(auth.currentUser())
  }

  @Test
  fun loadingKeepsSharedPrivacyAndLogoutReachable() {
    compose.setContent {
      PolySocialTheme {
        OrganizationScreen(OrganizationUiState.Loading, LogoutState.Idle, {}, {}, {}, {}, {})
      }
    }
    compose.onNodeWithTag(C.Tag.loading_state).assertExists()
    compose.onNodeWithTag(ProfileTags.Logout).performScrollTo().assertIsDisplayed()
  }

  @Test
  fun largeTextKeepsTheWholeLogoutRowReachableInAShortWindow() {
    var requested = 0
    compose.setContent {
      CompositionLocalProvider(
          LocalDensity provides Density(LocalDensity.current.density, fontScale = 3f)
      ) {
        PolySocialTheme {
          Box(Modifier.height(400.dp)) {
            OrganizationScreen(
                OrganizationUiState.Ready(identity),
                LogoutState.Idle,
                {},
                { requested++ },
                {},
                {},
                {},
            )
          }
        }
      }
    }
    compose.onNodeWithTag(ProfileTags.Logout).performScrollTo().assertIsDisplayed().performClick()
    assertEquals(1, requested)
  }
}
