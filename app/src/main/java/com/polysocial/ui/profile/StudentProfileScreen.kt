// Contributors: Claude Opus 5.5 (a group member's public profile from Figma's #368 frames);
// Konstantinos Perdikeas (directed, reviewed and owns it).
// Contributors: Claude (Sonnet 5.5, Anthropic) (the screen body shared with My profile, #495).
package com.polysocial.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.resources.C
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.LoadingState
import com.polysocial.ui.common.StudentProfileCard
import com.polysocial.ui.group.GroupHeader
import com.polysocial.ui.theme.Bg

/** Navigation boundary for a student's public profile (#368); Back calls [onBack]. */
@Composable
fun StudentProfileRoute(onBack: () -> Unit, viewModel: StudentProfileViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  StudentProfileScreen(state, onBack, viewModel::retry)
}

/**
 * Figma's "Group member" frames (#368): the group's Back header, then the shared
 * [StudentProfileCard], or the app's shared loading and couldn't-load states. A student who isn't
 * on PolySocial any more gets no Retry, since reading again can't bring them back.
 */
@Composable
fun StudentProfileScreen(state: StudentProfileUiState, onBack: () -> Unit, onRetry: () -> Unit) {
  Column(Modifier.fillMaxSize().background(Bg).testTag(C.Tag.student_profile_screen)) {
    GroupHeader(R.string.student_profile_title, C.Tag.student_profile_back, onBack)
    Box(Modifier.weight(1f)) { StudentProfileBody(state, onRetry) }
  }
}

/**
 * What a profile screen shows for [state]: the shared loading and couldn't-load states and the
 * [StudentProfileCard]. [notFound] is what a missing public profile shows: by default the "Can't
 * find this student" state, which My profile (#495) replaces, since it is the student's own.
 */
@Composable
internal fun StudentProfileBody(
    state: StudentProfileUiState,
    onRetry: () -> Unit,
    notFound: @Composable () -> Unit = {
      ErrorState(
          title = stringResource(R.string.student_profile_not_found_title),
          message = stringResource(R.string.student_profile_not_found_body),
          onRetry = null,
          modifier = Modifier.testTag(C.Tag.student_profile_not_found),
          icon = R.drawable.ic_tab_profile,
      )
    },
) {
  when (state) {
    StudentProfileUiState.Loading ->
        LoadingState(
            stringResource(R.string.student_profile_loading),
            Modifier.testTag(C.Tag.student_profile_loading),
        )
    is StudentProfileUiState.Found ->
        StudentProfileCard(
            state.profile,
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
        )
    StudentProfileUiState.NotFound -> notFound()
    StudentProfileUiState.CouldNotLoad ->
        ErrorState(
            title = stringResource(R.string.student_profile_error_title),
            message = stringResource(R.string.student_profile_error_body),
            onRetry = onRetry,
            modifier = Modifier.testTag(C.Tag.student_profile_error),
        )
  }
}
