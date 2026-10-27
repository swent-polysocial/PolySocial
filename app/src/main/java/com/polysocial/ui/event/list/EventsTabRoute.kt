// Contributors: Claude (the Events tab for students and for associations, #132).
package com.polysocial.ui.event.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.ui.common.ErrorState
import com.polysocial.ui.common.LoadingState

/**
 * The Events tab's root: the students' upcoming public events, or a verified association's own
 * events (see [EventsTabViewModel]). Opening an event calls [onEventClick] in both.
 *
 * @param studentContent and [associationContent] are the two tabs; tests replace them, because the
 *   real ones need Hilt.
 */
@Composable
fun EventsTabRoute(
    onEventClick: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventsTabViewModel = hiltViewModel(),
    studentContent: @Composable () -> Unit = { EventsRoute(onEventClick, modifier) },
    associationContent: @Composable () -> Unit = { AssociationEventsRoute(onEventClick, modifier) },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  when (state) {
    EventsTabState.LOADING -> LoadingState(stringResource(R.string.loading_events), modifier)
    EventsTabState.ERROR ->
        ErrorState(
            title = stringResource(R.string.app_start_error_title),
            message = stringResource(R.string.app_start_error_message),
            onRetry = viewModel::retry,
            modifier = modifier,
        )
    EventsTabState.STUDENT -> studentContent()
    EventsTabState.ASSOCIATION -> associationContent()
  }
}
