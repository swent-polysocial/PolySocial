// Contributors: OpenAI Codex (GPT-6.2 Sol, high; check-in windows for #469 under Lucas Pinto
// Ricardo’s supervision and approval).
package com.polysocial.ui.event.checkin

import com.polysocial.model.checkin.CheckInWindow
import com.polysocial.model.event.Event
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

internal enum class CheckInAvailability {
  NOT_OPEN,
  OPEN,
  CLOSED,
}

internal data class CheckInEntry(
    val event: Event,
    val target: CheckInTarget,
    val availability: CheckInAvailability,
    val zone: ZoneId,
)

/** Emits the current window and updates it at the next boundary while the detail is visible. */
internal fun checkInAvailability(window: CheckInWindow, clock: Clock) = flow {
  while (true) {
    val now = clock.instant()
    when {
      now.isBefore(window.opensAt) -> {
        emit(CheckInAvailability.NOT_OPEN)
        delayUntil(now, window.opensAt)
      }
      now in window -> {
        emit(CheckInAvailability.OPEN)
        delayUntil(now, window.closesAt)
      }
      else -> {
        emit(CheckInAvailability.CLOSED)
        return@flow
      }
    }
  }
}

private suspend fun delayUntil(now: Instant, boundary: Instant) {
  delay(Duration.between(now, boundary).toKotlinDuration())
}
