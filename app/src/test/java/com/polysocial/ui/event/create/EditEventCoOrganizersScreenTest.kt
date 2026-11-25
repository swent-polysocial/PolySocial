// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, #370; the creator's row as
// "Creator", #499).
package com.polysocial.ui.event.create

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.Event
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.OrganizerChanges
import com.polysocial.model.event.withCreator
import com.polysocial.model.registration.AttendeesResult
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.registration.RegistrationRepository
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.resources.C
import com.polysocial.ui.event.detail.EventDetailViewModel
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** UI tests for Edit event's organizers block and the "Add a co-organizer" sheet (#370). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class EditEventCoOrganizersScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val registrations = FakeRegistrationRepository()
  private val events =
      FakeEventRepository(now = FORM_NOW, registeredUids = registrations::attendeesOf)
  private val publicProfiles = FakePublicProfileRepository()
  private var closes = 0

  private val stored =
      completeForm()
          .toEvent(ZURICH)!!
          .withCreator("creator", isVerifiedAssociation = false)
          .copy(
              id = "event-1",
              organizerIds = listOf("creator", "lea"),
              allowedUids = listOf("creator", "lea"),
          )

  init {
    publicProfiles.profiles +=
        listOf(
                PublicProfile("creator", "Franz Keller", "IN", "BA5"),
                PublicProfile("lea", "Léa Martin", "IN", "BA3"),
                PublicProfile("tom", "Tom Rossi", "SC", "BA3", setOf("cinema", "sports")),
            )
            .associateBy { it.uid }
    registrations.seedRegistration("event-1", "lea")
    registrations.seedRegistration("event-1", "tom")
  }

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  private fun show(
      me: String = "creator",
      event: Event = stored,
      attendees: RegistrationRepository = registrations,
      profiles: PublicProfileRepository = publicProfiles,
  ) {
    events.currentUid = me
    events.seed(event)
    val viewModel =
        CreateEventViewModel(
            events,
            Clock.fixed(FORM_NOW, ZURICH),
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to "event-1")),
            FakeAuthRepository(AuthUser(me, "$me@epfl.ch", true)),
            FakeUserProfileRepository(),
            FakeAssociationRepository(),
            attendees,
            profiles,
        )
    composeTestRule.setContent {
      EditEventScreen(
          onClose = { closes++ },
          viewModel = viewModel,
          locationPicker = { _, _, _, _ -> },
      )
    }
    composeTestRule.waitForIdle()
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun click(tag: String) {
    node(tag).performScrollTo().performClick()
    composeTestRule.waitForIdle()
  }

  private fun assertRow(uid: String, name: String, details: String) {
    node(CoOrganizerTags.organizer(uid)).performScrollTo().assertIsDisplayed()
    composeTestRule.onNode(hasText(name).and(hasParentRow(uid))).assertExists()
    composeTestRule.onNode(hasText(details).and(hasParentRow(uid))).assertExists()
  }

  private fun hasParentRow(uid: String) = hasAnyAncestor(hasTestTag(CoOrganizerTags.organizer(uid)))

  @Test
  fun theCreator_seesThemselvesFirstAndEachCoOrganizerWithRemove() {
    show()

    node(CoOrganizerTags.LABEL).performScrollTo().assertTextEquals("Organizers · 2 of 5")
    // #499: "You" over "Creator", written like the co-organizers' rows.
    assertRow("creator", string(R.string.co_organizers_you), "Creator")
    assertRow("lea", "Léa Martin", "IN · BA3 · Co-organizer")
    node(CoOrganizerTags.remove("creator")).assertDoesNotExist()
    node(CoOrganizerTags.remove("lea")).assert(hasContentDescription("Remove Léa Martin"))
    node(CoOrganizerTags.HINT).performScrollTo().assertIsDisplayed()
  }

  @Test
  fun aCoOrganizer_seesTheCreatorByNameAndCanRemoveThemselves() {
    show(me = "lea")

    // #499: the creator's name over "section · year · Creator", as a co-organizer's row.
    assertRow("creator", "Franz Keller", "IN · BA5 · Creator")
    node(CoOrganizerTags.remove("creator")).assertDoesNotExist()
    assertRow(
        "lea",
        string(R.string.co_organizers_you),
        string(R.string.co_organizers_co_organizer),
    )
    node(CoOrganizerTags.remove("lea")).assert(hasContentDescription("Remove yourself"))
  }

  // ---- The creator's row as "Creator" (#499, #498's frames) ----

  @Test
  fun theCreatorAlone_isYouOverCreator_withoutRemove() {
    show(event = stored.copy(organizerIds = listOf("creator"), allowedUids = listOf("creator")))

    node(CoOrganizerTags.LABEL).performScrollTo().assertTextEquals("Organizers · 1 of 5")
    assertRow("creator", string(R.string.co_organizers_you), "Creator")
    node(CoOrganizerTags.remove("creator")).assertDoesNotExist()
  }

  @Test
  fun theCreatorsRow_leavesABlankSectionOrYearOut() {
    publicProfiles.profiles["creator"] = PublicProfile("creator", "Franz Keller", "IN", "")

    show(me = "lea")

    assertRow("creator", "Franz Keller", "IN · Creator")
  }

  @Test
  fun aCreatorWhoseProfileCantBeRead_isStillTheCreator_withoutRemove() {
    publicProfiles.profiles.remove("creator")

    show(me = "lea")

    assertRow(
        "creator",
        string(R.string.co_organizers_unknown),
        string(R.string.co_organizers_creator_profile_unavailable),
    )
    node(CoOrganizerTags.remove("creator")).assertDoesNotExist()
  }

  @Test
  fun noRowSaysCantBeRemovedAnyMore() {
    show()

    composeTestRule.onNodeWithText("can't be removed", substring = true).assertDoesNotExist()
  }

  @Test
  fun removingThemselvesThenSaving_savesAndCloses() {
    show(me = "lea")

    click(CoOrganizerTags.remove("lea"))
    node(CoOrganizerTags.organizer("lea")).assertDoesNotExist()
    node(C.Tag.create_event_submit).performClick()
    composeTestRule.waitForIdle()

    assertEquals(OrganizerChanges(removed = setOf("lea")), events.organizerRequests.single())
    assertEquals(listOf("creator"), events.events.single().organizerIds)
    assertEquals(1, closes)
  }

  @Test
  fun addingAStudentFromTheSheet_putsThemInTheBlock() {
    show()

    click(CoOrganizerTags.ADD)
    composeTestRule.onNodeWithText(string(R.string.add_co_organizer_body)).assertIsDisplayed()
    node(CoOrganizerTags.student("lea")).assertDoesNotExist()
    composeTestRule.onNodeWithText("SC · BA3 · Cinema, Sports").assertIsDisplayed()
    node(CoOrganizerTags.addStudent("tom")).performClick()
    composeTestRule.waitForIdle()

    node(CoOrganizerTags.student("tom")).assertDoesNotExist()
    assertRow("tom", "Tom Rossi", "SC · BA3 · Co-organizer")
    node(CoOrganizerTags.LABEL).assertTextEquals("Organizers · 3 of 5")
  }

  @Test
  fun atFiveOrganizers_addIsDisabledAndTheLimitIsExplained() {
    show(event = stored.copy(organizerIds = listOf("creator", "lea", "a", "b", "c")))

    node(CoOrganizerTags.ADD).performScrollTo().assertIsNotEnabled()
    node(CoOrganizerTags.FULL)
        .performScrollTo()
        .assertTextEquals(context.resources.getQuantityString(R.plurals.co_organizers_full, 5, 5))
    node(CoOrganizerTags.HINT).assertDoesNotExist()
  }

  @Test
  fun whileTheProfilesLoad_theRowsShowPlaceholders_thenTheNames() {
    val answer = CompletableDeferred<Unit>()
    show(
        profiles =
            object : PublicProfileRepository by publicProfiles {
              override suspend fun getPublicProfiles(uids: Collection<String>) =
                  answer.await().let { publicProfiles.getPublicProfiles(uids) }
            }
    )

    node(CoOrganizerTags.organizer("lea")).performScrollTo()
    composeTestRule.onNode(hasText("Léa Martin").and(hasParentRow("lea"))).assertDoesNotExist()
    composeTestRule
        .onNode(hasText(string(R.string.co_organizers_unknown)).and(hasParentRow("lea")))
        .assertDoesNotExist()
    answer.complete(Unit)
    composeTestRule.waitForIdle()

    assertRow("lea", "Léa Martin", "IN · BA3 · Co-organizer")
  }

  @Test
  fun theSheet_showsLoadingUntilTheStudentsGoingAnswer() {
    val answer = CompletableDeferred<Unit>()
    show(
        attendees =
            object : RegistrationRepository by registrations {
              override suspend fun getAttendeeUids(eventId: String) =
                  answer.await().let { registrations.getAttendeeUids(eventId) }
            }
    )

    click(CoOrganizerTags.ADD)
    node(CoOrganizerTags.SHEET_LOADING).assertIsDisplayed()
    node(CoOrganizerTags.student("tom")).assertDoesNotExist()
    answer.complete(Unit)
    composeTestRule.waitForIdle()

    node(CoOrganizerTags.SHEET_LOADING).assertDoesNotExist()
    node(CoOrganizerTags.student("tom")).assertIsDisplayed()
  }

  @Test
  fun aSheetThatCouldntLoad_retries() {
    registrations.attendeesFailure = AttendeesResult.NetworkError
    show()

    click(CoOrganizerTags.ADD)
    node(CoOrganizerTags.SHEET_FAILED).assertIsDisplayed()
    registrations.attendeesFailure = null
    node(CoOrganizerTags.SHEET_RETRY).performClick()
    composeTestRule.waitForIdle()

    node(CoOrganizerTags.student("tom")).assertIsDisplayed()
  }

  @Test
  fun nobodyElseGoing_saysSoAndCloseLeavesTheBlockUnchanged() {
    registrations.attendeesFailure = AttendeesResult.Attendees(listOf("lea"))
    show()

    click(CoOrganizerTags.ADD)
    node(CoOrganizerTags.SHEET_NOBODY).assertIsDisplayed()
    node(CoOrganizerTags.SHEET_CLOSE).performClick()
    composeTestRule.waitForIdle()

    node(CoOrganizerTags.SHEET_NOBODY).assertDoesNotExist()
    node(CoOrganizerTags.ADD).performScrollTo().assertIsEnabled()
    node(CoOrganizerTags.LABEL).assertTextEquals("Organizers · 2 of 5")
  }
}
