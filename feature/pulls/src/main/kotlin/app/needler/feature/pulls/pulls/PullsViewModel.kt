@file:OptIn(ExperimentalTime::class)

package app.needler.feature.pulls.pulls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullTaskId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.core.domain.model.RequestHistoryPage
import app.needler.core.domain.model.WantedList
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.feature.pulls.common.HISTORY_LANE_SUBJECT
import app.needler.feature.pulls.common.WANTED_LANE_SUBJECT
import app.needler.feature.pulls.common.laneProblemMessage
import app.needler.feature.pulls.common.problemMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the Pulls screen off the mirrored `pull` table.
 *
 * Every list on this screen is a Room query behind [PullRepository], so the
 * screen draws instantly and draws offline — REQUIREMENTS.md: "Reads come from
 * the mirrored `pull` table so the Pulls screen renders instantly and offline".
 * The only outbound calls this ViewModel makes are the four the user asks for
 * (cancel, retry, clear done) plus one refresh when the screen opens.
 *
 * ## The 2 s poll, and why it is not a loop in here
 *
 * REQUIREMENTS.md, "Polling schedule", gives the foregrounded Pulls screen a
 * **2 s** cadence against the full task list, and this module's own build file
 * says where that lives: "The 2 s foreground polling and the activity-summary
 * revision check live in `:core:data`; this module consumes the resulting
 * flow." [PullRepository.observePullsLive] is that flow, and collecting it is
 * the whole of this ViewModel's part in the schedule — the poll starts when the
 * flow is collected and stops when it is not, so `WhileSubscribed` below *is*
 * the "while the screen is foregrounded" condition and there is no start/stop
 * pair to get wrong on a rotation.
 *
 * A loop in here was the alternative and is rejected for two reasons that have
 * not changed: this module sees `:core:domain` and `:core:design` only, so it
 * cannot reach `GET /api/v1/downloads`; and a second writer on the `pull` table
 * would put the battery rule in `PullPoller` — "do not poll at all when there
 * are no active pulls" — outside the one place that can enforce it.
 *
 * Live progress is polled rather than streamed. REQUIREMENTS.md records the
 * decision: per-task SSE at `GET /api/v1/downloads/{id}/stream` exists and is
 * not used in v1, because "holding open one connection per task keeps the
 * mobile radio awake and scales badly against a queue of twenty albums".
 *
 * The flow polls before it first sleeps, which is also what makes a screen
 * reached fifteen minutes after the last background poll current immediately —
 * the explicit `refreshPulls` that used to be in [init] for that reason is
 * gone, because it would now be the same call twice.
 *
 * ## Opening the screen clears the badge
 *
 * `PullRepository.markCompletionsSeen` is documented as being "called when the
 * user opens the Pulls screen or taps a 'pull finished' notification", and
 * REQUIREMENTS.md says the badge "updates whenever the app is opened". Both are
 * honoured in [init]: the completions the user is now looking at stop counting
 * towards the badge. **Clear done** calls the same thing again for anything that
 * has landed since, and additionally hides those rows — see [onClearDone].
 *
 * ## The other two lanes are fetched, never polled
 *
 * `GET /api/v1/requests/history` and `GET /api/v1/requests/wanted` have no
 * mirror behind them — `PullRepository.requestHistory`' KDoc sets out why there
 * is no table for one — so they are suspend reads, and this ViewModel decides
 * when to make them. The rule is: **once on first opening the lane, and again
 * only when the user asks.**
 *
 * That is one network call per lane per screen visit, and REQUIREMENTS.md
 * "Battery and data" is why it is not more: item 1 allows "no background polling
 * when no pulls are active", so a second timer against two lists that change on
 * the scale of days would be spending the radio on nothing. The queue's own
 * cadence is untouched; nothing here adds a poll.
 *
 * Three alternatives were weighed and recorded here because each is the obvious
 * one to reach for next:
 *
 *  * **Fetching on every lane selection** was rejected: a tab is a cheap tap and
 *    a user comparing two lists would make a request per tap, which is the
 *    battery rule broken by a different route.
 *  * **Pull-to-refresh** was rejected twice over. This module does not depend on
 *    `material3`'s pull-to-refresh and adding a dependency for one gesture is
 *    not warranted; and REQUIREMENTS.md "Accessibility" requires every control
 *    to be reachable, which a drag with no button beside it is not. The header's
 *    **Refresh** is one tap, one TalkBack focus stop, and no new dependency.
 *  * **Re-fetching automatically when the connection comes back** was rejected
 *    for now: it is a side effect on a connectivity flow that fires on every
 *    network change, and the lane's own **Try again** already covers the case
 *    with the user deciding when. It is the better end state and is a handover
 *    note rather than a hidden cost taken on here.
 */
@HiltViewModel
class PullsViewModel @Inject constructor(
    private val pulls: PullRepository,
    session: SessionRepository,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<PullsNotice?>(null)

    /**
     * Completed pulls the user has cleared from this list.
     *
     * Session-scoped and client-side, because nothing else is available: the
     * repository exposes no "forget this finished task", and `/api/v1/downloads`
     * has no delete in the surface `:core:domain` models. Rebuilding the
     * ViewModel brings them back, which is the honest consequence of the server
     * still holding them — see [PullsNotice.DoneCleared], which says so to the
     * user rather than implying a deletion that did not happen.
     */
    private val cleared = MutableStateFlow<Set<String>>(emptySet())

    private val lane = MutableStateFlow(PullsLane.QUEUE)
    private val history = MutableStateFlow(HistoryLaneState())
    private val wanted = MutableStateFlow(WantedLaneState())

    /**
     * The fetch in flight for each read-through lane, so a second one replaces it.
     *
     * A refresh tapped while a page is still arriving must not land after it and overwrite the newer
     * answer with the older one. Cancelling is the whole of the fix because both lanes are a single
     * call with no partial state to unwind — unlike `refreshPulls`, which walks pages into a table.
     */
    private var historyFetch: Job? = null
    private var wantedFetch: Job? = null

    /**
     * The queue: the task list, the user's parked approvals, and whatever the
     * user has cleared, reconciled into one newest-first sequence.
     */
    private val queue: Flow<List<Pull>> = combine(
        // The polling flow, not the plain one: this is where the 2 s cadence enters the screen.
        pulls.observePullsLive(),
        pulls.observePendingApprovals(),
        cleared,
    ) { tasks, approvals, dismissed ->
        reconcile(tasks, approvals).filterNot { pull ->
            pull.bucket == PullBucket.COMPLETED && dismissed.contains(pull.releaseGroupMbid.value)
        }
    }

    private val status: Flow<Status> = combine(
        pulls.observeActivitySummary(),
        pulls.observePullBadgeCount(),
        session.observeConnectivity(),
    ) { summary: PullActivitySummary?, badge: Int, connectivity: ConnectivityState ->
        Status(
            heldCount = summary?.heldCount ?: 0,
            badgeCount = badge,
            offline = !connectivity.isOnline,
        )
    }

    /**
     * The two read-through lanes and which tab is showing, folded into one flow.
     *
     * Folded rather than combined flat because `combine` is typed to five sources and the queue
     * already takes four. Grouping the three that change together — a tab selection is immediately
     * followed by that lane's fetch — also means a tab tap emits one state rather than three.
     */
    private val lanes: Flow<Lanes> = combine(
        lane,
        history,
        wanted,
    ) { showing, historyState, wantedState ->
        Lanes(showing, historyState, wantedState)
    }

    val state: StateFlow<PullsUiState> = combine(
        queue,
        status,
        busy,
        notice,
        lanes,
    ) { rows, current, isBusy, currentNotice, laneStates ->
        PullsUiState(
            loading = false,
            pulls = rows,
            heldCount = current.heldCount,
            badgeCount = current.badgeCount,
            offline = current.offline,
            busy = isBusy,
            notice = currentNotice,
            renderedAt = now(),
            lane = laneStates.showing,
            history = laneStates.history,
            wanted = laneStates.wanted,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = PullsUiState(),
    )

    init {
        viewModelScope.launch {
            // No `refreshPulls` here: `observePullsLive` polls as soon as it is
            // collected, so asking again would be one redundant page walk per
            // ViewModel. See the class KDoc.
            //
            // `observePulls(bucket)` rather than filtering the merged list: what
            // should stop counting towards the badge is what the *server* says
            // has completed, and folding in pending approvals cannot change that.
            val landed: Set<ReleaseGroupMbid> = pulls.observePulls(PullBucket.COMPLETED)
                .first()
                .map { it.releaseGroupMbid }
                .toSet()
            if (landed.isNotEmpty()) pulls.markCompletionsSeen(landed)
        }
    }

    // ---- intents ------------------------------------------------------------

    /**
     * Stop a pull.
     *
     * Which endpoint this is depends on how far the pull got, and the two are
     * not interchangeable. A pull with a download task is cancelled through
     * `POST /api/v1/downloads/{id}/cancel`; a request the server has not turned
     * into a task yet — a role-`user` request parked for approval — has no task
     * id at all and is cancelled through `DELETE /api/v1/requests/active/{mbid}`.
     *
     * The guard on [Pull.canCancel] is not defensive tidiness: REQUIREMENTS.md
     * says the server *refuses* cancellation during `processing`, because files
     * are being moved, "so callers must gate on the derived state rather than
     * trying and handling the rejection".
     */
    fun onCancel(pull: Pull) {
        if (!pull.canCancel) return
        runExclusively {
            val taskId: PullTaskId? = pull.taskId
            val result: Outcome<Unit> = if (taskId != null) {
                pulls.cancelTask(taskId)
            } else {
                pulls.cancelRequest(pull.releaseGroupMbid)
            }
            when (result) {
                is Outcome.Success -> PullsNotice.Cancelled
                is Outcome.Failure -> PullsNotice.Problem(problemMessage(result.error))
            }
        }
    }

    /**
     * Ask the server to have another go.
     *
     * Legal on failed, cancelled and partial tasks, and routed the same way
     * [onCancel] is: a task id means the download lane, no task id means the
     * request lane. REQUIREMENTS.md warns that the two lanes report refusals
     * with opposite conventions, which is why the answer is turned into a
     * message by `problemMessage` rather than inspected here.
     */
    fun onRetry(pull: Pull) {
        if (!pull.canRetry) return
        runExclusively {
            val taskId: PullTaskId? = pull.taskId
            val result: Outcome<Unit> = if (taskId != null) {
                pulls.retryTask(taskId)
            } else {
                pulls.retryRequest(pull.releaseGroupMbid)
            }
            when (result) {
                is Outcome.Success -> PullsNotice.RetryPlaced
                is Outcome.Failure -> PullsNotice.Problem(problemMessage(result.error))
            }
        }
    }

    /**
     * The header's **Clear done**.
     *
     * Two things, because the pack's label promises one and the repository can
     * only do the other. The rows are hidden locally, which is what "clear done"
     * looks like to the person who tapped it; and `markCompletionsSeen` is
     * called, which is the only server-visible effect available and the one that
     * matters — it takes those albums out of the tab badge.
     *
     * Deliberately not `runExclusively`: hiding rows is local and instant, and
     * blocking it behind an in-flight cancel would make the control feel broken.
     */
    fun onClearDone() {
        val done: List<Pull> = state.value.completed
        if (done.isEmpty()) return
        cleared.value = cleared.value + done.map { it.releaseGroupMbid.value }
        notice.value = PullsNotice.DoneCleared
        viewModelScope.launch {
            pulls.markCompletionsSeen(done.map { it.releaseGroupMbid }.toSet())
        }
    }

    fun onDismissNotice() {
        notice.value = null
    }

    // ---- the other two lanes ------------------------------------------------

    /**
     * Switch tabs, and fetch the lane the first time it is looked at.
     *
     * The guard on [LaneStatus.UNASKED] is the whole of the fetch policy: a lane that has already
     * answered is not asked again on a tab tap, and a lane that **failed** is not asked again either
     * — it keeps its explanation and its **Try again**, rather than re-running a call against a
     * server that just refused one every time the user touches the tab. See the class KDoc for the
     * alternatives this is chosen over.
     */
    fun onSelectLane(selected: PullsLane) {
        if (lane.value == selected) return
        lane.value = selected
        when (selected) {
            // Nothing to fetch: the queue is a Room query that is already being collected, and its
            // poll started when the screen did.
            PullsLane.QUEUE -> Unit

            PullsLane.HISTORY ->
                if (history.value.status == LaneStatus.UNASKED) fetchHistory(LaneFetch.FIRST)

            PullsLane.WANTED ->
                if (wanted.value.status == LaneStatus.UNASKED) fetchWanted(LaneFetch.FIRST)
        }
    }

    /**
     * The header's **Refresh**, and the lane message's **Try again**.
     *
     * Always starts the showing lane again from the beginning. For history that means page one and a
     * replaced list, which is the only honest answer when the server may have inserted rows above
     * page two since the first fetch — appending onto a list whose earlier pages have shifted would
     * duplicate some requests and silently drop others.
     *
     * A no-op on the queue. That lane is repainting on its own 2 s cadence, so a refresh control
     * there would do what is already happening; the header does not draw one.
     */
    fun onRefreshLane() {
        when (lane.value) {
            PullsLane.QUEUE -> Unit
            PullsLane.HISTORY -> fetchHistory(LaneFetch.REFRESH)
            PullsLane.WANTED -> fetchWanted(LaneFetch.REFRESH)
        }
    }

    /**
     * The foot of the history list: fetch the next page and append it.
     *
     * Guarded on [HistoryLaneState.hasMore], which is `RequestHistoryPage`'s own three-branch
     * answer, preferring `total_pages` over arithmetic over the blind test. This is the only list in
     * the request lane that can be paged at all — REQUIREMENTS.md: "Only
     * `GET /api/v1/requests/history` pages" — and the only one that knows where the end is, which is
     * why the control can name the page it is about to ask for.
     *
     * A failed append leaves `hasMore` true, because the page that failed was never recorded, so the
     * same tap retries the same page. That is deliberate: the alternative is a dead control under a
     * list the user can see is incomplete.
     */
    fun onLoadMoreHistory() {
        val current: HistoryLaneState = history.value
        if (current.appending || !current.hasMore) return
        fetchHistory(LaneFetch.APPEND)
    }

    /**
     * **Retry** on a history row: `POST /api/v1/requests/retry/{mbid}`.
     *
     * Offered only where `RequestOutcome.isRetryable` allows it — failed and cancelled, never
     * rejected. That exclusion is the domain's and the reason is recorded there: retrying a declined
     * request "re-asks an administrator who has already said no", and the endpoint answers `200`
     * with `success=false` for it anyway, which the user would read as a bug in Needler rather than
     * as their server's policy.
     *
     * The history row is deliberately **not** re-fetched afterwards. [PullsNotice.RetryPlaced]
     * already says what happens next — the request comes back as a fresh pull — and the Pulls tab is
     * where it will appear. Re-fetching would cost a second call, throw away however many pages the
     * user had loaded, and lose their place in a list they were reading, to change one word in one
     * row they are about to navigate away from.
     */
    fun onRetryRequest(entry: RequestHistoryEntry) {
        if (!entry.canRetry) return
        runExclusively {
            when (val result: Outcome<Unit> = pulls.retryRequest(entry.releaseGroupMbid)) {
                is Outcome.Success -> PullsNotice.RetryPlaced
                is Outcome.Failure -> PullsNotice.Problem(problemMessage(result.error))
            }
        }
    }

    // ---- internals ----------------------------------------------------------

    /** Which of the three shapes of fetch this is; they differ only in how a failure is reported. */
    private enum class LaneFetch { FIRST, REFRESH, APPEND }

    /**
     * One call to `GET /api/v1/requests/history`, and where its answer goes.
     *
     * The three failure routes are the reason [LaneFetch] exists rather than a boolean:
     *
     *  * an **append** that fails puts its reason in the lane's own more-row, next to the tap that
     *    caused it, and leaves every row already on screen alone;
     *  * a **first** fetch that fails has no rows to leave alone, so the lane itself becomes the
     *    message — which is where the offline explanation belongs;
     *  * a **refresh** that fails with rows still on screen keeps them and reports the failure as a
     *    notice, because REQUIREMENTS.md makes offline "a first-class state, not an error" and
     *    blanking a list the user was reading in order to display an error is the opposite of that.
     */
    private fun fetchHistory(kind: LaneFetch) {
        val page: Int = if (kind == LaneFetch.APPEND) history.value.paging.page + 1 else FIRST_PAGE
        historyFetch?.cancel()
        historyFetch = viewModelScope.launch {
            history.value = when (kind) {
                LaneFetch.APPEND -> history.value.copy(appending = true, problem = null)
                LaneFetch.FIRST, LaneFetch.REFRESH -> history.value.copy(
                    status = LaneStatus.LOADING,
                    appending = false,
                    problem = null,
                )
            }
            when (val result: Outcome<RequestHistoryPage> = pulls.requestHistory(page = page)) {
                is Outcome.Success -> {
                    val fetched: RequestHistoryPage = result.value
                    // Append onto what is there, or replace it. A refresh replaces for the reason
                    // [onRefreshLane] gives: the pages below may have moved.
                    val existing: List<RequestHistoryEntry> =
                        if (kind == LaneFetch.APPEND) history.value.entries else emptyList()
                    history.value = HistoryLaneState(
                        status = LaneStatus.LOADED,
                        entries = existing + fetched.entries,
                        paging = fetched,
                    )
                }

                is Outcome.Failure -> {
                    val message: String =
                        laneProblemMessage(result.error, HISTORY_LANE_SUBJECT)
                    val keptRows: Boolean = history.value.entries.isNotEmpty()
                    history.value = when {
                        kind == LaneFetch.APPEND ->
                            history.value.copy(appending = false, problem = message)

                        keptRows -> {
                            notice.value = PullsNotice.Problem(message)
                            history.value.copy(status = LaneStatus.LOADED, problem = null)
                        }

                        else -> history.value.copy(
                            status = LaneStatus.UNAVAILABLE,
                            problem = message,
                        )
                    }
                }
            }
        }
    }

    /**
     * One call to `GET /api/v1/requests/wanted`, which is all there is.
     *
     * No page argument and no loop, because the endpoint takes neither — REQUIREMENTS.md:
     * `requests/wanted` has "no paging at all and return[s] the whole list". `WantedList` is modelled
     * so that a second page cannot be asked for, and this function is the shape of that model: there
     * is no [LaneFetch.APPEND] branch because there is nothing to append.
     */
    private fun fetchWanted(kind: LaneFetch) {
        wantedFetch?.cancel()
        wantedFetch = viewModelScope.launch {
            wanted.value = wanted.value.copy(status = LaneStatus.LOADING, problem = null)
            when (val result: Outcome<WantedList> = pulls.wantedList()) {
                is Outcome.Success -> wanted.value = WantedLaneState.loaded(result.value)

                is Outcome.Failure -> {
                    val message: String = laneProblemMessage(result.error, WANTED_LANE_SUBJECT)
                    // Same three-way split as history's, minus the append case. A refresh that fails
                    // over a list already drawn keeps the list.
                    if (kind == LaneFetch.REFRESH && !wanted.value.isEmpty) {
                        notice.value = PullsNotice.Problem(message)
                        wanted.value = wanted.value.copy(status = LaneStatus.LOADED, problem = null)
                    } else {
                        wanted.value = wanted.value.copy(
                            status = LaneStatus.UNAVAILABLE,
                            problem = message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Folds `GET /api/v1/requests/active` into the task list.
     *
     * REQUIREMENTS.md, "Queue screen requirements" item 6: show the user's own
     * pending approvals "with the waiting-for-approval state made explicit". The
     * two lanes overlap — a request can appear in both once the server has made
     * a task for it — so this reconciles rather than concatenates:
     *
     *  * a task that is also a parked request is marked [Pull.awaitingApproval],
     *    which is what drives [app.needler.core.domain.model.PullState.derive]
     *    to `PENDING_APPROVAL` and stops the row claiming progress it does not
     *    have;
     *  * a parked request with no task at all is prepended, because it is newer
     *    than anything the download lane knows about — it has not started yet.
     *
     * REQUIREMENTS.md, "Risks": "Requests from role `user` need approval … a
     * pull may sit waiting with no visible progress". This is the line of code
     * that stops that being invisible.
     */
    private fun reconcile(tasks: List<Pull>, approvals: List<Pull>): List<Pull> {
        if (approvals.isEmpty()) return tasks
        val waiting: Set<String> = approvals.map { it.releaseGroupMbid.value }.toSet()
        val matched: MutableSet<String> = mutableSetOf()
        val reconciled: List<Pull> = tasks.map { pull ->
            val key: String = pull.releaseGroupMbid.value
            if (waiting.contains(key)) {
                matched += key
                pull.copy(awaitingApproval = true)
            } else {
                pull
            }
        }
        val unstarted: List<Pull> = approvals
            .filterNot { matched.contains(it.releaseGroupMbid.value) }
            .map { it.copy(awaitingApproval = true) }
        return unstarted + reconciled
    }

    /**
     * Run one action at a time, disabling the row actions while it is in flight.
     *
     * Every one of these is a server write behind a button the user can hit
     * twice, and two cancels for the same task race each other into a `404`
     * that reads to the user as a failure when the first one worked.
     */
    private fun runExclusively(block: suspend () -> PullsNotice?) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                notice.value = block()
            } finally {
                busy.value = false
            }
        }
    }

    private fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())

    private data class Status(
        val heldCount: Int,
        val badgeCount: Int,
        val offline: Boolean,
    )

    /** The showing tab and the two lanes that are not the queue. See [lanes]. */
    private data class Lanes(
        val showing: PullsLane,
        val history: HistoryLaneState,
        val wanted: WantedLaneState,
    )

    private companion object {
        /**
         * Keep the queries alive briefly after the last subscriber leaves, so a
         * rotation, or a trip into an album and back, does not re-run every Room
         * query and re-mark every completion as seen.
         */
        const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L

        /** `GET /api/v1/requests/history` counts pages from one, as `RequestHistoryPage` records. */
        const val FIRST_PAGE: Int = 1
    }
}
