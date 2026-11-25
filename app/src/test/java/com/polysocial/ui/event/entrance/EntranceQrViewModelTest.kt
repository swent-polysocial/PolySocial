// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests, test-first, #468); Konstantinos
// Perdikeas (directed, reviewed and owns them).
// Contributors: Claude (Opus 5.5, Anthropic) (a hanging rotation and a schedule change, #538
// review); Konstantinos Perdikeas (directed, reviewed and owns them).
// Contributors: Claude (Opus 5.5, Anthropic) (a first code that never answers, #538 review);
// Konstantinos Perdikeas (directed, reviewed and owns them).
package com.polysocial.ui.event.entrance

import androidx.lifecycle.SavedStateHandle
import com.polysocial.model.checkin.CheckInRepository
import com.polysocial.model.checkin.EntranceCode
import com.polysocial.model.checkin.FakeCheckInRepository
import com.polysocial.model.checkin.RotateCodeResult
import com.polysocial.model.event.FakeEventRepository
import com.polysocial.model.event.validEvent
import com.polysocial.ui.event.detail.EventDetailViewModel
import com.polysocial.utils.MainDispatcherRule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Entrance QR (#468) with fake repositories and a clock that follows the test's virtual time,
 * starting at 19:10 UTC, during the window of an event from 19:00 to 21:00.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntranceQrViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val start = Instant.parse("2026-11-27T19:10:00Z")
  private val clock =
      object : Clock() {
        override fun instant(): Instant = start.plusMillis(main.dispatcher.scheduler.currentTime)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId) = this
      }
  private val eventStart = Instant.parse("2026-11-27T19:00:00Z")
  private val event =
      validEvent("Balélec warm-up", eventStart, eventStart.plus(Duration.ofHours(2)))
          .copy(id = EVENT, locationName = "Satellite")
  private val events = FakeEventRepository().apply { seed(event) }
  private val fake = FakeCheckInRepository()

  /** [fake], with each rotation stamped at the clock's time, like the server's. */
  private val checkIns =
      object : CheckInRepository by fake {
        override suspend fun rotateEntranceCode(eventId: String, replacing: String?) =
            fake.also { it.now = clock.instant() }.rotateEntranceCode(eventId, replacing)
      }

  private fun TestScope.viewModel(): EntranceQrViewModel {
    val vm =
        EntranceQrViewModel(
            SavedStateHandle(mapOf(EventDetailViewModel.EVENT_ID_ARG to EVENT)),
            events,
            checkIns,
            clock,
        )
    backgroundScope.launch { vm.uiState.collect {} }
    runCurrent()
    return vm
  }

  private fun code(token: Char, secondsAgo: Long) =
      EntranceCode(token.toString().repeat(22), null, clock.instant().minusSeconds(secondsAgo))

  private fun shown(vm: EntranceQrViewModel) = vm.uiState.value as EntranceQrUiState.Shown

  @Test
  fun aStoredCode_isShown_withTheEventAndVenue_andNotRotatedYet() =
      runTest(main.dispatcher) {
        fake.seedCode(EVENT, code('a', 10))

        val vm = viewModel()

        val state = shown(vm)
        assertEquals("Balélec warm-up", state.title)
        assertEquals("Satellite", state.venue)
        assertTrue(fake.rotations.isEmpty())
      }

  @Test
  fun anEventWithoutACode_getsItsFirstOne_whichIsThenShown() =
      runTest(main.dispatcher) {
        val vm = viewModel()

        assertEquals(listOf<String?>(null), fake.rotations)
        assertNotNull(fake.codeOf(EVENT))
        shown(vm)
      }

  @Test
  fun aCode_isRotatedOnce_60SecondsAfterItWasIssued_overTheShownToken() =
      runTest(main.dispatcher) {
        fake.seedCode(EVENT, code('a', 10))
        val vm = viewModel()

        advanceTimeBy(49_000)
        runCurrent()
        assertTrue(fake.rotations.isEmpty())

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf<String?>("a".repeat(22)), fake.rotations)
        assertEquals("a".repeat(22), fake.codeOf(EVENT)?.previousToken)
        shown(vm)

        // The new code is rotated 60 s after its own rotation, not before.
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(1, fake.rotations.size)
      }

  @Test
  fun aFailedRotation_isTriedAgainAfter10Seconds_notEveryTick() =
      runTest(main.dispatcher) {
        fake.seedCode(EVENT, code('a', 60))
        fake.rotateFailure = RotateCodeResult.NetworkError
        val vm = viewModel()
        assertEquals(1, fake.rotations.size)

        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(1, fake.rotations.size)
        shown(vm)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, fake.rotations.size)
      }

  @Test
  fun aRotationStillWaitingForAnAnswer_stillHidesTheCodeOnceItIsTooOld() =
      runTest(main.dispatcher) {
        fake.seedCode(EVENT, code('a', 10))
        fake.rotateGate = CompletableDeferred()
        val vm = viewModel()

        // 60 s old: the rotation starts and never answers.
        advanceTimeBy(50_000)
        runCurrent()
        assertEquals(1, fake.rotations.size)
        shown(vm)

        // 110 s old: too old to scan, though the rotation still hasn't answered.
        advanceTimeBy(50_000)
        runCurrent()
        assertEquals(EntranceQrUiState.Failed("Balélec warm-up"), vm.uiState.value)
        assertEquals(1, fake.rotations.size)
      }

  @Test
  fun aCodeTooOldToScan_isNeverShown() =
      runTest(main.dispatcher) {
        // 110 s old: a student scanning now could be refused by the time the rules check it.
        fake.seedCode(EVENT, code('a', 110))
        fake.rotateFailure = RotateCodeResult.UnexpectedError

        val vm = viewModel()

        assertEquals(EntranceQrUiState.Failed("Balélec warm-up"), vm.uiState.value)
      }

  @Test
  fun aCodeOnlyFromTheCache_isHidden_asOffline() =
      runTest(main.dispatcher) {
        fake.seedCode(EVENT, code('a', 10))
        fake.codeFromCache = true

        val vm = viewModel()

        assertEquals(EntranceQrUiState.Offline("Balélec warm-up"), vm.uiState.value)
      }

  @Test
  fun theFirstCodeNotCreated_isOffline_orFailed() =
      runTest(main.dispatcher) {
        fake.rotateFailure = RotateCodeResult.NetworkError
        assertEquals(EntranceQrUiState.Offline("Balélec warm-up"), viewModel().uiState.value)

        fake.rotateFailure = RotateCodeResult.UnexpectedError
        assertEquals(EntranceQrUiState.Failed("Balélec warm-up"), viewModel().uiState.value)
      }

  @Test
  fun aFirstCodeThatNeverAnswers_failsAfter10Seconds_andTryAgainCreatesIt() =
      runTest(main.dispatcher) {
        fake.rotateGate = CompletableDeferred()
        val vm = viewModel()
        assertEquals(EntranceQrUiState.Loading, vm.uiState.value)

        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(EntranceQrUiState.Loading, vm.uiState.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(EntranceQrUiState.Failed("Balélec warm-up"), vm.uiState.value)

        fake.rotateGate = null
        vm.retry()
        runCurrent()
        assertEquals(listOf<String?>(null, null), fake.rotations)
        shown(vm)
      }

  @Test
  fun aFirstCodeStillWaiting_whenTheWindowCloses_saysItClosed() =
      runTest(main.dispatcher) {
        // The window closes at 19:10:05, before the 10 s wait is over.
        events.remove(EVENT)
        events.seed(event.copy(endTime = Instant.parse("2026-11-27T19:10:05Z")))
        fake.rotateGate = CompletableDeferred()
        val vm = viewModel()
        assertEquals(EntranceQrUiState.Loading, vm.uiState.value)

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(
            EntranceQrUiState.Closed("Balélec warm-up", "Satellite", "19:10"),
            vm.uiState.value,
        )
      }

  @Test
  fun aRefusedRead_orAnEventThatCantBeRead_failed() =
      runTest(main.dispatcher) {
        fake.codeReadFailure = true
        assertEquals(EntranceQrUiState.Failed("Balélec warm-up"), viewModel().uiState.value)

        events.remove(EVENT)
        assertEquals(EntranceQrUiState.Failed(null), viewModel().uiState.value)
      }

  @Test
  fun tryAgain_readsEverythingAgain() =
      runTest(main.dispatcher) {
        fake.codeReadFailure = true
        val vm = viewModel()
        fake.codeReadFailure = false

        vm.retry()
        runCurrent()

        shown(vm)
      }

  @Test
  fun beforeTheWindow_itSaysWhenItOpens_withoutTouchingTheCode_thenStartsWhenItOpens() =
      runTest(main.dispatcher) {
        events.remove(EVENT)
        // Starts at 20:00, so the window opens at 19:30, 20 minutes from now.
        events.seed(event.copy(startTime = Instant.parse("2026-11-27T20:00:00Z")))
        fake.codeReadFailure = true
        val vm = viewModel()

        assertEquals(
            EntranceQrUiState.NotOpenYet("Balélec warm-up", "Satellite", "19:30"),
            vm.uiState.value,
        )
        assertTrue(fake.rotations.isEmpty())

        fake.codeReadFailure = false
        advanceTimeBy(Duration.ofMinutes(20).toMillis())
        runCurrent()
        assertEquals(listOf<String?>(null), fake.rotations)
        shown(vm)
      }

  @Test
  fun afterTheWindow_itSaysItClosed_withoutTouchingTheCode() =
      runTest(main.dispatcher) {
        events.remove(EVENT)
        events.seed(event.copy(endTime = Instant.parse("2026-11-27T19:05:00Z")))

        val vm = viewModel()

        assertEquals(
            EntranceQrUiState.Closed("Balélec warm-up", "Satellite", "19:05"),
            vm.uiState.value,
        )
        assertTrue(fake.rotations.isEmpty())
      }

  @Test
  fun theWindowClosingWhileShown_hidesTheCode() =
      runTest(main.dispatcher) {
        events.remove(EVENT)
        events.seed(event.copy(endTime = Instant.parse("2026-11-27T19:10:30Z")))
        fake.seedCode(EVENT, code('a', 10))
        val vm = viewModel()
        shown(vm)

        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(
            EntranceQrUiState.Closed("Balélec warm-up", "Satellite", "19:10"),
            vm.uiState.value,
        )
      }

  @Test
  fun aStartMovedEarlier_whileWaitingForTheWindow_startsTheCodeAtOnce() =
      runTest(main.dispatcher) {
        events.remove(EVENT)
        events.seed(event.copy(startTime = Instant.parse("2026-11-27T20:00:00Z")))
        val vm = viewModel()
        assertEquals(
            EntranceQrUiState.NotOpenYet("Balélec warm-up", "Satellite", "19:30"),
            vm.uiState.value,
        )

        // Another organizer moves it to 19:20: the window opened at 18:50.
        events.remove(EVENT)
        events.seed(event.copy(startTime = Instant.parse("2026-11-27T19:20:00Z")))
        runCurrent()

        assertEquals(listOf<String?>(null), fake.rotations)
        shown(vm)
      }

  @Test
  fun aStartMovedLater_whileShown_hidesTheCode_andSaysWhenItOpens() =
      runTest(main.dispatcher) {
        fake.seedCode(EVENT, code('a', 10))
        val vm = viewModel()
        shown(vm)

        events.remove(EVENT)
        events.seed(event.copy(startTime = Instant.parse("2026-11-27T21:00:00Z")))
        runCurrent()

        assertEquals(
            EntranceQrUiState.NotOpenYet("Balélec warm-up", "Satellite", "20:30"),
            vm.uiState.value,
        )
      }

  private companion object {
    const val EVENT = "event-1"
  }
}
