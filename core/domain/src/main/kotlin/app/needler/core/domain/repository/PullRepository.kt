package app.needler.core.domain.repository

import app.needler.core.domain.model.AlbumRequest
import app.needler.core.domain.model.BatchRequestReceipt
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullTaskId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestHistoryPage
import app.needler.core.domain.model.RequestOutcome
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.TrackRequest
import app.needler.core.domain.model.WantedList
import kotlinx.coroutines.flow.Flow

/**
 * Requesting music and watching the server acquire it.
 *
 * Reads come from the mirrored `pull` table so the Pulls screen renders instantly and offline; the
 * `refresh*` functions are what the pollers call. Live progress uses polling, not SSE: per-task SSE
 * exists on the server and is not used, because holding one connection per task keeps the radio awake
 * and scales badly against a queue of twenty albums.
 */
public interface PullRepository {

    /** Every known pull, newest first. Bucket with [Pull.bucket] for the screen's three sections. */
    public fun observePulls(): Flow<List<Pull>>

    public fun observePulls(bucket: PullBucket): Flow<List<Pull>>

    public fun observePull(mbid: ReleaseGroupMbid): Flow<Pull?>

    /**
     * The user's own requests still waiting for an admin, from `GET /api/v1/requests/active`.
     *
     * Rendered with the waiting-for-approval state made explicit, since such a pull otherwise sits with
     * no visible progress.
     */
    public fun observePendingApprovals(): Flow<List<Pull>>

    /** The last activity summary. Its `revision` makes an unchanged poll nearly free. */
    public fun observeActivitySummary(): Flow<PullActivitySummary?>

    /**
     * The number on the Pulls tab badge: active pulls plus unseen completions.
     *
     * This is the reliable channel, not notifications - Doze, standby buckets and OEM battery managers
     * all delay background work, so the badge must be correct whenever the app is opened.
     */
    public fun observePullBadgeCount(): Flow<Int>

    /**
     * Places a request via `POST /api/v1/requests/new`.
     *
     * The returned [RequestReceipt.status] is the server's own answer and must be rendered as given,
     * never inferred from the cached role. Offline, the request is journalled in the write queue and
     * comes back as [app.needler.core.domain.model.RequestStatus.QUEUED_OFFLINE] rather than a failure.
     */
    public suspend fun requestAlbum(request: AlbumRequest): Outcome<RequestReceipt>

    /** A single-track request via `POST /api/v1/tracks/{recording_mbid}/request`. */
    public suspend fun requestTrack(request: TrackRequest): Outcome<RequestReceipt>

    /** `POST /api/v1/requests/batch`, capped at 500 items server-side. */
    public suspend fun requestAlbums(requests: List<AlbumRequest>): Outcome<BatchRequestReceipt>

    /** `DELETE /api/v1/requests/active/{mbid}`: cancel a request before it completes. */
    public suspend fun cancelRequest(mbid: ReleaseGroupMbid): Outcome<Unit>

    /** `POST /api/v1/requests/retry/{mbid}`. */
    public suspend fun retryRequest(mbid: ReleaseGroupMbid): Outcome<Unit>

    /**
     * `POST /api/v1/downloads/{id}/cancel`.
     *
     * Only legal while searching, queued or downloading - see [Pull.canCancel]. The server refuses
     * cancellation during `processing` because files are being moved, so callers must gate on the
     * derived state rather than trying and handling the rejection.
     */
    public suspend fun cancelTask(taskId: PullTaskId): Outcome<Unit>

    /** `POST /api/v1/downloads/{id}/retry`. Legal on failed, cancelled and partial tasks. */
    public suspend fun retryTask(taskId: PullTaskId): Outcome<Unit>

    /** Full task list refresh, `GET /api/v1/downloads`. Polled every 2 s while the screen is open. */
    public suspend fun refreshPulls(): Outcome<Unit>

    /**
     * Cheap refresh, `GET /api/v1/downloads/activity-summary`.
     *
     * Polled every 20 s in the foreground and every 15 min to 6 h in the background. When the returned
     * revision has not moved, nothing downstream needs to run.
     */
    public suspend fun refreshActivitySummary(): Outcome<PullActivitySummary>

    /**
     * Marks completed pulls as seen, clearing them from the badge count.
     *
     * Called when the user opens the Pulls screen or taps a "pull finished" notification.
     */
    public suspend fun markCompletionsSeen(mbids: Set<ReleaseGroupMbid>)

    // ------------------------------------------------- the other request lanes

    /**
     * One page of `GET /api/v1/requests/history`: what this user has asked for over time.
     *
     * ## Why this is a suspend read and not a `Flow` off the mirror
     *
     * Every other read on this interface comes from Room, because REQUIREMENTS.md has a table behind
     * it. This one has none: "Local persistence" fixes the schema, and the `pull` table it lists is
     * the *live* queue - task id, percent, byte counters - with no history sibling anywhere in it.
     * Mirroring history anyway would mean inventing a table the canonical schema does not have, and
     * an unbounded one: the server's history grows for ever and the client would have to prune it
     * with no rule for doing so. So this reads through to the server and fails when there is no
     * server, which is honest; the queue keeps rendering offline because the queue is mirrored.
     *
     * ## Paging here is real, unlike the download queue's
     *
     * This is the only request list that pages at all, and the only list in the whole lane that
     * reports totals. `GET /api/v1/downloads` reports neither `total` nor `total_pages`, so
     * REQUIREMENTS.md rules out a "page 3 of 7" control there; here the totals are reported and
     * [RequestHistoryPage.total] is a number the screen may show. [requestHistory] and
     * [wantedList] must therefore not share paging code with [refreshPulls] - see [wantedList],
     * which pages not at all.
     *
     * @param page one-based, as the server counts.
     * @param status the server's `status` filter, or null for everything. Only
     *   [RequestOutcome.serverToken]s the server knows are sent; [RequestOutcome.OTHER] carries none
     *   and is therefore not expressible as a filter, which is deliberate.
     * @param newestFirst maps to the endpoint's `sort`, which also accepts oldest-first and
     *   by-status orderings.
     */
    public suspend fun requestHistory(
        page: Int = 1,
        pageSize: Int = HISTORY_PAGE_SIZE,
        status: RequestOutcome? = null,
        newestFirst: Boolean = true,
    ): Outcome<RequestHistoryPage> = Outcome.Failure(NOT_IMPLEMENTED_HERE)

    /**
     * `GET /api/v1/requests/wanted`: the standing watches for music the server has not found.
     *
     * **No paging at all**, which is why this returns the whole list rather than a page and takes no
     * arguments to ask for a second one. REQUIREMENTS.md is explicit that `requests/active` and
     * `requests/wanted` "have no paging at all and return the whole list. Only
     * `GET /api/v1/requests/history` pages" - so the shape of this function is the shape of the
     * endpoint, and a caller cannot write a loop the server would not answer.
     *
     * Read-only in v1. There is no endpoint on `/api/v1` to start, stop or re-schedule a watch: one
     * is created as a side effect of a request the server could not fulfil, and `monitor_artist` on
     * the request body is the only watching the user can switch on. A screen therefore reports these
     * and offers nothing, for the same reason the held-items notice does.
     */
    public suspend fun wantedList(): Outcome<WantedList> = Outcome.Failure(NOT_IMPLEMENTED_HERE)

    public companion object {
        /**
         * The page size the Pulls screen asks history for.
         *
         * Twenty is the endpoint's own default `page_size`, and a page that matches the server's
         * default is one the server is least likely to reject or silently clamp.
         */
        public const val HISTORY_PAGE_SIZE: Int = 20

        /**
         * Why [requestHistory] and [wantedList] have bodies at all.
         *
         * They are the only two members of this interface with defaults, and the default is a
         * *failure*, not an empty answer - a screen must never mistake "nobody implemented this" for
         * "you have never asked for anything". The reason they have defaults is additivity: this
         * interface is implemented by `DefaultPullRepository` and by hand-written fakes in three
         * separate test source sets, and an abstract member added to it breaks all of them in the
         * same commit. These two lanes are read-through views with nothing mirrored behind them, so
         * an implementation that answers every other member correctly and simply has no server to
         * ask is a coherent implementation; the mirrored reads above are not like that, which is why
         * none of them has a default.
         *
         * The alternative - making both abstract and correcting every implementor - was rejected
         * only because two of those implementors are owned by other work in flight. It is the better
         * end state, and turning these two abstract once the fakes have caught up is a one-line
         * change per file.
         */
        private val NOT_IMPLEMENTED_HERE: NeedlerError =
            NeedlerError.CapabilityUnavailable("requests/history and requests/wanted")
    }
}
