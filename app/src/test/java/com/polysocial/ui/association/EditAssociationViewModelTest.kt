// Contributors: Claude (wrote these tests, #127; emoji at a limit, after a review, #127).
package com.polysocial.ui.association

import com.polysocial.model.association.AssociationType
import com.polysocial.model.association.FakeAssociationRepository
import com.polysocial.model.association.MAX_ASSOCIATION_ABOUT_LENGTH
import com.polysocial.model.association.MAX_ASSOCIATION_NAME_LENGTH
import com.polysocial.model.association.MAX_ASSOCIATION_WEBSITE_LENGTH
import com.polysocial.model.association.UpdateAssociationResult
import com.polysocial.utils.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditAssociationViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val associations = FakeAssociationRepository()

  private fun started() = EditAssociationViewModel(associations).apply { start(testClub) }

  private fun form(
      name: String = "Club",
      about: String = "About",
      website: String = "",
  ) = AssociationForm(name, AssociationType.CLUB, about, website)

  // --- The checks, as the Security Rules make them (#126) ---

  @Test
  fun aBlankNameOrAbout_isMissing_spacesIncluded() {
    assertEquals(
        setOf(AssociationFormError.NAME_MISSING, AssociationFormError.ABOUT_MISSING),
        associationFormErrors(form(name = "  ", about = "\n ")),
    )
    assertEquals(emptySet<AssociationFormError>(), associationFormErrors(form()))
  }

  @Test
  fun theWebsite_isOptional_butMustBeAnHttpsLinkWithoutSpaces() {
    for (valid in listOf("", "   ", "https://example.org", " https://example.org/a?b=c ")) {
      assertEquals(
          valid,
          emptySet<AssociationFormError>(),
          associationFormErrors(form(website = valid)),
      )
    }
    for (invalid in
        listOf(
            "http://example.org",
            "example.org",
            "https://",
            "https:// example.org",
            "https://a b",
        )) {
      assertEquals(
          invalid,
          setOf(AssociationFormError.WEBSITE_INVALID),
          associationFormErrors(form(website = invalid)),
      )
    }
  }

  // --- The form ---

  @Test
  fun start_fillsTheFormFromTheDetails_once() {
    val viewModel = started()
    viewModel.onNameChange("Edited")

    viewModel.start(testClub.copy(name = "Other"))

    assertEquals(
        AssociationForm("Edited", AssociationType.CLUB, testClub.about, "https://example.org"),
        viewModel.uiState.value.form,
    )
  }

  @Test
  fun beforeStart_thereIsNoForm_andSaveDoesNothing() = runTest {
    val viewModel = EditAssociationViewModel(associations)

    viewModel.onNameChange("Name")
    viewModel.save()
    advanceUntilIdle()

    assertNull(viewModel.uiState.value.form)
    assertFalse(viewModel.uiState.value.canSave)
    assertEquals(0, associations.updatedAssociations.size)
  }

  @Test
  fun aMissingWebsite_startsEmpty() {
    val viewModel =
        EditAssociationViewModel(associations).apply { start(testClub.copy(website = null)) }

    assertEquals("", viewModel.uiState.value.form?.website)
  }

  @Test
  fun eachField_stopsAtItsSecurityRulesLimit() {
    val viewModel = started()

    viewModel.onNameChange("n".repeat(MAX_ASSOCIATION_NAME_LENGTH + 5))
    viewModel.onAboutChange("a".repeat(MAX_ASSOCIATION_ABOUT_LENGTH + 5))
    viewModel.onWebsiteChange("https://" + "w".repeat(MAX_ASSOCIATION_WEBSITE_LENGTH))
    viewModel.onTypeChange(AssociationType.EPFL_UNIT)

    val form = viewModel.uiState.value.form!!
    assertEquals(60, form.name.length)
    assertEquals(1000, form.about.length)
    assertEquals(200, form.website.length)
    assertEquals(AssociationType.EPFL_UNIT, form.type)
  }

  @Test
  fun save_sendsTheTrimmedDetails_withTheSameUid_andIsSaved() = runTest {
    val viewModel = started()
    viewModel.onNameChange("  Renamed Club ")
    viewModel.onTypeChange(AssociationType.COMMISSION)
    viewModel.onAboutChange(" New about. ")
    viewModel.onWebsiteChange(" https://example.org/new ")

    viewModel.save()
    assertEquals(EditAssociationStatus.Saving, viewModel.uiState.value.status)
    assertFalse(viewModel.uiState.value.canSave)
    advanceUntilIdle()

    val expected =
        testClub.copy(
            name = "Renamed Club",
            type = AssociationType.COMMISSION,
            about = "New about.",
            website = "https://example.org/new",
        )
    assertEquals(listOf(expected), associations.updatedAssociations)
    assertEquals(EditAssociationStatus.Saved(expected), viewModel.uiState.value.status)
  }

  @Test
  fun anEmptiedWebsite_isRemoved() = runTest {
    val viewModel = started()
    viewModel.onWebsiteChange("   ")

    viewModel.save()
    advanceUntilIdle()

    assertNull(associations.updatedAssociations.single().website)
  }

  @Test
  fun withAnError_saveShowsTheErrors_sendsNothing_andDisablesSaveUntilFixed() = runTest {
    val viewModel = started()
    viewModel.onNameChange("")
    viewModel.onAboutChange(" ")
    // Before Save, nothing shows yet.
    assertEquals(emptySet<AssociationFormError>(), viewModel.uiState.value.visibleErrors)
    assertTrue(viewModel.uiState.value.canSave)

    viewModel.save()
    advanceUntilIdle()

    assertEquals(
        setOf(AssociationFormError.NAME_MISSING, AssociationFormError.ABOUT_MISSING),
        viewModel.uiState.value.visibleErrors,
    )
    assertFalse(viewModel.uiState.value.canSave)
    assertEquals(0, associations.updatedAssociations.size)

    viewModel.onNameChange("Club")
    assertEquals(setOf(AssociationFormError.ABOUT_MISSING), viewModel.uiState.value.visibleErrors)
    viewModel.onAboutChange("About")
    assertTrue(viewModel.uiState.value.canSave)
  }

  @Test
  fun offline_keepsTheForm_andSaysSo_thenTryAgainSaves() = runTest {
    associations.updateAssociationResult = UpdateAssociationResult.NetworkError
    val viewModel = started()
    viewModel.onNameChange("Renamed")

    viewModel.save()
    advanceUntilIdle()

    assertEquals(EditAssociationStatus.Failed(offline = true), viewModel.uiState.value.status)
    assertEquals("Renamed", viewModel.uiState.value.form?.name)
    assertTrue(viewModel.uiState.value.canSave)

    associations.updateAssociationResult = UpdateAssociationResult.Updated
    viewModel.save()
    advanceUntilIdle()

    assertEquals(2, associations.updatedAssociations.size)
    assertTrue(viewModel.uiState.value.status is EditAssociationStatus.Saved)
  }

  @Test
  fun everyOtherFailure_isUnexpected() = runTest {
    for (failure in
        listOf(UpdateAssociationResult.UnexpectedError, UpdateAssociationResult.NotFound)) {
      associations.updateAssociationResult = failure
      val viewModel = started()

      viewModel.save()
      advanceUntilIdle()

      assertEquals(
          "After $failure",
          EditAssociationStatus.Failed(offline = false),
          viewModel.uiState.value.status,
      )
    }
  }

  @Test
  fun editingAfterAFailure_removesItsMessage() = runTest {
    associations.updateAssociationResult = UpdateAssociationResult.NetworkError
    val viewModel = started()
    viewModel.save()
    advanceUntilIdle()

    viewModel.onAboutChange("Changed")

    assertEquals(EditAssociationStatus.Editing, viewModel.uiState.value.status)
  }

  @Test
  fun whileSaving_theFormCantChange_andASecondSaveSendsNothing() = runTest {
    val gate = CompletableDeferred<Unit>()
    associations.gate = gate
    val viewModel = started()
    viewModel.save()
    advanceUntilIdle()

    viewModel.onNameChange("Too late")
    viewModel.onTypeChange(AssociationType.ASSOCIATION)
    viewModel.save()
    gate.complete(Unit)
    advanceUntilIdle()

    assertEquals(1, associations.updatedAssociations.size)
    assertEquals(testClub.name, viewModel.uiState.value.form?.name)
    assertEquals(AssociationType.CLUB, viewModel.uiState.value.form?.type)
  }

  @Test
  fun onceSaved_theFormCantChange_andSaveSendsNothing() = runTest {
    val viewModel = started()
    viewModel.save()
    advanceUntilIdle()

    viewModel.onNameChange("Too late")
    viewModel.save()
    advanceUntilIdle()

    assertEquals(1, associations.updatedAssociations.size)
    assertEquals(testClub.name, viewModel.uiState.value.form?.name)
    assertFalse(viewModel.uiState.value.canSave)
  }

  @Test
  fun aLimit_neverCutsAnEmojiInHalf() {
    val viewModel = started()
    // The limit falls between the two halves of the last emoji.
    val name = "n".repeat(MAX_ASSOCIATION_NAME_LENGTH - 1) + "🎉"

    viewModel.onNameChange(name)

    assertEquals("n".repeat(MAX_ASSOCIATION_NAME_LENGTH - 1), viewModel.uiState.value.form?.name)
    viewModel.onNameChange("n".repeat(MAX_ASSOCIATION_NAME_LENGTH - 2) + "🎉")
    assertEquals(MAX_ASSOCIATION_NAME_LENGTH, viewModel.uiState.value.form?.name?.length)
  }
}
