// Contributors: OpenAI Codex (profile editor reusing setup fields and chips, #235).
// Contributors: Claude (Sonnet 5.5, Anthropic) (its own screen tag now the pencil is gone, #495).
package com.polysocial.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.user.INTERESTS
import com.polysocial.model.user.SECTIONS
import com.polysocial.model.user.YEARS
import com.polysocial.ui.common.ErrorBanner
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Ink2

object EditProfileTags {
  const val SCREEN = "edit_profile_screen"
  const val BACK = "edit_profile_back"
  const val SECTION = "edit_profile_section"
  const val YEAR = "edit_profile_year"
  const val SAVE = "edit_profile_save"
  const val ERROR = "edit_profile_error"

  fun section(value: String) = "edit_profile_section_$value"

  fun year(value: String) = "edit_profile_year_$value"

  fun interest(value: String) = "edit_profile_interest_$value"
}

/** Navigation boundary: only a confirmed save returns to Profile, once per visit. */
@Composable
fun EditProfileRoute(onBack: () -> Unit, viewModel: EditProfileViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val saved = (state as? EditProfileState.Editing)?.saved == true
  var reported by rememberSaveable { mutableStateOf(false) }
  val back by rememberUpdatedState(onBack)
  LaunchedEffect(saved) {
    if (saved && !reported) {
      reported = true
      back()
    }
  }
  EditProfileScreen(
      state,
      onBack,
      viewModel::retry,
      viewModel::section,
      viewModel::year,
      viewModel::toggleInterest,
      viewModel::save,
  )
}

/** Reuses onboarding's fixed-list pickers and optional interest chips, without editing identity. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(
    state: EditProfileState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSection: (String) -> Unit,
    onYear: (String) -> Unit,
    onInterest: (String) -> Unit,
    onSave: () -> Unit,
) {
  val form = state as? EditProfileState.Editing
  val locked = form?.locked == true
  BackHandler(locked) {}
  Column(
      Modifier.fillMaxSize()
          .background(Bg)
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .testTag(EditProfileTags.SCREEN)
  ) {
    Column(Modifier.padding(horizontal = 20.dp)) {
      ProfileTopBar(onBack, !locked, step = null, backTag = EditProfileTags.BACK)
      Spacer(Modifier.height(11.5.dp))
      StepHeading(R.string.action_edit_profile, R.string.edit_profile_subtitle)
    }
    when (state) {
      EditProfileState.Loading -> LoadingState(stringResource(R.string.edit_profile_loading))
      EditProfileState.CouldNotLoad ->
          ErrorState(
              stringResource(R.string.edit_profile_load_failed),
              stringResource(R.string.edit_profile_load_failed_body),
              onRetry,
          )
      is EditProfileState.Editing ->
          Column(
              Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
          ) {
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Picker(
                  R.string.profile_section,
                  state.section,
                  SECTIONS,
                  onSection,
                  EditProfileTags.SECTION,
                  EditProfileTags::section,
                  14.dp,
                  Modifier.weight(1f),
                  !locked,
              )
              Picker(
                  R.string.profile_year,
                  state.year,
                  YEARS,
                  onYear,
                  EditProfileTags.YEAR,
                  EditProfileTags::year,
                  13.dp,
                  Modifier.weight(1f),
                  !locked,
              )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.profile_interests_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.profile_interests_subtitle),
                color = Ink2,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              INTERESTS.forEach { code ->
                InterestChip(
                    code,
                    code in state.interests,
                    !locked,
                    { onInterest(code) },
                    EditProfileTags.interest(code),
                )
              }
            }
            Spacer(Modifier.height(24.dp))
            state.failure?.let {
              ErrorBanner(
                  stringResource(R.string.edit_profile_save_failed),
                  stringResource(
                      if (it == EditProfileState.Failure.OFFLINE) R.string.edit_profile_offline
                      else R.string.edit_profile_save_failed_body
                  ),
                  it == EditProfileState.Failure.OFFLINE,
                  EditProfileTags.ERROR,
              )
              Spacer(Modifier.height(16.dp))
            }
            PrimaryButton(
                stringResource(
                    if (state.failure == null) R.string.edit_profile_save else R.string.retry
                ),
                enabled = !locked && state.valid && state.changed,
                tag = EditProfileTags.SAVE,
                loading = state.saving,
                onClick = onSave,
            )
            Spacer(Modifier.height(28.dp))
          }
    }
  }
}
