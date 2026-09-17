package app.needler.core.domain.repository

import app.needler.core.domain.model.AlbumRequest
import app.needler.core.domain.model.BatchRequestReceipt
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullTaskId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.TrackRequest
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
}
