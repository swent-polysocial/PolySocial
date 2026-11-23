// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of the sections picker of Create
// event, #369).
package com.polysocial.ui.event.create

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.model.association.AssociationResult
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.registration.FakeRegistrationRepository
import com.polysocial.model.user.FakePublicProfileRepository
import com.polysocial.model.user.FakeUserProfileRepository
import com.polysocial.model.user.ProfileResult
import com.polysocial.model.user.SECTIONS
import com.polysocial.resources.C
import java.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The sections picker of the Create Event form (#369): who can see a public event. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class EventSectionsScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val events = FakeEventRepository(currentUid = ORGANIZER_UID, now = FORM_NOW)
  private val auth =
      FakeAuthRepository(AuthUser(ORGANIZER_UID, ORGANIZER_EMAIL, isEmailVerified = true))

  private fun viewModel(association: Boolean = false) =
      CreateEventViewModel(
          events,
          Clock.fixed(FORM_NOW, ZURICH),
          SavedStateHandle(),
          auth,
          if (association)
              FakeUserProfileRepository(ProfileResult.Found(verifiedAssociationProfile()))
          else FakeUserProfileRepository(),
          if (association) FakeAssociationRepository(AssociationResult.Found(TEST_CLUB))
          else FakeAssociationRepository(),
          FakeRegistrationRepository(),
          FakePublicProfileRepository(),
      )

  private fun showCreateEvent(viewModel: CreateEventViewModel) {
    composeTestRule.setContent {
      CreateEventScreen(
          viewModel = viewModel,
          onClose = {},
          onViewEvent = {},
          onBackToMap = {},
          locationPicker = { _, _, _, _ -> },
      )
    }
    composeTestRule.waitForIdle()
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  @Test
  fun aPrivateEvent_hasNoSectionsPicker() {
    showCreateEvent(viewModel())

    node(EventSectionsTags.PICKER).assertDoesNotExist()
  }

  @Test
  fun aPublicEvent_offersEverySection_andSaysItIsOpenToAll() {
    showCreateEvent(viewModel())
    node(C.Tag.create_event_public).performScrollTo().performClick()

    node(EventSectionsTags.CAPTION).performScrollTo()
    node(EventSectionsTags.CAPTION)
        .assertTextEquals("Open to all sections. Pick sections to limit who can see it.")
    for (code in SECTIONS) node(EventSectionsTags.chip(code)).performScrollTo().assertIsOff()
  }

  @Test
  fun pickingSections_selectsTheirChips_andNamesThemInTheCaption() {
    val viewModel = viewModel()
    showCreateEvent(viewModel)
    node(C.Tag.create_event_public).performScrollTo().performClick()

    node(EventSectionsTags.chip("SC")).performScrollTo().performClick()
    node(EventSectionsTags.chip("IN")).performScrollTo().performClick()

    node(EventSectionsTags.chip("IN")).assertIsOn()
    node(EventSectionsTags.chip("SC")).assertIsOn()
    node(EventSectionsTags.chip("MA")).assertIsOff()
    node(EventSectionsTags.CAPTION).assertTextEquals("Only students in IN, SC can see it.")
    assertEquals(setOf("IN", "SC"), viewModel.uiState.value.form.allowedSections)
  }

  @Test
  fun unpickingTheLastSection_opensTheEventToAllSectionsAgain() {
    showCreateEvent(viewModel())
    node(C.Tag.create_event_public).performScrollTo().performClick()
    node(EventSectionsTags.chip("IN")).performScrollTo().performClick()
    composeTestRule.waitForIdle()
    node(EventSectionsTags.chip("IN")).assertIsOn()

    node(EventSectionsTags.chip("IN")).performScrollTo().performClick()
    composeTestRule.waitForIdle()

    node(EventSectionsTags.chip("IN")).assertIsOff()
    node(EventSectionsTags.CAPTION)
        .assertTextEquals("Open to all sections. Pick sections to limit who can see it.")
  }

  @Test
  fun makingTheEventPrivate_hidesThePicker_andPublicBringsBackTheChoice() {
    showCreateEvent(viewModel())
    node(C.Tag.create_event_public).performScrollTo().performClick()
    node(EventSectionsTags.chip("MA")).performScrollTo().performClick()

    node(C.Tag.create_event_private).performScrollTo().performClick()
    node(EventSectionsTags.PICKER).assertDoesNotExist()
    node(C.Tag.create_event_public).performScrollTo().performClick()

    node(EventSectionsTags.chip("MA")).performScrollTo().assertIsOn()
  }

  @Test
  fun aVerifiedAssociation_hasNoSectionsPicker_evenForAPublicEvent() {
    showCreateEvent(viewModel(association = true))
    node(C.Tag.create_event_public).performScrollTo().performClick()

    node(EventSectionsTags.PICKER).assertDoesNotExist()
  }

  @Test
  fun aChipReportsItsSection() {
    val toggled = mutableListOf<String>()
    composeTestRule.setContent {
      EventSectionsPicker(selected = setOf("IN"), enabled = true, onToggle = { toggled += it })
    }

    node(EventSectionsTags.chip("MA")).performClick()
    node(EventSectionsTags.chip("IN")).performClick()

    assertEquals(listOf("MA", "IN"), toggled)
  }

  @Test
  fun theChipsAreDisabledWhileTheFormIsLocked() {
    val toggled = mutableListOf<String>()
    composeTestRule.setContent {
      EventSectionsPicker(selected = setOf("IN"), enabled = false, onToggle = { toggled += it })
    }

    for (code in SECTIONS) node(EventSectionsTags.chip(code)).assertIsNotEnabled()
    assertEquals(emptyList<String>(), toggled)
  }
}
