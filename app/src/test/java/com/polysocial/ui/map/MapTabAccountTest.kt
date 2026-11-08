// Contributors: Claude (Opus 5.5, Anthropic) wrote this UI test of whose events each account's Map
// tab shows, with Hilt's real event sources over fake repositories, #133.
package com.polysocial.ui.map

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.MainActivity
import com.polysocial.model.association.AssociationModule
import com.polysocial.model.association.AssociationRepository
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.event.EventModule
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.model.location.FakeLocationService
import com.polysocial.model.location.LocationModule
import com.polysocial.model.location.LocationService
import com.polysocial.model.time.ClockModule
import com.polysocial.model.user.AccountType
import com.polysocial.ui.theme.PolySocialTheme
import com.polysocial.utils.FirebaseStartupRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import java.time.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Map tab's ViewModel for each account type ([mapViewModel], as AppShell picks it) comes from
 * Hilt with the app's real event sources, over in-memory events: a verified association sees only
 * its own upcoming events, private ones included, and a student the public ones (#133). A fake
 * renderer draws one node per marker, so no native map or network request runs.
 */
@HiltAndroidTest
@UninstallModules(
    AssociationModule::class,
    EventModule::class,
    LocationModule::class,
    ClockModule::class,
)
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class MapTabAccountTest {
  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @get:Rule(order = 1) val firebaseStartupRule = FirebaseStartupRule()

  @get:Rule(order = 2) val composeTestRule = createAndroidComposeRule<MainActivity>()

  private val ownPublic =
      validEvent(title = "Ciné-club").copy(id = "own", createdBy = CLUB, isAssociationEvent = true)
  private val ownPrivate = ownPublic.copy(id = "own-private", isPrivate = true)
  private val otherClub = ownPublic.copy(id = "other-club", createdBy = "other")
  private val students = validEvent(title = "Study session").copy(id = "students")
  private val studentsPrivate = students.copy(id = "students-private", isPrivate = true)

  @BindValue
  @JvmField
  val eventRepository: EventRepository =
      FakeEventRepository(currentUid = CLUB).apply {
        seed(ownPublic, ownPrivate, otherClub, students, studentsPrivate)
      }

  // In memory, so the names of associations never reach Firestore.
  @BindValue @JvmField val associations: AssociationRepository = FakeAssociationRepository()

  // Already asked once and refused, so the map shows its notice instead of a permission dialog.
  @BindValue
  @JvmField
  val locationService: LocationService = FakeLocationService().apply { markPermissionRequested() }

  @BindValue @JvmField val clock: Clock = MAP_TEST_CLOCK

  private fun showMapOf(accountType: AccountType) {
    composeTestRule.activity.setContent {
      PolySocialTheme {
        MapRoute(
            onViewDetails = {},
            viewModel = mapViewModel(accountType),
            tokenConfigured = true,
            renderer = { events, _, _, _ ->
              Column { events.forEach { Text(it.title, Modifier.testTag(MapTags.marker(it.id))) } }
            },
        )
      }
    }
    composeTestRule.waitForIdle()
  }

  private fun assertMarkers(shown: List<String>, hidden: List<String>) {
    shown.forEach { composeTestRule.onNodeWithTag(MapTags.marker(it)).assertIsDisplayed() }
    hidden.forEach { composeTestRule.onAllNodesWithTag(MapTags.marker(it)).assertCountEquals(0) }
  }

  @Test
  fun aVerifiedAssociation_seesOnlyItsOwnEvents_privateOnesIncluded() {
    showMapOf(AccountType.ASSOCIATION)

    assertMarkers(
        shown = listOf(ownPublic.id, ownPrivate.id),
        hidden = listOf(otherClub.id, students.id, studentsPrivate.id),
    )
  }

  @Test
  fun aStudent_seesThePublicEvents() {
    showMapOf(AccountType.STUDENT)

    assertMarkers(
        shown = listOf(ownPublic.id, otherClub.id, students.id),
        hidden = listOf(ownPrivate.id, studentsPrivate.id),
    )
  }

  private companion object {
    const val CLUB = "club"
  }
}
