package app.needler.core.data.repository

import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.PullDao
import app.needler.core.data.local.entity.AlbumEntity
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
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.TrackRequest
import app.needler.core.domain.model.WriteOperation
import app.needler.core.domain.model.map
import app.needler.core.domain.repository.PullRepository
import app.needler.core.network.v1.RequestKind
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.ActiveRequestsDto
import app.needler.core.network.v1.dto.AlbumRequestDto
import app.needler.core.network.v1.dto.BatchAlbumItemDto
import app.needler.core.network.v1.dto.BatchAlbumRequestDto
import app.needler.core.network.v1.dto.BatchRequestResponseDto
import app.needler.core.network.v1.dto.DownloadListDto
import app.needler.core.network.v1.dto.TrackRequestDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

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

    override fun observePull(mbid: ReleaseGroupMbid): Flow<Pull?> = combine(
        pullDao.observePull(mbid.value),
        albumDao.observeAlbum(mbid.value),
    ) { pull: PullEntity?, album: AlbumEntity? ->
        pull?.let {
            EntityMappers.pull(
                row = it,
                title = album?.title.orEmpty(),
                artist = album?.artistName.orEmpty(),
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
        var skippedCount = 0
        for (chunk in requests.chunked(BatchAlbumRequestDto.MAX_ITEMS)) {
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
                is Outcome.Failure -> return call
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
                    skippedCount += call.value.skipped
                }
            }
        }
        val skipped: List<ReleaseGroupMbid> = requests
            .map { it.releaseGroupMbid }
            .filter { mbid -> accepted.none { it.releaseGroupMbid == mbid } }
            .take(maxOf(skippedCount, 0))
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
     */
    override suspend fun cancelRequest(mbid: ReleaseGroupMbid): Outcome<Unit> {
        if (!networkMonitor.current().isOnline) {
            writeQueue.enqueue(WriteOperation.CancelRequest(mbid))
            return Outcome.Ok
        }
        val call = networkCall { v1.cancelRequest(mbid.value, RequestKind.Album) }
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

    override suspend fun retryRequest(mbid: ReleaseGroupMbid): Outcome<Unit> {
        if (!networkMonitor.current().isOnline) {
            writeQueue.enqueue(WriteOperation.RetryRequest(mbid))
            return Outcome.Ok
        }
        val call = networkCall { v1.retryRequest(mbid.value, RequestKind.Album) }
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
     */
    override suspend fun refreshPulls(): Outcome<Unit> {
        val now: Long = nowMillis()
        val rows: MutableList<PullEntity> = ArrayList()
        var page = 1
        while (page <= MAX_PAGES) {
            val call: Outcome<DownloadListDto> = networkCall {
                v1.downloads(page = page, pageSize = PAGE_SIZE)
            }
            val list: DownloadListDto = when (call) {
                is Outcome.Failure -> return call
                is Outcome.Success -> call.value
            }
            rows.addAll(list.items.mapNotNull { CatalogueMappers.pullEntity(it, now) })
            if (list.items.size < PAGE_SIZE) break
            page++
        }

        // `GET /api/v1/requests/active` has no paging at all and returns the whole list. It carries
        // the approvals that have no download task yet, which is the only way a role-`user` request
        // is visible before an admin acts.
        val approvals: Outcome<ActiveRequestsDto> = networkCall { v1.activeRequests() }
        val approvalRows: List<PullEntity> = when (approvals) {
            is Outcome.Failure -> emptyList()
            is Outcome.Success -> approvals.value.items
                .mapNotNull { CatalogueMappers.pendingApprovalEntity(it, now) }
        }

        val merged: List<PullEntity> = (rows + approvalRows).distinctBy { it.releaseGroupMbid }
        if (merged.isNotEmpty()) {
            pullDao.upsertAll(merged)
            ensureAlbumRows(merged, approvals.orNullItems(), now)
        }
        return Outcome.Ok
    }

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

    // ------------------------------------------------------------------ internals

    /**
     * Records a request that has not reached the server yet.
     *
     * The row is what makes an offline pull visible: without it the album shows no state at all and
     * the user has no way of knowing their tap was kept.
     */
    private suspend fun recordPendingSubmission(request: AlbumRequest) {
        val now: Long = nowMillis()
        ensureAlbumRow(request, now)
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
        ensureAlbumRow(request, now)
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
     */
    private suspend fun ensureAlbumRow(request: AlbumRequest, now: Long) {
        if (albumDao.getAlbum(request.releaseGroupMbid.value) != null) return
        albumDao.upsert(
            CatalogueMappers.placeholderAlbumEntity(
                releaseGroupMbid = request.releaseGroupMbid.value,
                title = request.albumTitle.orEmpty(),
                artistName = request.artistName.orEmpty(),
                artistMbid = null,
                year = request.year,
                now = now,
            ),
        )
    }

    private suspend fun ensureAlbumRows(
        pulls: List<PullEntity>,
        approvals: List<app.needler.core.network.v1.dto.ActiveRequestItemDto>,
        now: Long,
    ) {
        val known: Set<String> = albumDao.getAlbums(pulls.map { it.releaseGroupMbid })
            .map { it.releaseGroupMbid }
            .toSet()
        val hints: Map<String, app.needler.core.network.v1.dto.ActiveRequestItemDto> =
            approvals.associateBy { it.trackReleaseGroupMbid ?: it.musicbrainzId }
        val missing: List<AlbumEntity> = pulls
            .map { it.releaseGroupMbid }
            .distinct()
            .filter { !known.contains(it) }
            .map { mbid ->
                val hint = hints[mbid]
                CatalogueMappers.placeholderAlbumEntity(
                    releaseGroupMbid = mbid,
                    title = hint?.albumTitle.orEmpty(),
                    artistName = hint?.artistName.orEmpty(),
                    artistMbid = hint?.artistMbid,
                    year = hint?.year,
                    now = now,
                )
            }
        if (missing.isNotEmpty()) albumDao.upsertAll(missing)
    }

    private fun statusesFor(bucket: PullBucket): List<String> = when (bucket) {
        PullBucket.ACTIVE -> PullStatusDb.ACTIVE_DB_VALUES
        PullBucket.COMPLETED -> listOf(PullStatusDb.COMPLETED.dbValue)
        PullBucket.FAILED -> PullStatusDb.FAILED_DB_VALUES
    }

    private fun Outcome<ActiveRequestsDto>.orNullItems():
        List<app.needler.core.network.v1.dto.ActiveRequestItemDto> = when (this) {
        is Outcome.Success -> value.items
        is Outcome.Failure -> emptyList()
    }

    public companion object {
        public const val PAGE_SIZE: Int = 50

        /**
         * A ceiling on blind paging. With no `total` there is nothing to stop at but a short page, so
         * a server that always answers full pages would otherwise spin the two-second poller.
         */
        public const val MAX_PAGES: Int = 20
    }
}
