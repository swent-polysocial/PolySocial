// Contributors: OpenAI Codex (map state, selection and failure handling for #50).
package com.polysocial.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.event.Event
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MapContentStatus {
  LOADING,
  READY,
  EMPTY,
  ERROR,
}

enum class MapRenderStatus {
  LOADING,
  READY,
  ERROR,
}

data class MapUiState(
    val status: MapContentStatus = MapContentStatus.LOADING,
    val events: List<Event> = emptyList(),
    val selectedEvent: Event? = null,
    val renderStatus: MapRenderStatus = MapRenderStatus.LOADING,
    val renderGeneration: Int = 0,
    val selectedEventIsTonight: Boolean = false,
)

/**
 * Observes events, protects the public map and retains cached markers during transient failures.
 */
@HiltViewModel
class MapViewModel
@Inject
constructor(
    private val source: MapEventSource,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
  private val mutableState = MutableStateFlow(MapUiState())
  val uiState: StateFlow<MapUiState> = mutableState.asStateFlow()
  private var observation: Job? = null

  init {
    observeEvents()
  }

  fun selectEvent(id: String) = mutableState.update {
    val selected = it.events.firstOrNull { event -> event.id == id }
    it.copy(selectedEvent = selected, selectedEventIsTonight = isTonight(selected))
  }

  fun closePreview() = mutableState.update { it.copy(selectedEvent = null) }

  fun onRenderStatus(status: MapRenderStatus) = mutableState.update {
    it.copy(renderStatus = status)
  }

  fun retry() {
    mutableState.update {
      val restartRenderer = it.renderStatus == MapRenderStatus.ERROR
      it.copy(
          status = MapContentStatus.LOADING,
          renderStatus = if (restartRenderer) MapRenderStatus.LOADING else it.renderStatus,
          renderGeneration = it.renderGeneration + if (restartRenderer) 1 else 0,
      )
    }
    observeEvents()
  }

  private fun observeEvents() {
    observation?.cancel()
    observation = viewModelScope.launch {
      flow { emitAll(source.observePublicUpcomingEvents()) }
          .catch { emit(MapEventResult.Error) }
          .collect { result ->
            mutableState.update { previous ->
              when (result) {
                MapEventResult.Error -> previous.copy(status = MapContentStatus.ERROR)
                is MapEventResult.Events -> {
                  val events = publicMapEvents(result.events)
                  val selected = events.firstOrNull { it.id == previous.selectedEvent?.id }
                  previous.copy(
                      status =
                          if (events.isEmpty()) MapContentStatus.EMPTY else MapContentStatus.READY,
                      events = events,
                      selectedEvent = selected,
                      selectedEventIsTonight = isTonight(selected),
                  )
                }
              }
            }
          }
    }
  }

  private fun isTonight(event: Event?): Boolean =
      event != null &&
          event.startTime.atZone(MAP_TIME_ZONE).toLocalDate() ==
              LocalDate.now(clock.withZone(MAP_TIME_ZONE)) &&
          event.startTime.atZone(MAP_TIME_ZONE).hour >= 18
}

internal val MAP_TIME_ZONE: ZoneId = ZoneId.of("Europe/Zurich")

/** Rejects private and malformed records before coordinates reach the native renderer. */
internal fun publicMapEvents(events: List<Event>): List<Event> =
    events
        .filter {
          !it.isPrivate &&
              it.id.isNotBlank() &&
              it.location.latitude.isFinite() &&
              it.location.latitude in -90.0..90.0 &&
              it.location.longitude.isFinite() &&
              it.location.longitude in -180.0..180.0
        }
        .distinctBy { it.id }
