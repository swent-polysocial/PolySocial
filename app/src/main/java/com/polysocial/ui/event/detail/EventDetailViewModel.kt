// Contributors: OpenAI Codex (GPT-6.2 Sol, high; venue check-in for #469 under Lucas Pinto
// Ricardo’s supervision and approval).
// Contributors: Claude (drafted the event detail ViewModel, #112; the server timeout and Retry,
// #194; Edit and Cancel event for organizers, #131; the association's name, #128; the "Going"
// count on an association's manage event, #131).
// Contributors: Claude (Opus 5.5, Anthropic) whether the event has ended, for Find a group, and the
// student's group card, #233.
// Contributors: Claude Opus 5.5 ("I'm going" and Cancel registration, #122).
// Contributors: Claude Opus 5.5 (the "Remind me" toggle, its permission flow and cancel-on-
// unregister wiring, second PR of #123).
// Contributors: Claude Opus 5.5 (reconciled the reminder's real state on a failed cancellation
// instead of claiming Off, and guarded the initial isScheduled() read against a stale overwrite,
// second PR's review).
// Contributors: Claude Opus 5.5 (also guard the initial read against cancelling the registration,
// not just the toggle and permission result, #123's second PR, Mehdi's review).
// Contributors: Claude Opus 5.5 (carry the signed-in account's UID when scheduling, so
// ReminderWorker can refuse to notify a different account, #123's second PR, Mehdi's review).
// Contributors: Claude Fable 5.2 (a student organizer's name, read only for the students going
// and the organizers, #281).
// Contributors: Claude (Opus 5.5, Anthropic) (no organizer name read from a stale request when the
// screen comes back, and an answered read kept, also when the connection changes; whether a read
// runs, so the screen holds the name's place from the first state, #281's review).
// Contributors: Claude (Opus 5.5, Anthropic) (every organizer's name, the co-organizers after the
// creator, for the same viewers as the creator's, #371).
// Contributors: Claude (Opus 5.5, Anthropic) (a full event's refusal shown as the usual failure,
// #373).
// Contributors: Claude (Sonnet 5.5, Anthropic) asked before a student in a group cancels, then
// leaves the group and cancels the registration, #318.
// Contributors: Claude (Opus 5.5, Anthropic) ("Event full", from the event and from a refused
// registration, #407; only a refusal counts while the registrations are unreadable, after review).
// Contributors: Claude (Opus 5.5, Anthropic) (whether check-in has closed, for Show entrance QR,
// #538 review); Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.AssociationNames
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.isExactEpflEmail
import com.polysocial.model.checkin.checkInWindow
import com.polysocial.model.event.DeleteEventResult
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.event.isFull
import com.polysocial.model.event.isLive
import com.polysocial.model.event.isPast
import com.polysocial.model.group.Group
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.GroupWriteResult
import com.polysocial.model.group.MyGroupResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.AttendeeCountResult
import com.polysocial.model.registration.CancelRegistrationResult
import com.polysocial.model.registration.MyRegistrationsResult
import com.polysocial.model.registration.RegisterResult
import com.polysocial.model.registration.RegistrationRepository
import com.polysocial.model.reminder.CancelReminderResult
import com.polysocial.model.reminder.EventReminder
import com.polysocial.model.reminder.ReminderScheduler
import com.polysocial.model.reminder.ScheduleReminderResult
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfileResult
import com.polysocial.model.user.PublicProfilesResult
import com.polysocial.ui.event.checkin.CheckInEntry
import com.polysocial.ui.event.checkin.CheckInTarget
import com.polysocial.ui.event.checkin.checkInAvailability
import com.polysocial.ui.group.GroupMember
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * An event with its times in the device's time zone, as the detail screen shows them.
 *
 * @property endTime the end time, or null if the organizer didn't give one.
 * @property endDayOffset how many days after [date] the event ends: n > 0 shows "+n day(s)" next to
 *   the end time ("+1 day" for an overnight event), 0 means it ends the same day or has no end
 *   time.
 */
data class EventDetails(
    val event: Event,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime?,
    val endDayOffset: Int,
)

/** Returns [event] with its times read in [zone]. */
fun eventDetails(event: Event, zone: ZoneId): EventDetails {
  val start = event.startTime.atZone(zone)
  val end = event.endTime?.atZone(zone)
  val offset = end?.let { ChronoUnit.DAYS.between(start.toLocalDate(), it.toLocalDate()) } ?: 0
  return EventDetails(
      event = event,
      date = start.toLocalDate(),
      startTime = start.toLocalTime(),
      endTime = end?.toLocalTime(),
      endDayOffset = offset.toInt(),
  )
}

/** The "Going" count on an association's "manage event" (#131). */
sealed interface GoingCount {
  data object Loading : GoingCount

  /** [count] students are registered. */
  data class Count(val count: Long) : GoingCount

  /** The count couldn't be read (offline or a failure); tapping it counts again. */
  data object Unavailable : GoingCount
}

/**
 * The signed-in student's group for the event, for the card on the detail (Figma "Event detail · in
 * a group", #233): [members] in the group's order, with their public profiles when they could be
 * read, and [myUid], the student's own UID among them.
 */
data class YourGroup(
    val groupId: String,
    val capacity: Int,
    val members: List<GroupMember>,
    val myUid: String,
)

/** Whether the signed-in student said "I'm going" to the event (#122). */
enum class Attendance {
  /** Their registrations aren't read yet: "I'm going" shows, but can't be tapped. */
  UNKNOWN,
  NOT_GOING,
  GOING,
}

/** Why the last "I'm going" or "Cancel registration" didn't go through, shown once with Retry. */
enum class AttendanceMessage {
  REGISTER_OFFLINE,
  REGISTER_FAILED,
  CANCEL_OFFLINE,
  CANCEL_FAILED,

  /**
   * The registration itself was cancelled, but [ReminderScheduler.cancel] then failed: the reminder
   * may still fire. Retry re-runs [EventDetailViewModel.onCancelRegistration], whose registration
   * side is idempotent, so it only retries the reminder cleanup.
   */
  REMINDER_CLEANUP_FAILED,
}

/**
 * One failed save, with an [id] that tells two failures with the same [message] apart (for example
 * two offline Retries in a row), so the screen's Snackbar shows the second one too.
 */
data class AttendanceFeedback(val message: AttendanceMessage, val id: Long)

/**
 * Internal: the "Remind me 1 h before" toggle's state, combined into [EventDetailUiState.Loaded].
 */
private data class ReminderUiState(
    val on: Boolean = false,
    val blocked: Boolean = false,
    val showExplanation: Boolean = false,
    val requestPermission: Boolean = false,
)

/** What the event detail screen shows (Figma section 03, "Event detail" and its states). */
sealed interface EventDetailUiState {
  data object Loading : EventDetailUiState

  /**
   * The event.
   *
   * @property isOffline true when the device is offline and the event comes from the offline cache,
   *   so the screen says it shows the details saved on the phone.
   * @property isOrganizer true when the signed-in user organizes the event, so the screen offers
   *   Edit and Cancel event (#131).
   * @property associationName the name of the association that publishes the event, once read
   *   (#128); null for a student's event, and while or when it can't be read.
   * @property organizerNames the names of the event's organizers, the creator first, then its
   *   co-organizers in the order they were added, shown as "by <names>" (#281, #371); empty unless
   *   the viewer may see them ([namedOrganizers]), and while or when they can't be read. An
   *   organizer whose profile is missing or can't be read is left out.
   * @property isReadingOrganizerNames true while [organizerNames] are read for a viewer who may see
   *   them, so the screen holds its row's place instead of moving what is below when they arrive.
   * @property going the "Going" count, only on [isManaged] events; null otherwise.
   * @property isLive true while the event runs, when it was read (Figma's "Live now" on the manage
   *   screen); it is updated with the event's next change.
   * @property hasEnded true once the event has ended, when it was read ([isPast]), so the detail no
   *   longer offers Find a group; it is updated with the event's next change, like [isLive].
   * @property checkInClosed true once the event's [checkInWindow] has closed, when it was read, so
   *   organizers no longer get Show entrance QR (#468); later than [hasEnded] for an event without
   *   an end time, whose check-in stays open for hours after its start.
   * @property yourGroup the student's group for the event, which then shows instead of Find a group
   *   (#233); null when they aren't in one, while it is read, when it can't be read, and on
   *   [isManaged] events.
   * @property groupConfirmed true once a successful read says whether the student is in a group, so
   *   a null [yourGroup] then means they aren't. False while it is read, when it can't be read (the
   *   read then ends until it is restarted), when only the offline cache has answered with no group
   *   (the server may say otherwise) and on [isManaged] events; cancelling the registration needs
   *   it, so an unconfirmed read is never taken for a group that is gone (#318).
   * @property attendance whether the student is going (#122); organizers aren't offered "I'm
   *   going", as in Figma's "Event detail · organizer", so theirs stays [Attendance.NOT_GOING].
   * @property isSavingAttendance true while "I'm going" or "Cancel registration" is saved.
   * @property isFull true when the event has no place left for this student (#407): the event as
   *   read is full ([Event.isFull]), also from the offline cache, or the last "I'm going" was
   *   refused as full and the event hasn't changed since. While the student's registrations can't
   *   be read, only that refusal counts, since they may be going: "I'm going" then stays their way
   *   back to "✓ Going". The screen shows "Event full" instead of "I'm going" to a student known
   *   not to be going; always false for organizers.
   * @property reminderOn whether "Remind me 1 h before" is on, shown once going (#123); seeded once
   *   from [ReminderScheduler.isScheduled] the first time attendance is confirmed.
   * @property remindersBlocked true once Android's notification permission is known to be denied,
   *   so the toggle shows "Off: notifications are blocked" instead of the normal subtitle.
   * @property showReminderExplanation true while the permission explanation sheet shows, before
   *   Android's own prompt (first time the toggle is turned on without a known granted permission).
   * @property requestReminderPermission one-shot: true right after "Continue" on the explanation
   *   sheet, so the screen launches the system permission request; the screen acknowledges it.
   */
  data class Loaded(
      val details: EventDetails,
      val isOffline: Boolean,
      val isOrganizer: Boolean = false,
      val associationName: String? = null,
      val organizerNames: List<String> = emptyList(),
      val isReadingOrganizerNames: Boolean = false,
      val going: GoingCount? = null,
      val isLive: Boolean = false,
      val hasEnded: Boolean = false,
      val checkInClosed: Boolean = false,
      val yourGroup: YourGroup? = null,
      val groupConfirmed: Boolean = false,
      val attendance: Attendance = Attendance.NOT_GOING,
      val isSavingAttendance: Boolean = false,
      val isFull: Boolean = false,
      val reminderOn: Boolean = false,
      val remindersBlocked: Boolean = false,
      val showReminderExplanation: Boolean = false,
      val requestReminderPermission: Boolean = false,
  ) : EventDetailUiState {
    /**
     * True when the signed-in association organizes this association event: the screen is then
     * Figma's "Association · manage event" instead of the detail students see (#131).
     */
    val isManaged: Boolean
      get() = isOrganizer && details.event.isAssociationEvent
  }

  /** The event doesn't exist (anymore), or the user isn't allowed to see it. */
  data object NotFound : EventDetailUiState

  /**
   * The event was never loaded on the device, and the device is offline or the server didn't answer
   * within [EventDetailViewModel.SERVER_ANSWER_TIMEOUT_MILLIS]. It loads once the server answers,
   * or the user taps Retry.
   */
  data object OfflineNotSaved : EventDetailUiState
}

/**
 * The UIDs of the organizers whose names this viewer may see on the event (#281), empty when they
 * may see none: the organizers of a student's public event, the creator first, then the
 * co-organizers in the order they were added (#371), for the event's organizers and the students
 * registered for it ("I'm going"). Everyone else gets no name, so a public event doesn't show its
 * organizers to every student; co-organizers follow the creator's rule, since they never agreed to
 * be named (decided by the Sprint 6 PO on #371). An association's event shows the association
 * instead (#128), and a private event names nobody until its own decision.
 *
 * The names are only hidden on screen (decided on #281, see CONTEXT.md): the organizers' UIDs stay
 * in the event every student can read.
 */
private fun EventDetailUiState.Loaded.namedOrganizers(): List<String> {
  val event = details.event
  val maySee =
      (isOrganizer || attendance == Attendance.GOING) &&
          !event.isAssociationEvent &&
          !event.isPrivate
  if (!maySee) return emptyList()
  return (listOf(event.createdBy) + event.organizerIds).filter { it.isNotBlank() }.distinct()
}

/**
 * The organizers whose names are read for the viewer ([namedOrganizers]). [offline] isn't sent with
 * the read: it makes the event no longer showing as offline a new request, so a read that failed
 * for the network is tried again then ([OrganizerRead.answers]). A network that Android reports but
 * Firestore can't reach doesn't change it, so the name then waits for the next request (the screen
 * coming back, or the registration changing).
 */
private data class OrganizerRequest(val uids: List<String>, val offline: Boolean)

/** The organizers whose names this state's viewer may see ([namedOrganizers]), if any. */
private fun EventDetailUiState.Loaded.organizerRequest() =
    namedOrganizers().takeIf { it.isNotEmpty() }?.let { OrganizerRequest(it, isOffline) }

/**
 * The answer to a read of the names of the organizers [uids]: [names], in the same order, without
 * the organizers whose profile is missing, unreadable or has no name; [failedForNetwork] when a
 * read failed for the network.
 */
private data class OrganizerRead(
    val uids: List<String>,
    val names: List<String>,
    val failedForNetwork: Boolean = false,
) {
  /**
   * Whether this answer still holds for [request]: the same organizers, and reads that didn't fail
   * for the network, or did while the event still shows as offline.
   */
  fun answers(request: OrganizerRequest) =
      uids == request.uids && (request.offline || !failedForNetwork)
}

/** The answer to [request]: the organizers' [names], empty without any or without a request. */
private data class OrganizerAnswer(
    val request: OrganizerRequest?,
    val names: List<String> = emptyList(),
)

/**
 * This state with the organizers' names from [answer], for a viewer who may see them: checked again
 * here, so the names go at once when the registration is cancelled. Until the answer to this
 * state's own request comes, names already read for the same organizers stay, and without them the
 * row holds its place ([EventDetailUiState.Loaded.isReadingOrganizerNames]).
 */
private fun EventDetailUiState.withOrganizer(answer: OrganizerAnswer): EventDetailUiState {
  if (this !is EventDetailUiState.Loaded) return this
  val request = organizerRequest() ?: return this
  val names = answer.names.takeIf { answer.request?.uids == request.uids }.orEmpty()
  return copy(
      organizerNames = names,
      isReadingOrganizerNames = names.isEmpty() && answer.request != request,
  )
}

/** Where "Cancel event" is (Figma section 10, "Cancel event · …"). */
sealed interface CancelEventState {
  /** The confirmation isn't shown. */
  data object Hidden : CancelEventState

  /** The confirmation asks whether to cancel the event. */
  data object Confirming : CancelEventState

  /** The event is being deleted; the confirmation can't be dismissed meanwhile. */
  data object Cancelling : CancelEventState

  /** The event wasn't cancelled; the confirmation stays with Try again. */
  data class Failed(val reason: Reason) : CancelEventState {
    enum class Reason {
      OFFLINE,
      UNEXPECTED,
    }
  }

  /** The event was deleted: the screen closes. */
  data object Cancelled : CancelEventState
}

/**
 * The "Cancel your registration?" confirmation a student in a group sees before cancelling (#318):
 * cancelling also leaves the group, since a student can't be in a group for an event they aren't
 * registered for (#244).
 */
sealed interface CancelRegistrationState {
  /** The confirmation isn't shown. */
  data object Hidden : CancelRegistrationState

  /** The confirmation asks whether to cancel the registration and leave the group. */
  data object Confirming : CancelRegistrationState

  /** The group is being left; the confirmation can't be dismissed meanwhile. */
  data object Leaving : CancelRegistrationState

  /** The group wasn't left, so the registration is untouched; the confirmation stays. */
  data class Failed(val reason: Reason) : CancelRegistrationState {
    enum class Reason {
      OFFLINE,
      UNEXPECTED,
    }
  }
}

/**
 * Shows the event whose ID is the navigation argument [EVENT_ID_ARG], live: the screen updates when
 * the event changes, shows "not found" once it is deleted, and loads it by itself when the device
 * comes back online.
 *
 * Firestore's first snapshot often comes from the offline cache even while online (the map and the
 * Events tab cache every public event), and the server's answer follows a moment later. So "from
 * the cache" only counts as offline when [network] says the device is offline too; otherwise the
 * screen shows the cached event normally, or keeps loading while the server answers.
 *
 * Android can report a working network while Firestore can't reach its server (a firewall blocking
 * Firestore, a server outage, a connection that died silently). Firestore then withholds its cached
 * "not saved" answer until it decides by itself that it is offline, or answers nothing at all. So
 * each read loads for at most [SERVER_ANSWER_TIMEOUT_MILLIS] from its start while online, then
 * shows [EventDetailUiState.OfflineNotSaved] with Retry. The read keeps listening after the
 * timeout, so a late answer from the server still shows the event.
 *
 * Its organizers can also cancel it ([cancelState]): a confirmation, then
 * [EventRepository.deleteEvent] (in Sprint 3, cancelling deletes the event). A failed or refused
 * delete is never reported as cancelled: the confirmation stays with Try again. While it is
 * deleted, the event's live read reports it missing, so the screen keeps showing the event until it
 * closes.
 *
 * An association's event shows the association's name (#128), read once through the shared
 * [associationNames]; the event shows at once, and the name joins it when it arrives.
 *
 * On an event it manages ([EventDetailUiState.Loaded.isManaged]), an association also sees how many
 * students are going (#131), counted once from the server through [registrations] when the event
 * first shows. The event doesn't wait for it; offline or on a failure the count is
 * [GoingCount.Unavailable] until [retryGoingCount].
 *
 * On the detail students see, the student's group for the event is observed through [groups] once
 * the event shows (#233), with its members' public profiles read through [profiles] each time the
 * group changes. It joins the event when it arrives; when the student isn't in a group, or it can't
 * be read, the screen keeps Find a group. An association's managed event never reads it, since
 * associations never reach groups. A student who doesn't organize the event can say "I'm going"
 * ([onImGoing]) and cancel it ([onCancelRegistration]) through [registrations] (#122). Their
 * registrations are observed live while the screen shows, so a registration changed elsewhere (My
 * events) shows here too. If they can't be read, the screen offers "I'm going": registering twice
 * changes nothing. A save that fails changes nothing and reports [attendanceFeedback], whose Retry
 * saves again; each failure gets its own id, so a Retry that fails the same way is reported again
 * too. A registration refused because the event is full reports nothing: the screen shows "Event
 * full" instead ([EventDetailUiState.Loaded.isFull], #407).
 *
 * A student's public event names its organizers to the viewers [namedOrganizers] allows
 * (#281, #371), from their public profiles, read through [profiles] only for them, then kept while
 * this ViewModel lives and the organizers don't change; only reads that failed for the network are
 * tried again, once the event no longer shows as offline or when the screen comes back
 * ([OrganizerRequest]). The event never waits for them, and a profile that is missing or can't be
 * read just leaves that name out: a missing profile doesn't mean a deleted account (#291).
 *
 * Once going, "Remind me 1 h before" ([EventDetailUiState.Loaded.reminderOn], second PR of #123)
 * schedules or cancels through [reminders]: its state is seeded once from
 * [ReminderScheduler.isScheduled], turning it off or cancelling the registration both cancel the
 * reminder, and the Android 13+ permission explanation and request are driven by one-shot flags the
 * screen (which owns the actual permission UI) acknowledges.
 *
 * @param auth tells whether the signed-in user is one of the event's organizers.
 * @param clock gives the device's time zone, in which the times are shown, and the time that tells
 *   whether the event is live or has ended.
 */
@HiltViewModel
class EventDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: EventRepository,
    private val network: NetworkMonitor,
    private val auth: AuthRepository,
    private val clock: Clock,
    private val associationNames: AssociationNames,
    private val registrations: RegistrationRepository,
    private val groups: GroupRepository,
    private val profiles: PublicProfileRepository,
    private val reminders: ReminderScheduler,
) : ViewModel() {

  private val eventId = savedStateHandle.get<String>(EVENT_ID_ARG).orEmpty()

  /** Bumped by [retry], so the event is read again. */
  private val reads = MutableStateFlow(0)

  private val _cancelState = MutableStateFlow<CancelEventState>(CancelEventState.Hidden)
  val cancelState: StateFlow<CancelEventState> = _cancelState.asStateFlow()

  private val _registrationSheet =
      MutableStateFlow<CancelRegistrationState>(CancelRegistrationState.Hidden)
  val registrationSheet: StateFlow<CancelRegistrationState> = _registrationSheet.asStateFlow()

  /** The event last shown, kept on screen while it is deleted. */
  private var lastLoaded: EventDetailUiState.Loaded? = null

  /** The association whose name this screen asked for, so it is asked for once. */
  private var requestedName: String? = null

  /** The "Going" count, null until a managed event shows. */
  private val going = MutableStateFlow<GoingCount?>(null)

  /** Whether this screen counted the managed event's attendees, so they are counted once. */
  private var goingCounted = false

  /** True once a student's view of the event shows, so the student's group is observed. */
  private val watchesGroup = MutableStateFlow(false)

  /** Restarts the read of the student's group, which ends when it fails. */
  private val groupReads = MutableStateFlow(0)

  /**
   * The last answered read of the organizers' names, kept while this ViewModel lives, also while
   * the screen is stopped.
   */
  private var organizerRead: OrganizerRead? = null

  /**
   * The event as shown when the last "I'm going" was refused as full (#407). The refusal read the
   * event on the server, which may be newer than the event shown (for example from the cache), so
   * the event shows as full until its own read delivers a different version, which then decides
   * alone and clears it: a place freed since then offers "I'm going" again.
   */
  private val refusedFull = MutableStateFlow<Event?>(null)

  /** The attendance this screen saved, until the registrations' next answer replaces it. */
  private val savedAttendance = MutableStateFlow<Attendance?>(null)

  private val savingAttendance = MutableStateFlow(false)

  /** Bumped on every failed save, so each [AttendanceFeedback] is its own Snackbar. */
  private var nextAttendanceFeedbackId = 0L

  private val _attendanceFeedback = MutableStateFlow<AttendanceFeedback?>(null)
  val attendanceFeedback: StateFlow<AttendanceFeedback?> = _attendanceFeedback.asStateFlow()

  /** Whether [initReminderOnce] has already seeded [reminderOn] for this screen. */
  private var reminderInitialized = false

  /**
   * Set as soon as the student acts on the toggle or the permission result, so [initReminderOnce]'s
   * asynchronous [ReminderScheduler.isScheduled] read, if it is still running, never overwrites
   * that action with its own now-stale snapshot.
   */
  private var reminderUserActed = false

  /**
   * The reminder this screen last put on the device (or tried to), so [resyncReminder] schedules
   * again only when the event's time, title or place changed. Null until then.
   */
  private var syncedReminder: EventReminder? = null

  private val reminderOn = MutableStateFlow(false)
  private val remindersBlocked = MutableStateFlow(false)
  private val showReminderExplanation = MutableStateFlow(false)
  private val requestReminderPermission = MutableStateFlow(false)

  private val reminderUi: Flow<ReminderUiState> =
      combine(reminderOn, remindersBlocked, showReminderExplanation, requestReminderPermission) {
          on,
          blocked,
          showExplanation,
          requestPermission ->
        ReminderUiState(on, blocked, showExplanation, requestPermission)
      }

  /**
   * Read alongside the event, so it is usually known when the event shows. An association's read is
   * refused (it never registers) and its own events never offer "I'm going" anyway.
   */
  private val observedAttendance: Flow<Attendance?> =
      registrations
          .observeMyRegistrations()
          .map { result ->
            when (result) {
              is MyRegistrationsResult.Registrations ->
                  if (eventId in result.eventIds) Attendance.GOING else Attendance.NOT_GOING
              // Unreadable: shown as NOT_GOING, since registering twice changes nothing, so
              // "I'm going" stays safe to offer.
              MyRegistrationsResult.Error -> null
            }
          }
          .onStart { emit(Attendance.UNKNOWN) }

  /** The attendance shown, whether a save runs, and whether the registrations couldn't be read. */
  private data class AttendanceRead(
      val attendance: Attendance,
      val saving: Boolean,
      val unreadable: Boolean,
  )

  /**
   * [saved] while the observed registrations haven't caught up to it yet, [observed] once they have
   * confirmed it (and for any later, live change). Clearing [savedAttendance] only once it is
   * confirmed, instead of on every new observed value, keeps an unrelated snapshot from briefly
   * showing the old [observed] value while a save is still catching up.
   */
  private val attendance: Flow<AttendanceRead> =
      combine(observedAttendance, savedAttendance, savingAttendance) { observed, saved, saving ->
        if (saved != null && saved == observed) savedAttendance.value = null
        AttendanceRead(saved ?: observed ?: Attendance.NOT_GOING, saving, observed == null)
      }

  @OptIn(ExperimentalCoroutinesApi::class)
  val uiState: StateFlow<EventDetailUiState> =
      reads
          .flatMapLatest { eventStates().onStart { emit(EventDetailUiState.Loading) } }
          .onEach(::loadAssociationName)
          .combine(associationNames.names, ::withAssociationName)
          .onEach(::countGoingOnce)
          .combine(going) { state, count ->
            if (state is EventDetailUiState.Loaded && state.isManaged)
                state.copy(going = count ?: GoingCount.Loading)
            else state
          }
          .onEach {
            if (it is EventDetailUiState.Loaded && !it.isManaged) watchesGroup.value = true
          }
          .combine(
              watchesGroup
                  .combine(groupReads) { watches, _ -> watches }
                  .flatMapLatest { if (it) yourGroup() else flowOf(GroupRead()) }
          ) { state, read ->
            if (state is EventDetailUiState.Loaded && !state.isManaged)
                state.copy(yourGroup = read.group, groupConfirmed = read.confirmed)
            else state
          }
          .combine(attendance.combine(refusedFull, ::Pair)) { state, (read, refused) ->
            if (state is EventDetailUiState.Loaded && !state.isOrganizer) {
              val event = state.details.event
              // A newer version decides alone, even one that later goes back to the refused one.
              if (refused != null && event != refused) refusedFull.compareAndSet(refused, null)
              state.copy(
                  attendance = read.attendance,
                  isSavingAttendance = read.saving,
                  isFull = (event.isFull() && !read.unreadable) || event == refused,
              )
            } else state
          }
          .withOrganizerName()
          .onEach(::initReminderOnce)
          .onEach(::resyncReminder)
          .combine(reminderUi) { state, reminder ->
            if (state is EventDetailUiState.Loaded && !state.isOrganizer)
                state.copy(
                    reminderOn = reminder.on,
                    remindersBlocked = reminder.blocked,
                    showReminderExplanation = reminder.showExplanation,
                    requestReminderPermission = reminder.requestPermission,
                )
            else state
          }
          .combine(_cancelState) { state, cancel ->
            val deleting =
                cancel == CancelEventState.Cancelling || cancel == CancelEventState.Cancelled
            if (state is EventDetailUiState.Loaded) lastLoaded = state
            if (deleting && state !is EventDetailUiState.Loaded) lastLoaded ?: state else state
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
              EventDetailUiState.Loading,
          )

  /** Only a registered, verified student who does not organize the event sees check-in. */
  @OptIn(ExperimentalCoroutinesApi::class)
  internal fun checkInEntry(isStudent: Boolean): Flow<CheckInEntry?> =
      if (!isStudent) flowOf(null)
      else
          uiState
              .map { state ->
                val loaded = state as? EventDetailUiState.Loaded
                val user = auth.currentUser()
                if (
                    loaded != null &&
                        !loaded.isOrganizer &&
                        loaded.attendance == Attendance.GOING &&
                        user != null &&
                        user.isEmailVerified &&
                        isExactEpflEmail(user.email)
                )
                    loaded.details.event to user.uid
                else null
              }
              .distinctUntilChanged()
              .flatMapLatest { eventAndUid ->
                if (eventAndUid == null) flowOf(null)
                else {
                  val (event, uid) = eventAndUid
                  val target = CheckInTarget(event, uid)
                  checkInAvailability(target.window, clock).map {
                    CheckInEntry(event, target, it, clock.zone)
                  }
                }
              }

  /**
   * These states with the organizers' names, for the viewers [namedOrganizers] allows (#281). Whose
   * names they need is worked out again on each run of [uiState], so when the screen comes back
   * nothing is read before the event and the registrations say again who may see it.
   */
  private fun Flow<EventDetailUiState>.withOrganizerName(): Flow<EventDetailUiState> = flow {
    val requests = MutableStateFlow<OrganizerRequest?>(null)
    emitAll(
        this@withOrganizerName.onEach {
              requests.value = (it as? EventDetailUiState.Loaded)?.organizerRequest()
            }
            .combine(organizerAnswers(requests), EventDetailUiState::withOrganizer)
    )
  }

  /**
   * The answer to each of [requests], once there is one: the organizers' names from their public
   * profiles, without those whose profile is missing, unreadable or has no name. They are read once
   * and kept, so the screen coming back, the registration cancelled and made again or the
   * connection changing costs no read ([OrganizerRead.answers]).
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun organizerAnswers(requests: Flow<OrganizerRequest?>): Flow<OrganizerAnswer> =
      requests.mapLatest { request ->
        if (request == null) return@mapLatest OrganizerAnswer(null)
        val known = organizerRead?.takeIf { it.answers(request) }
        OrganizerAnswer(
            request,
            (known ?: readOrganizers(request.uids).also { organizerRead = it }).names,
        )
      }

  /** Asks once for the name of the association that publishes the event in [state], if any. */
  private fun loadAssociationName(state: EventDetailUiState) {
    val event = (state as? EventDetailUiState.Loaded)?.details?.event ?: return
    if (!event.isAssociationEvent || requestedName == event.createdBy) return
    requestedName = event.createdBy
    viewModelScope.launch { associationNames.load(event.createdBy) }
  }

  /** [state] with its association's name from [names], when it is an association's event. */
  private fun withAssociationName(
      state: EventDetailUiState,
      names: Map<String, String>,
  ): EventDetailUiState {
    val event = (state as? EventDetailUiState.Loaded)?.details?.event ?: return state
    return if (event.isAssociationEvent) state.copy(associationName = names[event.createdBy])
    else state
  }

  /** Counts the attendees of the managed event in [state], the first time it shows. */
  private fun countGoingOnce(state: EventDetailUiState) {
    if (goingCounted || state !is EventDetailUiState.Loaded || !state.isManaged) return
    goingCounted = true
    countGoing()
  }

  private fun countGoing() {
    going.value = GoingCount.Loading
    viewModelScope.launch {
      going.value =
          when (val result = registrations.countAttendees(eventId)) {
            is AttendeeCountResult.Count -> GoingCount.Count(result.count)
            AttendeeCountResult.NetworkError,
            AttendeeCountResult.UnexpectedError -> GoingCount.Unavailable
          }
    }
  }

  /** What the read of the student's group says; [confirmed] as in [EventDetailUiState.Loaded]. */
  private data class GroupRead(val group: YourGroup? = null, val confirmed: Boolean = false)

  /**
   * The signed-in student's group for the event, with its members' public profiles, or null. The
   * group shows as soon as it is known, with the profiles already read; the others fill in when
   * their read answers, so a student in a group never sees Find a group while it runs. Only the
   * group matters: when only `fromCache` changes, the profiles aren't read again, unless the last
   * read failed.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun yourGroup(): Flow<GroupRead> {
    // If the last read of the members' profiles failed, the same group goes through again, so
    // coming back online names them.
    var profilesRead = true
    // The profiles of the last read that worked, shown while the next one runs or if it fails.
    var known = emptyMap<String, PublicProfile>()
    return groups
        .observeMyGroup(eventId)
        .map { result ->
          // Only a successful read says whether the student is in a group, and an empty answer from
          // the offline cache doesn't: they may have joined from another device, and the server's
          // answer is still to come. A group from the cache is still shown.
          if (result is MyGroupResult.Success && (result.group != null || !result.fromCache))
              result.group to true
          else null to false
        }
        .distinctUntilChanged { old, new -> profilesRead && old == new }
        .transformLatest { (group, confirmed) ->
          val me = auth.currentUser()?.uid
          if (group == null || me == null) {
            emit(GroupRead(group = null, confirmed = confirmed && me != null))
            return@transformLatest
          }
          emit(GroupRead(yourGroup(group, me, known), confirmed = true))
          val read = readProfiles(group.memberUids.filter { it != me })
          profilesRead = read != null
          if (read != null) {
            known = read
            emit(GroupRead(yourGroup(group, me, read), confirmed = true))
          }
        }
  }

  private fun yourGroup(group: Group, me: String, profiles: Map<String, PublicProfile>) =
      YourGroup(
          groupId = group.id,
          capacity = group.capacity,
          members = group.memberUids.map { GroupMember(it, profiles[it]) },
          myUid = me,
      )

  /** The public profiles of [uids], or null if they couldn't be read. */
  private suspend fun readProfiles(uids: List<String>): Map<String, PublicProfile>? =
      when (val result = profiles.getPublicProfiles(uids)) {
        is PublicProfilesResult.Found -> result.profiles
        PublicProfilesResult.NetworkError,
        PublicProfilesResult.UnexpectedError -> null
      }

  /**
   * Reads the names of the organizers [uids] from their public profiles, one read each, in
   * parallel: one profile that is missing or can't be read leaves only its own name out.
   */
  private suspend fun readOrganizers(uids: List<String>): OrganizerRead {
    val results = coroutineScope { uids.map { async { profiles.getPublicProfile(it) } }.awaitAll() }
    return OrganizerRead(
        uids,
        results.mapNotNull {
          (it as? PublicProfileResult.Found)?.profile?.displayName?.ifBlank { null }
        },
        failedForNetwork = results.any { it == PublicProfileResult.NetworkError },
    )
  }

  /** Counts the attendees again after the count failed (tapping the unavailable count). */
  fun retryGoingCount() {
    if (going.value == GoingCount.Unavailable) countGoing()
  }

  /**
   * Reads the event again, showing [EventDetailUiState.Loading] meanwhile (the offline "not saved"
   * state's Retry). It replaces the previous read, and the new read has its own
   * [SERVER_ANSWER_TIMEOUT_MILLIS].
   */
  fun retry() {
    reads.update { it + 1 }
  }

  /** Asks to confirm "Cancel event". */
  fun onCancelEvent() {
    _cancelState.compareAndSet(CancelEventState.Hidden, CancelEventState.Confirming)
  }

  /** "Keep event", or dismissing the confirmation: nothing changes. Ignored while cancelling. */
  fun onKeepEvent() {
    _cancelState.update {
      if (it == CancelEventState.Cancelling || it == CancelEventState.Cancelled) it
      else CancelEventState.Hidden
    }
  }

  /** Deletes the event, from the confirmation or its Try again. */
  fun onConfirmCancel() {
    val state = _cancelState.value
    if (state != CancelEventState.Confirming && state !is CancelEventState.Failed) return
    _cancelState.value = CancelEventState.Cancelling
    viewModelScope.launch {
      _cancelState.value =
          when (repository.deleteEvent(eventId)) {
            DeleteEventResult.Deleted -> CancelEventState.Cancelled
            DeleteEventResult.NetworkError ->
                CancelEventState.Failed(CancelEventState.Failed.Reason.OFFLINE)
            // Never reported as cancelled. A missing event shows "not found" behind (#131).
            DeleteEventResult.NotOrganizer,
            DeleteEventResult.NotFound,
            DeleteEventResult.UnexpectedError ->
                CancelEventState.Failed(CancelEventState.Failed.Reason.UNEXPECTED)
          }
    }
  }

  /** "I'm going": registers the student. Ignored while a save is running. */
  fun onImGoing() = saveAttendance(going = true)

  /**
   * "Cancel registration": removes the student's registration, after a confirmation that also
   * leaves their group if they are in one (#318). Ignored while a save is running.
   */
  fun onCancelRegistration() {
    val loaded = uiState.value as? EventDetailUiState.Loaded
    when {
      loaded?.yourGroup != null ->
          _registrationSheet.compareAndSet(
              CancelRegistrationState.Hidden,
              CancelRegistrationState.Confirming,
          )
      loaded != null && loaded.isGroupUnknown() && network.isOnline() -> {
        // The student may still be in a group: ask for the read again and offer Retry, rather than
        // cancelling the registration and leaving them in a group without it. Offline, the
        // cancellation itself can't happen (it is a server transaction), so it goes ahead and
        // explains that, as before.
        groupReads.update { it + 1 }
        _registrationSheet.compareAndSet(
            CancelRegistrationState.Hidden,
            CancelRegistrationState.Failed(loaded.groupUnknownReason()),
        )
      }
      else -> saveAttendance(going = false)
    }
  }

  /** True when the student's membership is unknown, so cancelling must wait for a read. */
  private fun EventDetailUiState.Loaded.isGroupUnknown() =
      !isManaged && yourGroup == null && !groupConfirmed

  private fun EventDetailUiState.Loaded.groupUnknownReason() =
      if (isOffline) CancelRegistrationState.Failed.Reason.OFFLINE
      else CancelRegistrationState.Failed.Reason.UNEXPECTED

  /** "Keep going": closes the confirmation, unless the group is being left. */
  fun onKeepRegistration() {
    _registrationSheet.update {
      if (it == CancelRegistrationState.Leaving) it else CancelRegistrationState.Hidden
    }
  }

  /**
   * Confirms the sheet: leaves the group first, then cancels the registration. This order never
   * leaves the student in a group without a registration; if only the cancellation fails, the
   * student is still registered and the usual Snackbar offers Retry. If a successful read says the
   * group is gone by now (left from another device, or deleted), there is nothing to leave and the
   * registration is cancelled straight away, also when retrying after a failure. While the group
   * can't be read, nothing is cancelled: the sheet fails and the read restarts.
   */
  fun onConfirmCancelRegistration() {
    val loaded = uiState.value as? EventDetailUiState.Loaded ?: return
    val group = loaded.yourGroup
    val current = _registrationSheet.value
    if (
        (current != CancelRegistrationState.Confirming &&
            current !is CancelRegistrationState.Failed) ||
            !_registrationSheet.compareAndSet(current, CancelRegistrationState.Leaving)
    )
        return
    viewModelScope.launch {
      val failure =
          when {
            group != null -> leaveFailure(group.groupId, current)
            loaded.isGroupUnknown() -> {
              groupReads.update { it + 1 }
              loaded.groupUnknownReason()
            }
            else -> null
          }
      if (failure == null) {
        _registrationSheet.value = CancelRegistrationState.Hidden
        saveAttendance(going = false)
      } else {
        _registrationSheet.value = CancelRegistrationState.Failed(failure)
      }
    }
  }

  /**
   * Leaves [groupId] and returns why it failed, or null once the student is out. A cancelled call
   * puts the sheet back to [previous] and lets the cancellation through.
   */
  private suspend fun leaveFailure(
      groupId: String,
      previous: CancelRegistrationState,
  ): CancelRegistrationState.Failed.Reason? =
      try {
        when (groups.leaveGroup(eventId, groupId)) {
          is GroupWriteResult.Success -> null
          GroupWriteResult.NetworkError -> CancelRegistrationState.Failed.Reason.OFFLINE
          else -> CancelRegistrationState.Failed.Reason.UNEXPECTED
        }
      } catch (cancelled: CancellationException) {
        _registrationSheet.value = previous
        throw cancelled
      } catch (_: Exception) {
        CancelRegistrationState.Failed.Reason.UNEXPECTED
      }

  /** Clears [shown], unless a newer failure replaced it meanwhile. */
  fun onAttendanceFeedbackShown(shown: AttendanceFeedback) {
    _attendanceFeedback.compareAndSet(shown, null)
  }

  /**
   * Seeds [reminderOn] from [ReminderScheduler.isScheduled] the first time the student is confirmed
   * going, so the toggle shows the reminder's real state instead of always starting off. Dropped if
   * [reminderUserActed] became true while this read was still running, so a toggle or permission
   * result the student already acted on is never overwritten by this now-stale answer.
   */
  private fun initReminderOnce(state: EventDetailUiState) {
    if (reminderInitialized) return
    if (state !is EventDetailUiState.Loaded) return
    if (state.isOrganizer || state.attendance != Attendance.GOING) return
    reminderInitialized = true
    viewModelScope.launch {
      val scheduled = reminders.isScheduled(eventId)
      if (!reminderUserActed) reminderOn.value = scheduled
      // The organizer may have changed the event since the reminder was set: follow it now.
      if (scheduled && !reminderUserActed) resyncReminder(uiState.value)
    }
  }

  /**
   * Keeps an existing reminder in step with the event it belongs to: when its start time, title or
   * place differs from what this screen last scheduled (or from what the device holds, the first
   * time), it is scheduled again, which replaces the earlier one. A reminder whose event now starts
   * within an hour or has started can't be scheduled, so it is cancelled and the switch turns off.
   * A failure leaves the reminder as it was, and the next change tries again (#485).
   */
  private fun resyncReminder(state: EventDetailUiState) {
    if (!reminderInitialized || !reminderOn.value) return
    if (state !is EventDetailUiState.Loaded) return
    if (state.isOrganizer || state.attendance != Attendance.GOING) return
    val wanted = reminderFor(state.details.event) ?: return
    if (wanted == syncedReminder) return
    syncedReminder = wanted
    viewModelScope.launch {
      when (reminders.schedule(wanted)) {
        is ScheduleReminderResult.Scheduled -> Unit
        ScheduleReminderResult.TooLate -> {
          if (reminders.cancel(eventId) == CancelReminderResult.Cancelled) {
            reminderOn.value = false
          } else {
            syncedReminder = null
          }
        }
        ScheduleReminderResult.UnexpectedError -> syncedReminder = null
      }
    }
  }

  /** The reminder of [event] as it is now, or null when nobody is signed in. */
  private fun reminderFor(event: Event): EventReminder? {
    val uid = auth.currentUser()?.uid ?: return null
    return EventReminder(
        eventId = eventId,
        title = event.title,
        startTime = event.startTime,
        uid = uid,
        placeName = event.locationName.ifBlank { null },
    )
  }

  /**
   * Turns the reminder [on] or off directly: used for every Off (no confirmation needed), and for
   * On once the notification permission is already granted (or isn't needed below API 33), so the
   * explanation sheet isn't shown again.
   */
  fun onReminderToggle(on: Boolean) {
    reminderUserActed = true
    if (!on) {
      cancelReminder()
      return
    }
    scheduleReminder()
  }

  /**
   * Cancels the reminder for a direct Off. [reminderOn] only turns false once
   * [ReminderScheduler.cancel] actually confirms it: a failure leaves it on, since the reminder may
   * still fire, instead of claiming Off before that is known.
   */
  private fun cancelReminder() {
    remindersBlocked.value = false
    viewModelScope.launch {
      if (reminders.cancel(eventId) == CancelReminderResult.Cancelled) reminderOn.value = false
    }
  }

  /** The switch was turned on without a known granted permission: show the explanation first. */
  fun onReminderExplanationRequested() {
    showReminderExplanation.value = true
  }

  /** "Continue" on the explanation sheet: hide it and ask the screen to request the permission. */
  fun onReminderExplanationContinue() {
    showReminderExplanation.value = false
    requestReminderPermission.value = true
  }

  /** "Not now": hide the sheet, the toggle stays off. */
  fun onReminderExplanationDismiss() {
    showReminderExplanation.value = false
  }

  /** The screen launched the system permission request for [requestReminderPermission]. */
  fun onReminderPermissionRequested() {
    requestReminderPermission.value = false
  }

  /**
   * The system permission prompt answered, or the screen rechecked on resume after "Open settings".
   * Granted schedules the reminder; denied blocks the toggle and keeps it off.
   */
  fun onReminderPermissionResult(granted: Boolean) {
    reminderUserActed = true
    if (granted) {
      remindersBlocked.value = false
      scheduleReminder()
    } else {
      remindersBlocked.value = true
      reminderOn.value = false
    }
  }

  private fun scheduleReminder() {
    val event = (uiState.value as? EventDetailUiState.Loaded)?.details?.event ?: return
    val reminder = reminderFor(event) ?: return
    syncedReminder = reminder
    viewModelScope.launch {
      val result = reminders.schedule(reminder)
      // TooLate or UnexpectedError: the switch simply doesn't turn on, like a save that fails
      // changes nothing elsewhere on this screen; Figma has no frame for this failure.
      reminderOn.value = result is ScheduleReminderResult.Scheduled
    }
  }

  private fun saveAttendance(going: Boolean) {
    if (!savingAttendance.compareAndSet(expect = false, update = true)) return
    _attendanceFeedback.value = null
    viewModelScope.launch {
      try {
        val failure =
            if (going) {
              when (registrations.register(eventId)) {
                RegisterResult.Registered -> null
                RegisterResult.NetworkError -> AttendanceMessage.REGISTER_OFFLINE
                RegisterResult.Full -> {
                  // No Snackbar: "Event full" replaces "I'm going" and says it, and Retry
                  // couldn't help until someone cancels.
                  refusedFull.value = (uiState.value as? EventDetailUiState.Loaded)?.details?.event
                  return@launch
                }
                RegisterResult.NotSignedIn,
                RegisterResult.NotFound,
                RegisterResult.UnexpectedError -> AttendanceMessage.REGISTER_FAILED
              }
            } else {
              when (registrations.cancel(eventId)) {
                CancelRegistrationResult.Cancelled -> null
                CancelRegistrationResult.NetworkError -> AttendanceMessage.CANCEL_OFFLINE
                CancelRegistrationResult.NotSignedIn,
                CancelRegistrationResult.UnexpectedError -> AttendanceMessage.CANCEL_FAILED
              }
            }
        var feedback = failure
        if (failure == null) {
          savedAttendance.value = if (going) Attendance.GOING else Attendance.NOT_GOING
          // The reminder stops making sense once the registration it was for is gone.
          if (!going) {
            // Otherwise a still-pending initReminderOnce read, seeded before this cancellation,
            // can finish afterward and resurrect reminderOn from its now-stale snapshot.
            reminderUserActed = true
            remindersBlocked.value = false
            if (reminders.cancel(eventId) == CancelReminderResult.Cancelled) {
              reminderOn.value = false
            } else {
              // The registration is gone either way; only the local cleanup failed, so [reminderOn]
              // is left as it was (the reminder may still fire) and Retry offers another try.
              feedback = AttendanceMessage.REMINDER_CLEANUP_FAILED
            }
          }
        }
        _attendanceFeedback.value = feedback?.let {
          AttendanceFeedback(it, nextAttendanceFeedbackId++)
        }
      } finally {
        savingAttendance.value = false
      }
    }
  }

  /**
   * One read of the event, live. Its timeout starts with the read, not with the repository's first
   * answer, which may never come; later answers don't restart it.
   */
  private fun eventStates(): Flow<EventDetailUiState> =
      combine(
          // null until the repository answers.
          repository.getEvent(eventId).map<EventResult, EventResult?> { it }.onStart { emit(null) },
          flow {
            emit(false)
            delay(SERVER_ANSWER_TIMEOUT_MILLIS)
            emit(true)
          },
      ) { result, timedOut ->
        stateOf(result, timedOut)
      }

  /**
   * What the screen shows for the repository's latest [result] (null before its first answer),
   * [timedOut] telling whether the read's [SERVER_ANSWER_TIMEOUT_MILLIS] has passed. Before any
   * answer it loads, even offline, until the timeout; a cached "not saved" shows at once offline.
   * The timer firing after an answer recomputes the same state, which the [StateFlow] drops.
   */
  private fun stateOf(result: EventResult?, timedOut: Boolean): EventDetailUiState =
      when (result) {
        is EventResult.Found ->
            EventDetailUiState.Loaded(
                eventDetails(result.event, clock.zone),
                isOffline = result.fromCache && !network.isOnline(),
                isOrganizer = auth.currentUser()?.uid in result.event.organizerIds,
                isLive = result.event.isLive(clock.instant()),
                hasEnded = result.event.isPast(clock.instant()),
                checkInClosed = !clock.instant().isBefore(checkInWindow(result.event).closesAt),
            )
        EventResult.NotFound -> EventDetailUiState.NotFound
        // Even offline, the cache answers a moment later and may have the event.
        null -> if (timedOut) EventDetailUiState.OfflineNotSaved else EventDetailUiState.Loading
        // Online, the server may still answer.
        EventResult.NotCached ->
            if (timedOut || !network.isOnline()) EventDetailUiState.OfflineNotSaved
            else EventDetailUiState.Loading
      }

  companion object {
    /** Name of the navigation argument holding the event's ID. */
    const val EVENT_ID_ARG = "eventId"

    /** Keeps listening this long after the screen stops, so a rotation doesn't restart the read. */
    internal const val STOP_TIMEOUT_MILLIS = 5_000L

    /**
     * How long a read waits for the server while online, from its start, before the screen gives up
     * and shows the offline "not saved" state with Retry. Firestore only reports a missing,
     * never-read event once it has decided by itself that it is offline (or never, if the
     * connection died silently), so without this the screen could load forever. Ten seconds is long
     * enough for a slow but working connection, and the same bound as the session cache cleanup.
     */
    const val SERVER_ANSWER_TIMEOUT_MILLIS = 10_000L
  }
}
