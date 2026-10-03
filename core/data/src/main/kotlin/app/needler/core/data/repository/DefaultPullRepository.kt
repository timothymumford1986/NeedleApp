package app.needler.core.data.repository

import app.needler.core.data.background.BackgroundWorkScheduler
import app.needler.core.data.background.PollSchedule
import app.needler.core.data.local.SortKeys
import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.PullDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.PullEntity
import app.needler.core.data.local.entity.PullStatusDb
import app.needler.core.data.mapper.CatalogueMappers
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.data.mapper.networkCall
import app.needler.core.data.platform.AppStateStore
import app.needler.core.data.platform.NetworkMonitor
import app.needler.core.data.writequeue.WriteQueue
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
import app.needler.core.domain.model.RecordingMbid
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.RequestTarget
import app.needler.core.domain.model.TrackRequest
import app.needler.core.domain.model.WantedList
import app.needler.core.domain.model.WriteOperation
import app.needler.core.domain.model.map
import app.needler.core.domain.repository.PullRepository
import app.needler.core.network.v1.RequestHistorySort
import app.needler.core.network.v1.RequestKind
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.ActiveRequestItemDto
import app.needler.core.network.v1.dto.ActiveRequestsDto
import app.needler.core.network.v1.dto.AlbumRequestDto
import app.needler.core.network.v1.dto.BatchAlbumItemDto
import app.needler.core.network.v1.dto.BatchAlbumRequestDto
import app.needler.core.network.v1.dto.BatchRequestResponseDto
import app.needler.core.network.v1.dto.DownloadListDto
import app.needler.core.network.v1.dto.DownloadTaskDto
import app.needler.core.network.v1.dto.RequestHistoryDto
import app.needler.core.network.v1.dto.RequestHistoryItemDto
import app.needler.core.network.v1.dto.TrackRequestDto
import app.needler.core.network.v1.dto.WantedRetryingItemDto
import app.needler.core.network.v1.dto.WantedWatchItemDto
import app.needler.core.network.v1.dto.WantedWatchesDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.time.Duration

/**
 * Requesting music and watching the server acquire it.
 *
 * Reads come from the mirrored `pull` table, so the Pulls screen renders instantly and offline. The
 * `refresh*` functions are what the pollers call: two seconds against the full task list while the
 * screen is foregrounded, and the activity summary everywhere else, where an unchanged `revision`
 * makes the poll nearly free.
 *
 * The two client-derived states - Searching, and "needs attention on the server" - are stored rather
 * than recomputed at read time, because the screen filters on them. `search_job_id` and
 * `candidate_index` are stored beside them so the derivation can always be re-run.
 */
public class DefaultPullRepository(
    private val pullDao: PullDao,
    private val albumDao: AlbumDao,
    private val appStateStore: AppStateStore,
    private val writeQueue: WriteQueue,
    private val networkMonitor: NetworkMonitor,
    private val v1: V1Api,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /**
     * Arms the expedited check that runs a minute after a pull is placed.
     *
     * REQUIREMENTS.md schedules it because fifteen minutes is `WorkManager`'s floor for periodic
     * work, so without it a small album that finishes two minutes after a poll would sit
     * unannounced for a quarter of an hour - which is exactly when a user is watching. Defaults to
     * [BackgroundWorkScheduler.None] so the repository tests need no `WorkManager`.
     */
    private val workScheduler: BackgroundWorkScheduler = BackgroundWorkScheduler.None,
) : PullRepository {

    private val activitySummaryState: MutableStateFlow<PullActivitySummary?> = MutableStateFlow(null)

    /** The last summary seen this process. Its `revision` is what makes a no-change poll cheap. */
    public val activitySummary: StateFlow<PullActivitySummary?> = activitySummaryState.asStateFlow()

    // ---------------------------------------------------------------------- reads

    override fun observePulls(): Flow<List<Pull>> =
        pullDao.observeAllPulls().map { rows -> rows.map { EntityMappers.pull(it) } }

    override fun observePulls(bucket: PullBucket): Flow<List<Pull>> =
        pullDao.observePullsInStatus(statusesFor(bucket)).map { rows ->
            rows.map { EntityMappers.pull(it) }
        }

    /**
     * [observePulls], with the two-second poll REQUIREMENTS.md "Polling schedule" requires.
     *
     * ## The loop is here because nowhere else can hold it
     *
     * REQUIREMENTS.md gives the foregrounded Pulls screen `GET /api/v1/downloads` "every 2 seconds",
     * and `PullsViewModel`'s KDoc has said for some time that the loop belongs in `:core:data` and
     * that nothing drove it - which left the one screen whose whole purpose is watching something
     * move completely static between the refresh it does on open and whatever `WorkManager` happened
     * to do a quarter of an hour later. `:feature:pulls` depends on `:core:domain` and
     * `:core:design` only, so it cannot reach the endpoint at all; and a loop there would be a
     * second writer on the `pull` table, which would put `PullPoller`'s battery rule - do not poll
     * when nothing is active - outside the only place that can enforce it.
     *
     * ## Why a merged flow rather than a function to start and stop
     *
     * The subscription is the lifecycle. `merge` runs the ticker beside the Room query, so
     * collecting starts the poll and cancelling ends it: `stateIn(WhileSubscribed)` in the ViewModel
     * is then the whole of the "while the screen is foregrounded" condition, and there is no
     * `startPolling`/`stopPolling` pair for a caller to get wrong on a configuration change. The
     * ticker is a [Flow] of [Nothing] - it never emits - so it cannot change what a collector sees;
     * the emissions all come from the mirror, which is also what makes this correct offline.
     *
     * Polling, not SSE, and deliberately: `GET /api/v1/downloads/{id}/stream` exists and
     * REQUIREMENTS.md keeps it out of v1 because one held connection per task "keeps the mobile
     * radio awake and scales badly against a queue of twenty albums".
     */
    override fun observePullsLive(): Flow<List<Pull>> =
        merge(ticker(PollSchedule.FOREGROUND_TASK_LIST_INTERVAL) { refreshPulls() }, observePulls())

    /**
     * [observeActivitySummary], with the twenty-second poll for everywhere that is not this screen.
     *
     * The cheap half of the pair. The endpoint returns only
     * `{revision, active_count, held_count, failed_count, landed_release_group_mbids}`, and an
     * unchanged `revision` means nothing downstream has to run - which is what makes a poll every
     * twenty seconds affordable for as long as the app is open.
     *
     * Nothing in this module collects it, and nothing in this module can: "elsewhere in the app" is
     * a question only the host can answer, and `:app` owns that. The mechanism lives here so that
     * both foreground cadences are one pattern with one interval constant each.
     */
    override fun observeActivitySummaryLive(): Flow<PullActivitySummary?> = merge(
        ticker(PollSchedule.FOREGROUND_SUMMARY_INTERVAL) { refreshActivitySummary() },
        activitySummary,
    )

    /**
     * A ticker that polls and never emits.
     *
     * Polls first and sleeps afterwards, so a collector gets fresh data without waiting out an
     * interval - the Pulls screen opening is itself a reason to ask. [block]'s answer is discarded
     * on purpose: every one of these calls returns an [Outcome] rather than throwing, and a failed
     * poll must not end the flow and take the mirror's emissions with it. REQUIREMENTS.md "Failure
     * handling" does not make a dropped background poll a user-visible error; the screen keeps
     * drawing what it last knew, which is the behaviour it has offline anyway.
     */
    private fun ticker(interval: Duration, block: suspend () -> Unit): Flow<Nothing> = flow {
        while (true) {
            block()
            delay(interval)
        }
    }

    override fun observePull(mbid: ReleaseGroupMbid): Flow<Pull?> = combine(
        pullDao.observePull(mbid.value),
        albumDao.observeAlbum(mbid.value),
    ) { pull: PullEntity?, album: AlbumEntity? ->
        pull?.let {
            EntityMappers.pull(
                row = it,
                title = album?.title.orEmpty(),
                artist = album?.artistName.orEmpty(),
                // REQUIREMENTS.md "Design pack discrepancies": the quality the server went looking
                // for rides on the `album` row, and this is the one read that already holds it.
                qualityPolicySummary = album?.qualityPolicySummary,
            )
        }
    }

    override fun observePendingApprovals(): Flow<List<Pull>> =
        pullDao.observePullsInStatus(listOf(PullStatusDb.PENDING_APPROVAL.dbValue)).map { rows ->
            rows.map { EntityMappers.pull(it) }
        }

    override fun observeActivitySummary(): Flow<PullActivitySummary?> = activitySummary

    /**
     * The number on the Pulls tab badge: active pulls plus completions the user has not seen.
     *
     * This is the reliable channel, not notifications - Doze, standby buckets and OEM battery
     * managers all delay or drop background work, so the badge has to be right whenever the app is
     * opened. That is why "seen" is persisted rather than held in memory.
     */
    override fun observePullBadgeCount(): Flow<Int> = combine(
        pullDao.observePullCount(PullStatusDb.ACTIVE_DB_VALUES),
        pullDao.observePullsInStatus(listOf(PullStatusDb.COMPLETED.dbValue)),
        appStateStore.observeSeenPulls(),
    ) { active: Int, completed, seen: Set<String> ->
        active + completed.count { !seen.contains(it.releaseGroupMbid) }
    }

    // --------------------------------------------------------------------- writes

    /**
     * Places a request.
     *
     * Two details of this lane are easy to get wrong and both are handled here. The accepted status
     * is **202**, not 200 - the server has accepted the request, not completed it - so the client
     * must not treat anything but 200 as a failure; `:core:network` already reads 202 as success.
     * And the status in the receipt is **rendered as the server returned it**, never inferred from
     * the cached role: an admin may have changed that role moments earlier, and the server is the
     * only authority on whether this particular request needs approval.
     *
     * Offline, the request is journalled and comes back as
     * [RequestStatus.QUEUED_OFFLINE] rather than a failure, with a local `pull` row so the Pulls
     * screen shows it waiting.
     */
    override suspend fun requestAlbum(request: AlbumRequest): Outcome<RequestReceipt> {
        if (!networkMonitor.current().isOnline) {
            writeQueue.enqueue(WriteOperation.PlaceAlbumRequest(request))
            recordPendingSubmission(request)
            return Outcome.Success(
                RequestReceipt(
                    releaseGroupMbid = request.releaseGroupMbid,
                    status = RequestStatus.QUEUED_OFFLINE,
                ),
            )
        }
        val call = networkCall {
            v1.requestAlbum(
                AlbumRequestDto(
                    musicbrainzId = request.releaseGroupMbid.value,
                    artist = request.artistName,
                    album = request.albumTitle,
                    year = request.year,
                    monitorArtist = request.monitorArtist,
                ),
            )
        }
        return when (call) {
            is Outcome.Failure -> if (call.error.isRetryable) {
                // A retryable failure is indistinguishable from being offline as far as the user's
                // intent goes, so it is journalled rather than lost.
                writeQueue.enqueue(WriteOperation.PlaceAlbumRequest(request))
                recordPendingSubmission(request)
                Outcome.Success(
                    RequestReceipt(
                        releaseGroupMbid = request.releaseGroupMbid,
                        status = RequestStatus.QUEUED_OFFLINE,
                    ),
                )
            } else {
                call
            }
            is Outcome.Success -> {
                val receipt: RequestReceipt = CatalogueMappers.receipt(call.value)
                applyReceipt(request, receipt)
                workScheduler.schedulePollAfterPull()
                Outcome.Success(receipt)
            }
        }
    }

    override suspend fun requestTrack(request: TrackRequest): Outcome<RequestReceipt> {
        if (!networkMonitor.current().isOnline) {
            writeQueue.enqueue(WriteOperation.PlaceTrackRequest(request))
            return Outcome.Success(
                RequestReceipt(releaseGroupMbid = null, status = RequestStatus.QUEUED_OFFLINE),
            )
        }
        return networkCall {
            v1.requestTrack(
                recordingMbid = request.recordingMbid.value,
                request = TrackRequestDto(
                    artistName = request.artistName.orEmpty(),
                    trackTitle = request.trackTitle.orEmpty(),
                ),
            )
        }.map { dto -> CatalogueMappers.trackReceipt(dto.status, dto.taskId) }
    }

    /**
     * A batch request.
     *
     * The 500-item cap is enforced at **decode time** on the server: 501 items is a `422` before the
     * handler runs, so the caller chunks rather than relying on the response's `overflow` field,
     * which the server never sets to anything but `0`. The reported overflow is therefore always
     * zero here too - it is dead, and building anything on it would be building on a constant.
     *
     * ## A chunk failing halfway through is not a failed batch
     *
     * This used to return the failed [Outcome] and throw away the receipts for every chunk already
     * accepted - whose `pull` rows had already been written. Ask for 500 albums, have 200 accepted
     * and the third chunk time out, and the screen said the whole thing failed while the Pulls queue
     * filled up with 200 albums the server was busy acquiring. REQUIREMENTS.md "Placing a request"
     * is silent on partial failure, so the shape is chosen here, on one rule: **report what
     * happened, and never claim an album is not being fetched when it is.**
     *
     * Concretely:
     *
     *  * nothing accepted at all - the first chunk failed - is reported as the plain failure it is.
     *    There is nothing partial about it and a caller that had to inspect a success to discover
     *    the batch never started would be a trap;
     *  * with something accepted, the walk **stops** at the first failure and reports a success
     *    carrying both halves: [BatchRequestReceipt.requested] for what the server took, and
     *    [BatchRequestReceipt.notSubmitted] plus [BatchRequestReceipt.failure] for what it was never
     *    offered. Continuing through the remaining chunks was rejected: a mid-batch failure is
     *    almost always systemic - the connection, the session, the server - so the next nine chunks
     *    would be nine more timeouts for the user to wait through, and the one case where it is not
     *    systemic is a single bad MBID the user can retry;
     *  * a **retryable** failure journals the remainder instead, so it submits on reconnect and
     *    comes back under [RequestStatus.QUEUED_OFFLINE]. That is exactly what the wholly-offline
     *    path above does with the same list, and what [requestAlbum] does with a single album: the
     *    user's intent was "acquire these", and a connection that dropped partway through is not a
     *    reason to discard half of it.
     *
     * The skipped list is counted **per chunk** rather than across the whole request, which it was
     * not. The response reports counts rather than naming what it took, so the accepted items are
     * the first `requested` of the chunk and the skipped ones are what follows them *in that chunk*;
     * a global filter over everything not accepted would have counted albums that were never sent as
     * "already present".
     */
    override suspend fun requestAlbums(requests: List<AlbumRequest>): Outcome<BatchRequestReceipt> {
        if (requests.isEmpty()) {
            return Outcome.Success(BatchRequestReceipt(emptyList(), emptyList(), overflow = 0))
        }
        if (!networkMonitor.current().isOnline) {
            requests.forEach { request ->
                writeQueue.enqueue(WriteOperation.PlaceAlbumRequest(request))
                recordPendingSubmission(request)
            }
            return Outcome.Success(
                BatchRequestReceipt(
                    requested = requests.map {
                        RequestReceipt(it.releaseGroupMbid, RequestStatus.QUEUED_OFFLINE)
                    },
                    skipped = emptyList(),
                    overflow = 0,
                ),
            )
        }

        val accepted: MutableList<RequestReceipt> = ArrayList(requests.size)
        val skipped: MutableList<ReleaseGroupMbid> = ArrayList()
        val chunks: List<List<AlbumRequest>> = requests.chunked(BatchAlbumRequestDto.MAX_ITEMS)
        for ((index, chunk) in chunks.withIndex()) {
            val call: Outcome<BatchRequestResponseDto> = networkCall {
                v1.requestAlbums(
                    BatchAlbumRequestDto(
                        items = chunk.map { request ->
                            BatchAlbumItemDto(
                                musicbrainzId = request.releaseGroupMbid.value,
                                artistName = request.artistName,
                                albumTitle = request.albumTitle,
                                year = request.year,
                            )
                        },
                        monitorArtist = chunk.any { it.monitorArtist },
                    ),
                )
            }
            when (call) {
                is Outcome.Failure -> {
                    // Nothing accepted yet: this is an ordinary failure and reporting it as one is
                    // the only honest answer.
                    if (accepted.isEmpty()) return call

                    val remainder: List<AlbumRequest> = chunks.drop(index).flatten()
                    if (call.error.isRetryable) {
                        // The same treatment the offline path gives the whole list, for the same
                        // reason: the intent survives the connection.
                        remainder.forEach { request ->
                            writeQueue.enqueue(WriteOperation.PlaceAlbumRequest(request))
                            recordPendingSubmission(request)
                            accepted.add(
                                RequestReceipt(
                                    request.releaseGroupMbid,
                                    RequestStatus.QUEUED_OFFLINE,
                                ),
                            )
                        }
                        return Outcome.Success(
                            BatchRequestReceipt(
                                requested = accepted,
                                skipped = skipped,
                                overflow = 0,
                            ),
                        )
                    }
                    return Outcome.Success(
                        BatchRequestReceipt(
                            requested = accepted,
                            skipped = skipped,
                            notSubmitted = remainder.map { it.releaseGroupMbid },
                            failure = call.error,
                            overflow = 0,
                        ),
                    )
                }

                is Outcome.Success -> {
                    val status: RequestStatus = RequestStatus.fromServerToken(call.value.status)
                    // The response counts rather than naming what it accepted, so the receipts are
                    // built from what was sent. The first `requested` items of the chunk are the
                    // accepted ones; the remainder were skipped as already present or requested.
                    chunk.take(call.value.requested).forEach { request ->
                        val receipt = RequestReceipt(request.releaseGroupMbid, status)
                        accepted.add(receipt)
                        applyReceipt(request, receipt)
                    }
                    if (call.value.requested > 0) workScheduler.schedulePollAfterPull()
                    skipped += chunk.drop(call.value.requested)
                        .take(call.value.skipped.coerceAtLeast(0))
                        .map { it.releaseGroupMbid }
                }
            }
        }
        return Outcome.Success(
            BatchRequestReceipt(requested = accepted, skipped = skipped, overflow = 0),
        )
    }

    /**
     * Cancels a request.
     *
     * A refusal on this endpoint is **HTTP 200 with `success=false`** and a reason; `403` is reserved
     * for an MBID belonging to another user's request. That is the opposite of [cancelTask], which
     * uses real statuses, so the two cannot share error handling.
     *
     * ## It is not always the release group that goes in the path
     *
     * This used to send `RequestKind.Album` and `mbid` unconditionally, which is wrong for a track
     * request in both of its two arguments: REQUIREMENTS.md "Placing a request" says the endpoint
     * "takes `request_kind`, either `album` or `track`", and a track request is "keyed on recording
     * MBID, not release group". The `pull` row is keyed on the release group whichever it is - that
     * is the join key REQUIREMENTS.md "Identity model" mandates - so the kind and the recording
     * arrive as stored columns on the row rather than as parameters here. See [requestRef].
     *
     * Reachable rather than theoretical: a `requests/active` track row whose `download_status` is
     * past approval derives a cancellable state with no task id, which routes the UI's Cancel
     * straight through here.
     */
    override suspend fun cancelRequest(mbid: ReleaseGroupMbid): Outcome<Unit> {
        val ref: RequestRef = when (val resolved = requestRef(mbid)) {
            is Outcome.Failure -> return resolved
            is Outcome.Success -> resolved.value
        }
        if (!networkMonitor.current().isOnline) {
            writeQueue.enqueue(
                WriteOperation.CancelRequest(
                    releaseGroupMbid = mbid,
                    requestKind = ref.target,
                    recordingMbid = ref.recordingMbid,
                ),
            )
            return Outcome.Ok
        }
        val call = networkCall { v1.cancelRequest(ref.endpointMbid, ref.kind) }
        return when (call) {
            is Outcome.Failure -> call
            is Outcome.Success -> if (call.value.success) {
                pullDao.delete(mbid.value)
                Outcome.Ok
            } else {
                Outcome.Failure(NeedlerError.Rejected(message = call.value.message))
            }
        }
    }

    /** `POST /api/v1/requests/retry/{mbid}`, keyed exactly as [cancelRequest] is and for the same reason. */
    override suspend fun retryRequest(mbid: ReleaseGroupMbid): Outcome<Unit> {
        val ref: RequestRef = when (val resolved = requestRef(mbid)) {
            is Outcome.Failure -> return resolved
            is Outcome.Success -> resolved.value
        }
        if (!networkMonitor.current().isOnline) {
            writeQueue.enqueue(
                WriteOperation.RetryRequest(
                    releaseGroupMbid = mbid,
                    requestKind = ref.target,
                    recordingMbid = ref.recordingMbid,
                ),
            )
            return Outcome.Ok
        }
        val call = networkCall { v1.retryRequest(ref.endpointMbid, ref.kind) }
        return when (call) {
            is Outcome.Failure -> call
            is Outcome.Success -> if (call.value.success) {
                Outcome.Ok
            } else {
                Outcome.Failure(NeedlerError.Rejected(message = call.value.message))
            }
        }
    }

    /**
     * Which id and which `request_kind` the two `/requests` mutations should carry for [mbid].
     *
     * Read from the stored `pull` row, because that is where the answer is: the kind and the
     * recording MBID are columns on it, written by whichever lane reported the request. A row this
     * device has never seen - no `pull` row at all - is treated as an album request, which is both
     * the overwhelming majority and the safe direction: the release-group id is the one the caller
     * named.
     *
     * The one case that refuses is a row that says `track` and has no recording MBID, which is what
     * a track row written before the column existed looks like. Sending the release group with
     * `request_kind=track` would be a `404` at best; sending it with `request_kind=album` would ask
     * the server to cancel the *album* the track belongs to, which is a request the user never made
     * and a mistake nothing would report. So it fails, and says what it needs:
     * [refreshPulls] fills the column in on the next poll, after which the same tap works.
     */
    private suspend fun requestRef(mbid: ReleaseGroupMbid): Outcome<RequestRef> {
        val row: PullEntity = pullDao.getPull(mbid.value) ?: return Outcome.Success(RequestRef.album(mbid))
        if (!row.isTrackRequest) return Outcome.Success(RequestRef.album(mbid))
        val recording: String = row.recordingMbid?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Outcome.Failure(
                NeedlerError.Rejected(
                    message = "This track request cannot be changed from here yet - " +
                        "its recording id has not been synced.",
                ),
            )
        return Outcome.Success(
            RequestRef(
                target = RequestTarget.TRACK,
                endpointMbid = recording,
                recordingMbid = RecordingMbid(recording),
            ),
        )
    }

    /**
     * The resolved target of a `/requests` cancel or retry: which kind, and which MBID in the path.
     *
     * A value type rather than a pair, so the two can never be passed in the wrong order - which is
     * the whole defect this replaces, in the other direction.
     */
    private data class RequestRef(
        val target: RequestTarget,
        val endpointMbid: String,
        val recordingMbid: RecordingMbid? = null,
    ) {
        /** The network layer's spelling of [target]. */
        val kind: RequestKind
            get() = when (target) {
                RequestTarget.ALBUM -> RequestKind.Album
                RequestTarget.TRACK -> RequestKind.Track
            }

        companion object {
            fun album(mbid: ReleaseGroupMbid): RequestRef =
                RequestRef(target = RequestTarget.ALBUM, endpointMbid = mbid.value)
        }
    }

    /**
     * Cancels a download task.
     *
     * Legal only while searching, queued or downloading. Cancelling during `processing` is refused
     * because files are being moved, so callers gate on the derived state rather than trying and
     * handling the rejection - which is why the domain exposes `Pull.canCancel`.
     */
    override suspend fun cancelTask(taskId: PullTaskId): Outcome<Unit> {
        val call = networkCall { v1.cancelDownload(taskId.value) }
        return when (call) {
            is Outcome.Failure -> call
            is Outcome.Success -> {
                pullDao.getPullByTaskId(taskId.value)?.let { row ->
                    pullDao.upsert(
                        row.copy(status = PullStatusDb.CANCELLED, updatedAt = nowMillis()),
                    )
                }
                Outcome.Ok
            }
        }
    }

    /**
     * Retries a download task.
     *
     * `retryDownload` returns a **new** task id: the old task is not resurrected. The local row is
     * therefore re-keyed rather than updated in place, or the screen goes on polling a task the
     * server has forgotten.
     */
    override suspend fun retryTask(taskId: PullTaskId): Outcome<Unit> {
        val call = networkCall { v1.retryDownload(taskId.value) }
        return when (call) {
            is Outcome.Failure -> call
            is Outcome.Success -> {
                val newTaskId: String? = call.value.taskId.takeIf { it.isNotBlank() }
                pullDao.getPullByTaskId(taskId.value)?.let { row ->
                    pullDao.upsert(
                        row.copy(
                            taskId = newTaskId,
                            status = PullStatusDb.SEARCHING,
                            percent = 0,
                            filesDone = 0,
                            error = null,
                            searchJobId = null,
                            candidateIndex = null,
                            updatedAt = nowMillis(),
                        ),
                    )
                }
                Outcome.Ok
            }
        }
    }

    // ------------------------------------------------------------------ refreshes

    /**
     * The full task list, plus the user's own pending approvals.
     *
     * `GET /api/v1/downloads` has no `total` and no `total_pages`, so paging is blind: a full page
     * means there may be another. This walks pages until one comes back short, capped so a runaway
     * server cannot spin the poller for ever.
     *
     * ## Why the titles are harvested here
     *
     * `PullEntity` holds no title: REQUIREMENTS.md "Identity model" makes the release-group MBID the
     * join key, so the Pulls screen's title comes from the `album` mirror through a LEFT JOIN and
     * there is exactly one row per album to correct when it is wrong. That is the right shape, and it
     * used to be undermined here - the title, artist, year and artist MBID that **both** lanes carry
     * were dropped on the floor, and a pull for an album the mirror had never seen wrote a
     * placeholder `album` row with an empty title. The screen then drew a blank where an album name
     * goes, for every pull that had not also been placed from this device.
     *
     * So the hints are collected from both lanes as the pages arrive and merged field by field.
     * REQUIREMENTS.md "Queue screen requirements" item 6 puts both lanes on this one screen, and each
     * omits fields the other supplies - the downloads lane has the task, the requests lane has the
     * request as the user placed it - so neither can be treated as the sole source. The alternative,
     * asking `GET /api/v1/albums/{mbid}` for every unknown title, was rejected: it is one request per
     * pull against a two-second poll, and the answer is already in the response we have.
     *
     * ## Why this also deletes, and when it refuses to
     *
     * It used to upsert only. A task the server drops without ever reporting a terminal state -
     * cleared by an admin, pruned by the server's own housekeeping, lost to a re-import - therefore
     * stayed `DOWNLOADING` in the mirror for ever, and `observePullBadgeCount` counts active rows.
     * REQUIREMENTS.md calls that badge "the reliable channel", and a channel with a permanent `1` on
     * it is not one. `deleteFinishedBefore` was never the fix: it prunes rows that *reached* a
     * terminal state, and these never do.
     *
     * The hard part is knowing the server's silence is real. `GET /api/v1/downloads` has **no
     * `total` and no `total_pages`** - REQUIREMENTS.md: "paging is blind: ask for a page, and a full
     * page means there may be another" - so an absence proves nothing unless the whole list was
     * walked. Four conditions, all necessary:
     *
     *  1. **The walk ended on a short page.** That is the only end-of-list signal the endpoint
     *     offers, and it is a real one: fewer items than asked for means there is no further page.
     *     A walk that stopped at [MAX_PAGES] with every page full proves the opposite - that rows
     *     this poll never saw certainly exist - so nothing is deleted at all. That is the case the
     *     requirement's warning is about, and it is the case that makes "no total" matter.
     *  2. **`requests/active` answered.** It has no paging and returns the whole list, so a success
     *     is complete by construction - but a *failure* is swallowed into an empty list by
     *     [orNullItems], and pruning against that would delete every parked approval the moment that
     *     one call timed out.
     *  3. **Only rows the Pulls screen buckets as Active.** The defect is a badge that will not go
     *     down. A completed or failed row the server has forgotten is still the truthful record that
     *     the pull landed or did not, and it is what the unseen-completions half of the badge is
     *     counted from; [deleteFinishedBefore] retires those on age, which is the right rule for
     *     them and the wrong one for these.
     *  4. **Not a row the server has never heard of, and not one it has only just heard of.** A
     *     request journalled while offline is active, has no task id, and is deliberately unknown to
     *     the server until the write queue replays it. And a request accepted seconds ago has a
     *     `pull` row before the server has a task to report for it, so rows are left alone until
     *     they are older than [PollSchedule.RECONCILE_GRACE] - without which the two-second poll
     *     would delete the row the user's own tap just created.
     *
     * The rows are deleted rather than marked cancelled. Writing `CANCELLED` would be inventing a
     * status the server never reported, which is the one thing REQUIREMENTS.md "Placing a request"
     * is explicit about: "render the status the server returned rather than inferring it".
     */
    override suspend fun refreshPulls(): Outcome<Unit> {
        val now: Long = nowMillis()
        val rows: MutableList<PullEntity> = ArrayList()
        val hints: MutableMap<String, AlbumHint> = LinkedHashMap()
        var page = 1
        var sawShortPage = false
        while (page <= MAX_PAGES) {
            val call: Outcome<DownloadListDto> = networkCall {
                v1.downloads(page = page, pageSize = PAGE_SIZE)
            }
            val list: DownloadListDto = when (call) {
                is Outcome.Failure -> return call
                is Outcome.Success -> call.value
            }
            list.items.forEach { item -> hints.addHint(item.releaseGroupMbid, AlbumHint.of(item)) }
            rows.addAll(list.items.mapNotNull { CatalogueMappers.pullEntity(it, now) })
            if (list.items.size < PAGE_SIZE) {
                sawShortPage = true
                break
            }
            page++
        }

        // `GET /api/v1/requests/active` has no paging at all and returns the whole list. It carries
        // the approvals that have no download task yet, which is the only way a role-`user` request
        // is visible before an admin acts.
        val approvals: Outcome<ActiveRequestsDto> = networkCall { v1.activeRequests() }
        val approvalItems: List<ActiveRequestItemDto> = approvals.orNullItems()
        val approvalRows: List<PullEntity> = approvalItems
            .mapNotNull { CatalogueMappers.pendingApprovalEntity(it, now) }
        approvalItems.forEach { item ->
            hints.addHint(item.trackReleaseGroupMbid ?: item.musicbrainzId, AlbumHint.of(item))
        }

        val merged: List<PullEntity> = (rows + approvalRows).distinctBy { it.releaseGroupMbid }
        if (merged.isNotEmpty()) {
            pullDao.upsertAll(merged)
            ensureAlbumRows(merged, hints, now)
        }
        if (sawShortPage && approvals is Outcome.Success) {
            retireVanishedPulls(seen = merged.mapTo(HashSet()) { it.releaseGroupMbid }, now = now)
        }
        return Outcome.Ok
    }

    /**
     * Drops the active `pull` rows a complete walk did not mention.
     *
     * Only ever called when both lanes answered in full; see [refreshPulls] for the four conditions
     * and why each is necessary. [seen] is every release group either lane reported this poll.
     *
     * The read is of whole entities rather than ids because three of the four tests are on columns:
     * the status says whether the row is one of the screen's Active ones, `task_id` plus the status
     * identify a request the server has never been told about, and `updated_at` is the last moment
     * anything at all asserted this row exists - which is what the grace window is measured from.
     * The active set is bounded by what one person can have in flight, so this is a small query
     * running beside a page walk that has just made several HTTP calls.
     */
    private suspend fun retireVanishedPulls(seen: Set<String>, now: Long) {
        val cutoff: Long = now - PollSchedule.RECONCILE_GRACE.inWholeMilliseconds
        val vanished: List<String> = pullDao
            .getPullsInStatus(PullStatusDb.ACTIVE_DB_VALUES)
            .filter { row ->
                !seen.contains(row.releaseGroupMbid) &&
                    !isPendingSubmission(row) &&
                    row.updatedAt < cutoff
            }
            .map { it.releaseGroupMbid }
        if (vanished.isEmpty()) return
        pullDao.deleteAll(vanished)

        // And the album rows those pulls were the only evidence for. The state the request wrote
        // outlives the `pull` row - nothing else rewrites it - so without this a task the server has
        // forgotten leaves an album badged "Waiting" or "Pulling" for ever, with no row behind it to
        // explain why and no pull for `EntityMappers.albumState` to correct it from. An album in the
        // library keeps its state: it has audio on the server, whatever became of the task.
        albumDao.getAlbums(vanished)
            .filterNot { it.state.isInLibrary }
            .forEach { row ->
                albumDao.setState(
                    releaseGroupMbid = row.releaseGroupMbid,
                    state = AlbumStateDb.NOT_OWNED,
                    inLibrary = false,
                    updatedAt = now,
                )
            }
    }

    /**
     * True while a row is a request the server has not been told about yet.
     *
     * The same test [EntityMappers.pull] derives `Pull.isPendingSubmission` from, and the same one
     * [recordPendingSubmission] writes: no task id, and merely queued. An approval row also has no
     * task id and is not this, which is what the status check is for.
     */
    private fun isPendingSubmission(row: PullEntity): Boolean =
        row.taskId == null && row.status == PullStatusDb.QUEUED

    override suspend fun refreshActivitySummary(): Outcome<PullActivitySummary> {
        val call = networkCall { v1.downloadActivitySummary() }
        return call.map { dto ->
            val summary: PullActivitySummary = CatalogueMappers.activitySummary(dto)
            activitySummaryState.value = summary
            summary
        }
    }

    override suspend fun markCompletionsSeen(mbids: Set<ReleaseGroupMbid>) {
        appStateStore.markPullsSeen(mbids.map { it.value }.toSet())
    }

    // ------------------------------------------------- the other request lanes

    /**
     * One page of `GET /api/v1/requests/history`.
     *
     * ## Paging here is the opposite of [refreshPulls]'
     *
     * [refreshPulls] walks `GET /api/v1/downloads` blindly, page after page, until one comes back
     * short, because that endpoint reports neither `total` nor `total_pages`. This endpoint reports
     * both, so it does exactly one call for exactly the page it was asked for and hands the totals
     * back untouched for the caller to page with. Reusing the walk here would fetch a whole history
     * to draw twenty rows of it; reusing this shape there is not possible at all. The two are
     * deliberately not shared, and this KDoc is the note that says so on purpose.
     *
     * ## There is no in-band failure to unpack
     *
     * A read on this lane either answers or raises a status, so [networkCall] is the whole of the
     * error handling. That is not true of its neighbours [cancelRequest] and [retryRequest], which
     * refuse with `200` and `success=false` - see their KDoc. A reader looking for the in-band check
     * here should not find one.
     *
     * The titles the page carries are folded back into the `album` mirror, for the reason
     * [ensureAlbumRows] gives: whichever lane has a name is the one that fills the mirror in, and
     * this lane names albums the download queue has long since forgotten. Only *blank* rows are
     * repaired and none is created - see [repairAlbumTitles].
     */
    override suspend fun requestHistory(
        page: Int,
        pageSize: Int,
        status: RequestOutcome?,
        newestFirst: Boolean,
    ): Outcome<RequestHistoryPage> {
        val call: Outcome<RequestHistoryDto> = networkCall {
            v1.requestHistory(
                page = page.coerceAtLeast(1),
                pageSize = pageSize.coerceIn(1, MAX_HISTORY_PAGE_SIZE),
                status = status?.serverToken,
                sort = if (newestFirst) RequestHistorySort.Newest else RequestHistorySort.Oldest,
            )
        }
        return when (call) {
            is Outcome.Failure -> call
            is Outcome.Success -> {
                val hints: MutableMap<String, AlbumHint> = LinkedHashMap()
                call.value.items.forEach { item ->
                    hints.addHint(item.trackReleaseGroupMbid ?: item.musicbrainzId, AlbumHint.of(item))
                }
                repairAlbumTitles(hints, nowMillis())
                Outcome.Success(CatalogueMappers.requestHistoryPage(call.value))
            }
        }
    }

    /**
     * `GET /api/v1/requests/wanted` in one call, because that is all the endpoint offers.
     *
     * **No paging at all.** There is no `page`, no `page_size` and no total, and the response is the
     * whole list - the same shape as `requests/active`, and the opposite of the history call
     * directly above. So there is no loop here and no ceiling: a `MAX_PAGES` guard like
     * [refreshPulls]' would be guarding against a second request that cannot be made.
     *
     * Read-only. `/api/v1` exposes nothing to start, stop or re-schedule a watch, so this reports and
     * offers nothing, exactly as the held-items count does.
     */
    override suspend fun wantedList(): Outcome<WantedList> {
        val call: Outcome<WantedWatchesDto> = networkCall { v1.wantedRequests() }
        return when (call) {
            is Outcome.Failure -> call
            is Outcome.Success -> {
                val hints: MutableMap<String, AlbumHint> = LinkedHashMap()
                call.value.items.forEach { item ->
                    hints.addHint(item.releaseGroupMbid, AlbumHint.of(item))
                }
                call.value.retrying.forEach { item ->
                    hints.addHint(item.releaseGroupMbid, AlbumHint.of(item))
                }
                repairAlbumTitles(hints, nowMillis())
                Outcome.Success(CatalogueMappers.wantedList(call.value))
            }
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Records a request that has not reached the server yet.
     *
     * The row is what makes an offline pull visible: without it the album shows no state at all and
     * the user has no way of knowing their tap was kept.
     */
    private suspend fun recordPendingSubmission(request: AlbumRequest) {
        val now: Long = nowMillis()
        ensureAlbumRow(request, now, qualityPolicySummary = null)
        pullDao.upsert(
            PullEntity(
                releaseGroupMbid = request.releaseGroupMbid.value,
                taskId = null,
                status = PullStatusDb.QUEUED,
                percent = 0,
                filesDone = 0,
                filesTotal = 0,
                downloadedBytes = null,
                totalSizeBytes = null,
                source = null,
                error = null,
                searchJobId = null,
                candidateIndex = null,
                requestedByThisDevice = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Writes the album state the server's receipt implies.
     *
     * The state comes from the receipt and not from the role, for the same reason the receipt's own
     * status does.
     */
    private suspend fun applyReceipt(request: AlbumRequest, receipt: RequestReceipt) {
        val now: Long = nowMillis()
        // The receipt's `quality_snapshot_summary` is the policy the server applied to *this*
        // request, and REQUIREMENTS.md "Design pack discrepancies" puts it on the album row. This is
        // the one moment it is in hand.
        ensureAlbumRow(request, now, qualityPolicySummary = receipt.qualityPolicySummary)
        val status: PullStatusDb = when (receipt.status) {
            RequestStatus.PENDING_APPROVAL -> PullStatusDb.PENDING_APPROVAL
            RequestStatus.ACCEPTED -> PullStatusDb.SEARCHING
            RequestStatus.QUEUED_OFFLINE -> PullStatusDb.QUEUED
            RequestStatus.ALREADY_PRESENT -> return
            RequestStatus.REJECTED -> PullStatusDb.FAILED
        }
        pullDao.upsert(
            PullEntity(
                releaseGroupMbid = request.releaseGroupMbid.value,
                taskId = receipt.taskId?.value,
                status = status,
                percent = 0,
                filesDone = 0,
                filesTotal = 0,
                downloadedBytes = null,
                totalSizeBytes = null,
                source = null,
                error = receipt.message.takeIf { receipt.status == RequestStatus.REJECTED },
                searchJobId = null,
                candidateIndex = null,
                requestedByThisDevice = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        albumDao.setState(
            releaseGroupMbid = request.releaseGroupMbid.value,
            state = EntityMappers.albumStateFor(status),
            inLibrary = false,
            updatedAt = now,
        )
    }

    /**
     * A pull needs an album row to render against: `pull` left-joins `album` for the title and
     * artist, and a pull for an album only ever seen in catalogue search has no row yet.
     *
     * An existing row has its *gaps* filled from the request and the receipt rather than being left
     * alone, which is what [repairedRow] decides: a row the catalogue wrote has a title but no
     * quality summary, because only a request's answer carries one. A row that already says
     * something keeps saying it.
     */
    private suspend fun ensureAlbumRow(
        request: AlbumRequest,
        now: Long,
        qualityPolicySummary: String?,
    ) {
        val hint = AlbumHint(
            title = request.albumTitle.tidy(),
            artistName = request.artistName.tidy(),
            year = request.year,
            qualityPolicySummary = qualityPolicySummary.tidy(),
        )
        val existing: AlbumEntity? = albumDao.getAlbum(request.releaseGroupMbid.value)
        if (existing == null) {
            albumDao.upsert(
                CatalogueMappers.placeholderAlbumEntity(
                    releaseGroupMbid = request.releaseGroupMbid.value,
                    title = hint.title.orEmpty(),
                    artistName = hint.artistName.orEmpty(),
                    artistMbid = null,
                    year = hint.year,
                    now = now,
                    qualityPolicySummary = hint.qualityPolicySummary,
                ),
            )
            return
        }
        repairedRow(existing, hint, now)?.let { albumDao.upsert(it) }
    }

    /**
     * Gives every pull an `album` row to render against, and repairs the ones that say nothing.
     *
     * Two jobs, because doing only the first is what broke the Pulls screen. A pull placed from a
     * catalogue search result can reach the queue before its album is mirrored, so a placeholder row
     * is written for anything missing - that part was always here. What was missing is that the
     * placeholder used to be written **blank**, and a blank row is indistinguishable from a known one
     * on the next refresh: `getAlbums` returns it, the mbid is no longer "missing", and the emptiness
     * is permanent. Every pull the user had not personally placed from this device therefore drew an
     * empty title for ever.
     *
     * So an existing row whose title or artist is blank is repaired from the hints, and a row that
     * already says something is **left alone**. That asymmetry is the whole point: the mirror knows
     * more about an owned album than a download task ever will - track counts, format, cover art -
     * and letting a task's summary overwrite a catalogue title would trade a visible bug for an
     * invisible one. Only the fields the mirror has nothing for are filled in.
     *
     * @param hints title, artist, year and artist MBID gathered from whichever lane supplied them,
     *   keyed on release-group MBID - the join key REQUIREMENTS.md "Identity model" mandates.
     */
    private suspend fun ensureAlbumRows(
        pulls: List<PullEntity>,
        hints: Map<String, AlbumHint>,
        now: Long,
    ) {
        val mbids: List<String> = pulls.map { it.releaseGroupMbid }.distinct()
        val known: Map<String, AlbumEntity> = albumDao.getAlbums(mbids)
            .associateBy { it.releaseGroupMbid }

        val missing: List<AlbumEntity> = mbids
            .filter { !known.containsKey(it) }
            .map { mbid ->
                val hint: AlbumHint = hints[mbid] ?: AlbumHint()
                CatalogueMappers.placeholderAlbumEntity(
                    releaseGroupMbid = mbid,
                    title = hint.title.orEmpty(),
                    artistName = hint.artistName.orEmpty(),
                    artistMbid = hint.artistMbid,
                    year = hint.year,
                    now = now,
                    qualityPolicySummary = hint.qualityPolicySummary,
                )
            }

        val repaired: List<AlbumEntity> = known.values.mapNotNull { row ->
            repairedRow(row, hints[row.releaseGroupMbid], now)
        }

        val writes: List<AlbumEntity> = missing + repaired
        if (writes.isNotEmpty()) albumDao.upsertAll(writes)

        alignAlbumStates(pulls, known, now)
    }

    /**
     * Brings `album.state` back into step with the `pull` row the poll has just written.
     *
     * The column was only ever written when a request was *placed* - see [applyReceipt] - so it
     * recorded what the receipt said and then stayed there for ever while the task moved on. On a
     * device that meant a pull the Pulls screen listed correctly as "Searching" opened on an album
     * screen still reading "Waiting for an administrator to approve this pull", from a receipt whose
     * `pending` status an older mapping had read as an approval. `EntityMappers.albumState` now
     * prefers the pull row wherever one is joined, which fixes the album screen; this fixes the
     * screens that join no pull at all. The library grid and the artist screen map the `album` row
     * alone, by design - "a grid needs the badge and not the percentage" - so for them the badge *is*
     * the column, and a stale column is a stale badge.
     *
     * Two states are never written from here, for the reasons `EntityMappers.liveState` gives: an
     * album already in the library is the sync's and the pin's business, and a finished pull does not
     * get to promote an album into the library before its tracks are mirrored.
     */
    private suspend fun alignAlbumStates(
        pulls: List<PullEntity>,
        known: Map<String, AlbumEntity>,
        now: Long,
    ) {
        pulls.forEach { pull ->
            // A row written as a placeholder moments ago is not in `known`, and is `NOT_OWNED`.
            val current: AlbumStateDb = known[pull.releaseGroupMbid]?.state ?: AlbumStateDb.NOT_OWNED
            if (current.isInLibrary) return@forEach
            val implied: AlbumStateDb = EntityMappers.albumStateFor(pull.status)
            if (implied.isInLibrary || implied == current) return@forEach
            albumDao.setState(
                releaseGroupMbid = pull.releaseGroupMbid,
                state = implied,
                inLibrary = false,
                updatedAt = now,
            )
        }
    }

    /**
     * Fills in blank titles the mirror already has rows for, and **creates none**.
     *
     * The repair half of [ensureAlbumRows] without the placeholder half, which is the right trade for
     * the two request lanes that have no queue behind them. A pull needs an `album` row because the
     * Pulls queue left-joins one for its title; a history entry and a wanted watch carry their own
     * title in their own payload and render without the mirror at all. Writing a placeholder for
     * every row of an unbounded history list would grow the mirror for nothing - REQUIREMENTS.md
     * "The metadata mirror is pruned" would collect them again later, so the only lasting effect
     * would be the churn.
     *
     * What is worth doing is the repair. These two lanes name albums the download queue no longer
     * has any record of, and an `album` row left blank by an earlier placeholder write is exactly the
     * defect that put 34 empty title lines on a real device. So a row the mirror already holds gets
     * its gaps filled from whichever lane supplied them, and a row that already says something is
     * left alone, for the reason [ensureAlbumRows] sets out at length.
     */
    private suspend fun repairAlbumTitles(hints: Map<String, AlbumHint>, now: Long) {
        if (hints.isEmpty()) return
        val known: List<AlbumEntity> = albumDao.getAlbums(hints.keys.toList())
        if (known.isEmpty()) return
        val repaired: List<AlbumEntity> = known.mapNotNull { row ->
            repairedRow(row, hints[row.releaseGroupMbid], now)
        }
        if (repaired.isNotEmpty()) albumDao.upsertAll(repaired)
    }

    /**
     * [row] with the gaps [hint] can fill, or null when it has nothing to add.
     *
     * Shared by [ensureAlbumRows] and [repairAlbumTitles] so the four lanes that supply titles cannot
     * end up with four slightly different ideas of what "blank" means. Returning null for an
     * unchanged row is what keeps a poll from writing every album it saw back unmodified.
     */
    private fun repairedRow(row: AlbumEntity, hint: AlbumHint?, now: Long): AlbumEntity? {
        if (hint == null) return null
        val title: String = row.title.ifBlank { hint.title ?: row.title }
        val artistName: String = row.artistName.ifBlank { hint.artistName ?: row.artistName }
        val artistMbid: String? = row.artistMbid ?: hint.artistMbid
        val year: Int? = row.year ?: hint.year
        // The quality summary is gap-filled like the rest, and that ordering matters: the receipt
        // for a request this device placed is the authoritative answer for that request, while a
        // download task's copy is the only answer available for a pull placed elsewhere. First
        // writer wins, so the receipt is never overwritten by a task summary.
        val quality: String? = row.qualityPolicySummary ?: hint.qualityPolicySummary
        val unchanged: Boolean = title == row.title &&
            artistName == row.artistName &&
            artistMbid == row.artistMbid &&
            year == row.year &&
            quality == row.qualityPolicySummary
        if (unchanged) return null
        return row.copy(
            title = title,
            // The sort keys are derived, so they have to move with the values they index or the
            // repaired album files itself under its old empty name.
            titleNormalised = SortKeys.normalise(title),
            artistName = artistName,
            artistNormalised = SortKeys.normalise(artistName),
            artistMbid = artistMbid,
            year = year,
            qualityPolicySummary = quality,
            updatedAt = now,
        )
    }

    /**
     * What one lane was able to say about an album, with blank treated as absent.
     *
     * A value type rather than passing a DTO around because the two lanes name these fields
     * differently and carry them at different completeness - the downloads lane has the task, the
     * requests lane has the request as the user placed it - and the merge has to happen field by
     * field. Collapsing them here means [ensureAlbumRows] never has to know which endpoint an answer
     * came from.
     *
     * Blank is normalised to `null` on the way in. The two are the same fact, and keeping them
     * distinct is precisely the confusion that let an empty title be stored as though the server had
     * asserted it; see [DownloadTaskDto.albumTitle].
     */
    private data class AlbumHint(
        val title: String? = null,
        val artistName: String? = null,
        val artistMbid: String? = null,
        val year: Int? = null,
        /**
         * `quality_snapshot_summary`: the server-side policy this request was answered under.
         *
         * Harvested here for the same reason the title is, and it fixes the same kind of defect.
         * REQUIREMENTS.md "Design pack discrepancies" settles that the figure is "shown on the album
         * and on its pull", and the Pulls row reads it off the joined `album` row - but nothing was
         * putting it there for a pull this device had not placed, so the pack's FLAC / MP3 320 badge
         * existed only in the screenshot fixtures. The downloads lane reports it on every task; this
         * is where that copy reaches the mirror.
         */
        val qualityPolicySummary: String? = null,
    ) {
        /** True when this lane said nothing worth storing, so it can be skipped entirely. */
        val isEmpty: Boolean
            get() = title == null && artistName == null && artistMbid == null && year == null &&
                qualityPolicySummary == null

        /** [other] fills the gaps this one has. First answer wins, so no page undoes another. */
        fun mergedWith(other: AlbumHint): AlbumHint = AlbumHint(
            title = title ?: other.title,
            artistName = artistName ?: other.artistName,
            artistMbid = artistMbid ?: other.artistMbid,
            year = year ?: other.year,
            qualityPolicySummary = qualityPolicySummary ?: other.qualityPolicySummary,
        )

        companion object {
            fun of(dto: DownloadTaskDto): AlbumHint = AlbumHint(
                title = dto.albumTitle.tidy(),
                artistName = dto.artistName.tidy(),
                artistMbid = dto.artistMbid.tidy(),
                year = dto.year,
                qualityPolicySummary = dto.qualitySnapshotSummary.tidy(),
            )

            fun of(dto: ActiveRequestItemDto): AlbumHint = AlbumHint(
                title = dto.albumTitle.tidy(),
                artistName = dto.artistName.tidy(),
                artistMbid = dto.artistMbid.tidy(),
                year = dto.year,
                // `quality` on this lane is the request's own summary under a shorter name; the
                // downloads lane calls the same figure `quality_snapshot_summary`.
                qualityPolicySummary = dto.quality.tidy(),
            )

            /** `GET /api/v1/requests/history`: the request as the user placed it, however long ago. */
            fun of(dto: RequestHistoryItemDto): AlbumHint = AlbumHint(
                title = dto.albumTitle.tidy(),
                artistName = dto.artistName.tidy(),
                artistMbid = dto.artistMbid.tidy(),
                year = dto.year,
            )

            /** `GET /api/v1/requests/wanted`: a standing watch, which also carries `first_release_date`. */
            fun of(dto: WantedWatchItemDto): AlbumHint = AlbumHint(
                title = dto.albumTitle.tidy(),
                artistName = dto.artistName.tidy(),
                artistMbid = dto.artistMbid.tidy(),
                year = dto.year ?: dto.firstReleaseDate?.take(4)?.toIntOrNull(),
            )

            /** The `retrying` half of the same response. */
            fun of(dto: WantedRetryingItemDto): AlbumHint = AlbumHint(
                title = dto.albumTitle.tidy(),
                artistName = dto.artistName.tidy(),
                artistMbid = dto.artistMbid.tidy(),
                year = dto.year,
            )
        }
    }

    /** Records a lane's answer for [mbid], keeping whatever an earlier lane already knew. */
    private fun MutableMap<String, AlbumHint>.addHint(mbid: String?, hint: AlbumHint) {
        val key: String = mbid?.trim().orEmpty()
        if (key.isEmpty() || hint.isEmpty) return
        this[key] = this[key]?.mergedWith(hint) ?: hint
    }

    private fun statusesFor(bucket: PullBucket): List<String> = when (bucket) {
        PullBucket.ACTIVE -> PullStatusDb.ACTIVE_DB_VALUES
        PullBucket.COMPLETED -> listOf(PullStatusDb.COMPLETED.dbValue)
        PullBucket.FAILED -> PullStatusDb.FAILED_DB_VALUES
    }

    private fun Outcome<ActiveRequestsDto>.orNullItems(): List<ActiveRequestItemDto> = when (this) {
        is Outcome.Success -> value.items
        is Outcome.Failure -> emptyList()
    }

    public companion object {
        public const val PAGE_SIZE: Int = 50

        /**
         * A ceiling on blind paging. With no `total` there is nothing to stop at but a short page, so
         * a server that always answers full pages would otherwise spin the two-second poller.
         *
         * It applies to `GET /api/v1/downloads` only. `requests/history` reports `total_pages`, so it
         * needs no ceiling; `requests/active` and `requests/wanted` do not page at all.
         */
        public const val MAX_PAGES: Int = 20

        /**
         * A ceiling on the history page size the caller may ask for.
         *
         * Not a server limit - the endpoint documents none - but a request for ten thousand rows is a
         * caller's bug and would be answered with ten thousand rows over a phone connection. Fifty
         * matches [PAGE_SIZE], so the two lanes at least ask for comparable amounts of work.
         */
        public const val MAX_HISTORY_PAGE_SIZE: Int = 50
    }
}

/**
 * Blank normalised to absent, which is how every title and every summary enters this file.
 *
 * Top-level and file-private rather than a member of `AlbumHint.Companion`, which is where it used
 * to live: `DefaultPullRepository.ensureAlbumRow` builds a hint out of an `AlbumRequest` and a
 * receipt rather than out of a DTO, and two spellings of "blank is absent" is exactly the confusion
 * that once let an empty title be stored as though the server had asserted it.
 */
private fun String?.tidy(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
