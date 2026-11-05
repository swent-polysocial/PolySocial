// Contributors: Claude (drafted the event detail ViewModel, #112; the server timeout and Retry,
// #194; Edit and Cancel event for organizers, #131; the association's name, #128; the "Going"
// count on an association's manage event, #131).
// Contributors: Claude (Opus 5.5, Anthropic) whether the event has ended, for Find a group, and the
// student's group card, #233.
package com.polysocial.ui.event.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.association.AssociationNames
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.event.DeleteEventResult
import com.polysocial.model.event.Event
import com.polysocial.model.event.EventRepository
import com.polysocial.model.event.EventResult
import com.polysocial.model.event.isLive
import com.polysocial.model.event.isPast
import com.polysocial.model.group.Group
import com.polysocial.model.group.GroupRepository
import com.polysocial.model.group.MyGroupResult
import com.polysocial.model.network.NetworkMonitor
import com.polysocial.model.registration.AttendeeCountResult
import com.polysocial.model.registration.RegistrationRepository
import com.polysocial.model.user.PublicProfile
import com.polysocial.model.user.PublicProfileRepository
import com.polysocial.model.user.PublicProfilesResult
import com.polysocial.ui.group.GroupMember
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
   * @property going the "Going" count, only on [isManaged] events; null otherwise.
   * @property isLive true while the event runs, when it was read (Figma's "Live now" on the manage
   *   screen); it is updated with the event's next change.
   * @property hasEnded true once the event has ended, when it was read ([isPast]), so the detail no
   *   longer offers Find a group; it is updated with the event's next change, like [isLive].
   * @property yourGroup the student's group for the event, which then shows instead of Find a group
   *   (#233); null when they aren't in one, while it is read, when it can't be read, and on
   *   [isManaged] events.
   */
  data class Loaded(
      val details: EventDetails,
      val isOffline: Boolean,
      val isOrganizer: Boolean = false,
      val associationName: String? = null,
      val going: GoingCount? = null,
      val isLive: Boolean = false,
      val hasEnded: Boolean = false,
      val yourGroup: YourGroup? = null,
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
 * associations never reach groups.
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
) : ViewModel() {

  private val eventId = savedStateHandle.get<String>(EVENT_ID_ARG).orEmpty()

  /** Bumped by [retry], so the event is read again. */
  private val reads = MutableStateFlow(0)

  private val _cancelState = MutableStateFlow<CancelEventState>(CancelEventState.Hidden)
  val cancelState: StateFlow<CancelEventState> = _cancelState.asStateFlow()

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
          .combine(watchesGroup.flatMapLatest { if (it) yourGroup() else flowOf(null) }) {
              state,
              group ->
            if (state is EventDetailUiState.Loaded && !state.isManaged)
                state.copy(yourGroup = group)
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

  /**
   * The signed-in student's group for the event, with its members' public profiles, or null. The
   * group shows as soon as it is known, with the profiles already read; the others fill in when
   * their read answers, so a student in a group never sees Find a group while it runs. Only the
   * group matters: when only `fromCache` changes, the profiles aren't read again, unless the last
   * read failed.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun yourGroup(): Flow<YourGroup?> {
    // If the last read of the members' profiles failed, the same group goes through again, so
    // coming back online names them.
    var profilesRead = true
    // The profiles of the last read that worked, shown while the next one runs or if it fails.
    var known = emptyMap<String, PublicProfile>()
    return groups
        .observeMyGroup(eventId)
        .map { (it as? MyGroupResult.Success)?.group }
        .distinctUntilChanged { old, new -> profilesRead && old == new }
        .transformLatest { group ->
          val me = auth.currentUser()?.uid
          if (group == null || me == null) {
            emit(null)
            return@transformLatest
          }
          emit(yourGroup(group, me, known))
          val read = readProfiles(group.memberUids.filter { it != me })
          profilesRead = read != null
          if (read != null) {
            known = read
            emit(yourGroup(group, me, read))
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
    private const val STOP_TIMEOUT_MILLIS = 5_000L

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
