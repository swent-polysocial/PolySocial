// Contributors: Claude Opus 5.5 (medium) (the students' Upcoming and My events segments, #204).
package com.polysocial.ui.event.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.polysocial.R
import com.polysocial.ui.theme.Disabled

object StudentEventsTags {
  const val SEGMENTS = "student_events_segments"
  const val UPCOMING = "student_events_segment_upcoming"
  const val MY_EVENTS = "student_events_segment_my_events"
}

/** The two lists of the students' Events tab. */
enum class StudentEventsSegment {
  /** The upcoming public events, with search and filters (#105, #115). */
  UPCOMING,

  /** The student's chosen events (#204). */
  MY_EVENTS,
}

/**
 * The students' Events tab: Upcoming and My events (Figma section 12). [myEventsViewModel] runs
 * while the tab is shown, whichever segment is selected, so "My events · N" counts the upcoming
 * chosen events, and it keeps their Upcoming/Past split current while resumed. Opening an event
 * from either list calls [onEventClick].
 *
 * @param upcomingContent the Upcoming list; tests replace it, because the real one needs Hilt.
 */
@Composable
fun StudentEventsRoute(
    onEventClick: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
    myEventsViewModel: MyEventsViewModel = hiltViewModel(),
    upcomingContent: @Composable () -> Unit = { EventsRoute(onEventClick) },
) {
  val state by myEventsViewModel.state.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(myEventsViewModel, lifecycleOwner) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      myEventsViewModel.keepSplitCurrent()
    }
  }
  StudentEventsTab(
      myEventsCount = state.upcomingCount,
      modifier = modifier,
      upcoming = upcomingContent,
      myEvents = { onBrowseUpcoming ->
        MyEventsScreen(
            state,
            zone = myEventsViewModel.zone,
            today = myEventsViewModel.today,
            onRetry = myEventsViewModel::retry,
            onEventClick = onEventClick,
            onRemove = myEventsViewModel::remove,
            onBrowseUpcoming = onBrowseUpcoming,
            onMessageShown = myEventsViewModel::messageShown,
        )
      },
  )
}

/**
 * The segmented control above the selected list. The control stays on top in every state of both
 * lists, so the student can always switch. The selected segment survives rotation and coming back
 * from an event's detail. [myEvents] gets a callback that selects Upcoming.
 *
 * @param myEventsCount the count on "My events · N", or null to show "My events" alone.
 */
@Composable
fun StudentEventsTab(
    myEventsCount: Int?,
    modifier: Modifier = Modifier,
    upcoming: @Composable () -> Unit,
    myEvents: @Composable (onBrowseUpcoming: () -> Unit) -> Unit,
) {
  var selected by rememberSaveable { mutableStateOf(StudentEventsSegment.UPCOMING) }
  Column(modifier.fillMaxSize()) {
    Row(
        modifier =
            Modifier.padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 4.dp)
                .fillMaxWidth()
                .background(Disabled, RoundedCornerShape(22.dp))
                .padding(4.dp)
                .testTag(StudentEventsTags.SEGMENTS),
    ) {
      Segment(
          stringResource(R.string.events_segment_upcoming),
          isSelected = selected == StudentEventsSegment.UPCOMING,
          onClick = { selected = StudentEventsSegment.UPCOMING },
          tag = StudentEventsTags.UPCOMING,
      )
      Segment(
          myEventsCount?.let { stringResource(R.string.events_segment_my_events_count, it) }
              ?: stringResource(R.string.events_segment_my_events),
          isSelected = selected == StudentEventsSegment.MY_EVENTS,
          onClick = { selected = StudentEventsSegment.MY_EVENTS },
          tag = StudentEventsTags.MY_EVENTS,
      )
    }
    Box(Modifier.weight(1f)) {
      if (selected == StudentEventsSegment.UPCOMING) upcoming()
      else myEvents { selected = StudentEventsSegment.UPCOMING }
    }
  }
}
