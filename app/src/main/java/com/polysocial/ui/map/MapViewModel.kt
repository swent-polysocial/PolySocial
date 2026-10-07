// Contributors: OpenAI Codex (map state, selection and failure handling for #50).
package com.polysocial.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.event.Event
import com.polysocial.model.map.MapEventResult
import com.polysocial.model.map.MapEventSource
import dagger.hilt.android.lifecycle.HiltViewModel
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
  UNAVAILABLE,
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
)

/**
 * Observes events, protects the public map and retains cached markers during transient failures.
 */
@HiltViewModel
class MapViewModel @Inject constructor(private val source: MapEventSource) : ViewModel() {
  private val mutableState = MutableStateFlow(MapUiState())
  val uiState: StateFlow<MapUiState> = mutableState.asStateFlow()
  private var observation: Job? = null

  init {
    observeEvents()
  }

  fun selectEvent(id: String) = mutableState.update {
    it.copy(selectedEvent = it.events.firstOrNull { event -> event.id == id })
  }

  fun closePreview() = mutableState.update { it.copy(selectedEvent = null) }

  fun onRenderStatus(status: MapRenderStatus) = mutableState.update {
    it.copy(renderStatus = status)
  }

  fun retry() {
    mutableState.update {
      it.copy(
          status = MapContentStatus.LOADING,
          renderStatus = MapRenderStatus.LOADING,
          renderGeneration = it.renderGeneration + 1,
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
                MapEventResult.Loading -> previous.copy(status = MapContentStatus.LOADING)
                MapEventResult.Unavailable -> previous.copy(status = MapContentStatus.UNAVAILABLE)
                MapEventResult.Error -> previous.copy(status = MapContentStatus.ERROR)
                is MapEventResult.Events -> {
                  val events = publicMapEvents(result.events)
                  previous.copy(
                      status =
                          if (events.isEmpty()) MapContentStatus.EMPTY else MapContentStatus.READY,
                      events = events,
                      selectedEvent = events.firstOrNull { it.id == previous.selectedEvent?.id },
                  )
                }
              }
            }
          }
    }
  }
}

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
