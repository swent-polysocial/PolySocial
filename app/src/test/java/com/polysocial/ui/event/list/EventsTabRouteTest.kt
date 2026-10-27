// Contributors: Claude (wrote these tests, #132; the association names dependency, #128).
package com.polysocial.ui.event.list

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.association.FakeAssociationNames
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.event.withCreator
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.user.AccountType
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.UserProfile
import com.polysocial.resources.C
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Events tab with its real screens and fake repositories: a verified association sees its own
 * events, a student the public ones (#132).
 */
@RunWith(AndroidJUnit4::class)
class EventsTabRouteTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()

  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val repository = FakeEventRepository(currentUid = "association")
  private val clock = Clock.fixed(repository.now, ZoneId.of("Europe/Zurich"))
  private val network =
      object : NetworkMonitor {
        override fun isOnline() = true
      }

  private val auth = FakeAuthRepository(AuthUser("association", "test@example.com", true))
  private val profile =
      UserProfile(
          uid = "association",
          email = "test@example.com",
          displayName = "Test Association",
          section = "",
          year = "",
          accountType = AccountType.ASSOCIATION,
          isAssociationVerified = true,
      )
  private val profiles = FakeUserProfileRepository(ProfileResult.Found(profile))

  /** The association's own event, which is also public. */
  private val own =
      validEvent(title = "Ciné-club: Dune")
          .withCreator("association", isVerifiedAssociation = true)
          .copy(id = "own")

  /** A student's public event, which the association doesn't organize. */
  private val studentEvent =
      validEvent(title = "Study session")
          .withCreator("student", isVerifiedAssociation = false)
          .copy(id = "student")

  private fun showTab() {
    repository.seed(own, studentEvent)
    // Created once, outside composition, like hiltViewModel() would.
    val tab = EventsTabViewModel(auth, profiles)
    val students =
        EventsViewModel(
            repository,
            network,
            FakeLocationService(),
            clock,
            SavedStateHandle(),
            FakeAssociationNames(),
        )
    val association = AssociationEventsViewModel(repository, network, clock)
    composeTestRule.setContent {
      PolySocialTheme {
        EventsTabRoute(
            onEventClick = {},
            viewModel = tab,
            studentContent = { EventsRoute(onEventClick = {}, viewModel = students) },
            associationContent = {
              AssociationEventsRoute(onEventClick = {}, viewModel = association)
            },
        )
      }
    }
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeTestRule.waitForIdle()
  }

  @Test
  fun aVerifiedAssociation_seesOnlyItsOwnEvents() {
    showTab()

    composeTestRule.onNodeWithTag(AssociationEventsTags.card(own.id)).assertIsDisplayed()
    composeTestRule
        .onAllNodesWithTag(AssociationEventsTags.card(studentEvent.id))
        .assertCountEquals(0)
    composeTestRule.onAllNodesWithTag(EventsTags.card(own.id)).assertCountEquals(0)
  }

  @Test
  fun aStudent_seesThePublicEvents() {
    profiles.getProfileResult =
        ProfileResult.Found(
            profile.copy(accountType = AccountType.STUDENT, isAssociationVerified = false)
        )

    showTab()

    composeTestRule.onNodeWithTag(EventsTags.card(own.id)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(EventsTags.card(studentEvent.id)).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(AssociationEventsTags.UPCOMING_TAB).assertCountEquals(0)
  }

  @Test
  fun aProfileThatCannotBeRead_offersRetry() {
    profiles.getProfileResult = ProfileResult.NetworkError
    showTab()
    composeTestRule
        .onNodeWithText(context.getString(R.string.app_start_error_title))
        .assertIsDisplayed()

    profiles.getProfileResult = ProfileResult.Found(profile)
    composeTestRule.onNodeWithText(context.getString(R.string.retry)).performClick()
    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(AssociationEventsTags.card(own.id)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.screen_events).assertIsDisplayed()
  }
}
