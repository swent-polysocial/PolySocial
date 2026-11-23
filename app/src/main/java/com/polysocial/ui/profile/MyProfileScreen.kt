// Contributors: Claude (Sonnet 5.5, Anthropic) (the signed-in student's own profile and its Edit
// profile button, #495, from the Figma frames of #494).
package com.polysocial.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.PrimaryButton
import com.polysocial.ui.group.GroupHeader
import com.polysocial.ui.theme.Bg

/** Navigation boundary for My profile (#495); Back calls [onBack], Edit profile [onEdit]. */
@Composable
fun MyProfileRoute(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    viewModel: MyProfileViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  // Reads on opening and again when Edit profile returns, so a saved change shows at once.
  LifecycleResumeEffect(viewModel) {
    viewModel.refresh()
    onPauseOrDispose {}
  }
  MyProfileScreen(state, onBack, onEdit, viewModel::retry)
}

/**
 * The student's profile as others see it: the shared [StudentProfileBody] under the group's Back
 * header, with an Edit profile button once there is something to show or to create. A missing
 * public profile says so in the student's own words, not that the account can't be found.
 */
@Composable
fun MyProfileScreen(
    state: StudentProfileUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
) {
  Column(Modifier.fillMaxSize().background(Bg).testTag(C.Tag.my_profile_screen)) {
    GroupHeader(R.string.my_profile_title, C.Tag.my_profile_back, onBack)
    Box(Modifier.weight(1f)) {
      StudentProfileBody(
          state,
          onRetry,
          notFound = {
            ErrorState(
                title = stringResource(R.string.my_profile_not_ready_title),
                message = stringResource(R.string.my_profile_not_ready_body),
                onRetry = null,
                modifier = Modifier.testTag(C.Tag.my_profile_not_ready),
                icon = R.drawable.ic_tab_profile,
            )
          },
      )
    }
    if (state is StudentProfileUiState.Found || state == StudentProfileUiState.NotFound) {
      PrimaryButton(
          stringResource(R.string.my_profile_edit),
          enabled = true,
          tag = C.Tag.my_profile_edit,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
          onClick = onEdit,
      )
    }
  }
}
