// Contributors: Claude (wrote these tests of the Edit event screen, #131).
package com.polysocial.ui.event.create

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polysocial.R
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.UpdateEventResult
import com.polysocial.model.event.withCreator
import com.polysocial.resources.C
import com.polysocial.ui.event.detail.EventDetailViewModel
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** UI tests for Edit event (#131): the Create Event form, filled in, with "Save changes". */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class EditEventScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val repository = FakeEventRepository(currentUid = "organizer", now = FORM_NOW)
  private val stored =
      completeForm()
          .toEvent(ZURICH)!!
          .withCreator("organizer", isVerifiedAssociation = false)
          .copy(id = "event-1")
  private var closes = 0

  private fun string(id: Int) = context.getString(id)

  private fun show(id: String = "event-1", events: EventRepository = repository) {
    val viewModel =
        CreateEventViewModel(
            events,
            Clock.fixed(FORM_NOW, ZURICH),
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to id)),
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

  private fun save() {
    node(C.Tag.create_event_submit).performClick()
    composeTestRule.waitForIdle()
  }

  @Test
  fun theForm_showsTheEventToEditWithSaveChanges() {
    repository.seed(stored)

    show()

    composeTestRule.onNodeWithText(string(R.string.edit_event_title)).assertIsDisplayed()
    node(C.Tag.create_event_title).assertTextContains(stored.title)
    node(C.Tag.create_event_submit).assertTextEquals(string(R.string.edit_event_submit))
  }

  @Test
  fun editingTheTitleAndSaving_updatesTheEventAndCloses() {
    repository.seed(stored)
    show()

    node(C.Tag.create_event_title).performTextReplacement("Exam review")
    save()

    assertEquals("Exam review", repository.updateRequests.single().title)
    assertEquals("Exam review", repository.events.single().title)
    assertEquals(1, closes)
  }

  @Test
  fun savingOffline_showsFigmasErrorAndKeepsTheForm() {
    repository.seed(stored)
    repository.updateFailure = UpdateEventResult.NetworkError
    show()

    node(C.Tag.create_event_title).performTextReplacement("Exam review")
    save()

    node(C.Tag.create_event_failure).performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.edit_event_failed_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.edit_event_offline_body)).assertIsDisplayed()
    node(C.Tag.create_event_submit).assertTextEquals(string(R.string.create_event_try_again))
    node(C.Tag.create_event_title).assertTextContains("Exam review")
    assertEquals(0, closes)
  }

  @Test
  fun anEventThatCannotBeRead_closesTheScreen() {
    show(id = "missing")

    assertEquals(1, closes)
  }

  @Test
  fun whileTheEventIsRead_showsTheLoadingState() {
    // A read that hasn't answered yet.
    val reading =
        object : EventRepository by repository {
          override fun getEvent(id: String) = flow<Nothing> { awaitCancellation() }
        }

    show(events = reading)

    node(C.Tag.loading_state).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.edit_event_loading)).assertIsDisplayed()
  }

  @Test
  fun whileSaving_theButtonSaysSavingChanges() {
    repository.seed(stored)
    repository.pending = CompletableDeferred()
    show()

    save()

    node(C.Tag.create_event_submit).assertTextEquals(string(R.string.edit_event_saving))
    assertEquals(0, closes)
  }
}
