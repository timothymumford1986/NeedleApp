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
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.feature.pulls.common.problemMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
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
 * ## It does not poll
 *
 * REQUIREMENTS.md, "Polling schedule", gives the foregrounded Pulls screen a
 * **2 s** cadence, and this module's own build file says where that lives: "The
 * 2 s foreground polling and the activity-summary revision check live in
 * `:core:data`; this module consumes the resulting flow." So there is no loop
 * here, deliberately. What is in `:core:data` today is `PullPoller`, which
 * implements the *background* cadences under `WorkManager`; nothing yet drives
 * the two-second foreground one. Growing a second poller in a feature module
 * would put two writers on the same table and make the battery rule in
 * `PullPoller` — "do not poll at all when there are no active pulls" —
 * unenforceable from the place that enforces it. It is a handover note, not a
 * thing to fix from here.
 *
 * What this ViewModel does do is ask once, on open, so that a screen reached
 * fifteen minutes after the last background poll is not fifteen minutes stale.
 * That is the same one-shot `AlbumViewModel` performs with `refreshAlbum`.
 *
 * ## Opening the screen clears the badge
 *
 * `PullRepository.markCompletionsSeen` is documented as being "called when the
 * user opens the Pulls screen or taps a 'pull finished' notification", and
 * REQUIREMENTS.md says the badge "updates whenever the app is opened". Both are
 * honoured in [init]: the completions the user is now looking at stop counting
 * towards the badge. **Clear done** calls the same thing again for anything that
 * has landed since, and additionally hides those rows — see [onClearDone].
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

    /**
     * The queue: the task list, the user's parked approvals, and whatever the
     * user has cleared, reconciled into one newest-first sequence.
     */
    private val queue: Flow<List<Pull>> = combine(
        pulls.observePulls(),
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

    val state: StateFlow<PullsUiState> = combine(
        queue,
        status,
        busy,
        notice,
    ) { rows, current, isBusy, currentNotice ->
        PullsUiState(
            loading = false,
            pulls = rows,
            heldCount = current.heldCount,
            badgeCount = current.badgeCount,
            offline = current.offline,
            busy = isBusy,
            notice = currentNotice,
            renderedAt = now(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = PullsUiState(),
    )

    init {
        viewModelScope.launch {
            // The mirror has already drawn the screen by the time this returns;
            // this is what makes a stale row correct rather than what makes the
            // first row appear.
            pulls.refreshPulls()

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

    // ---- internals ----------------------------------------------------------

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

    private companion object {
        /**
         * Keep the queries alive briefly after the last subscriber leaves, so a
         * rotation, or a trip into an album and back, does not re-run every Room
         * query and re-mark every completion as seen.
         */
        const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}
