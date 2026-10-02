// `advanceTimeBy`, `runCurrent` and `backgroundScope` are what make the 2 s foreground poll testable
// without waiting two real seconds per assertion; all three are still marked experimental.
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.needler.core.data.repository

import app.needler.core.data.fake.FakeAlbumDao
import app.needler.core.data.fake.FakeAppStateStore
import app.needler.core.data.fake.FakeNetworkMonitor
import app.needler.core.data.fake.FakePullDao
import app.needler.core.data.fake.FakeV1Api
import app.needler.core.data.fake.FakeWriteQueueDao
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.albumRow
import app.needler.core.data.fake.pullRow
import app.needler.core.data.background.PollSchedule
import app.needler.core.data.local.SortKeys
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.PullEntity
import app.needler.core.data.local.entity.PullStatusDb
import app.needler.core.data.local.entity.WriteOperationTypeDb
import app.needler.core.data.writequeue.WriteQueue
import app.needler.core.domain.model.AlbumRequest
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullState
import app.needler.core.domain.model.PullTaskId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.RequestStatus
import app.needler.core.network.v1.dto.DownloadActivitySummaryDto
import app.needler.core.network.v1.dto.DownloadListDto
import app.needler.core.network.v1.dto.DownloadTaskDto
import app.needler.core.network.v1.dto.RequestAcceptedDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requesting music, and watching the server acquire it.
 *
 * Three things here are places the first draft of the spec went wrong, and each one has a test: the
 * accepted status is 202 rather than 200, a refused *request* answers 200 with `success=false` while
 * a refused *download task* uses real statuses, and `retryDownload` returns a **new** task id that
 * the local row must adopt.
 *
 * The *status codes* themselves are not testable from here - [FakeV1Api] answers in DTOs and never
 * builds a response - so `PullLaneConventionsTest` in `:core:network` asserts them over real HTTP.
 * What this file is for is what the repository does with the answers.
 */
public class PullRepositoryTest {

    private val pullDao = FakePullDao()
    private val albumDao = FakeAlbumDao()
    private val appState = FakeAppStateStore()
    private val writeQueueDao = FakeWriteQueueDao()
    private val writeQueue = WriteQueue(writeQueueDao) { NOW }
    private val network = FakeNetworkMonitor()
    private val v1 = FakeV1Api()

    private val repository = DefaultPullRepository(
        pullDao = pullDao,
        albumDao = albumDao,
        appStateStore = appState,
        writeQueue = writeQueue,
        networkMonitor = network,
        v1 = v1,
        nowMillis = { NOW },
    )

    private val request = AlbumRequest(
        releaseGroupMbid = ReleaseGroupMbid(RG),
        albumTitle = "Spiderland",
        artistName = "Slint",
        year = 1991,
    )

    // ------------------------------------------------------------------ requests

    @Test
    public fun `the status is rendered as the server returned it, never inferred from the role`(): Unit =
        runTest {
            v1.requestAlbumResponse = {
                RequestAcceptedDto(
                    success = true,
                    musicbrainzId = RG,
                    status = "awaiting_approval",
                    qualitySnapshotSummary = "Prefer FLAC",
                )
            }

            val receipt: RequestReceipt =
                (repository.requestAlbum(request) as Outcome.Success).value

            assertEquals(RequestStatus.PENDING_APPROVAL, receipt.status)
            assertEquals("Prefer FLAC", receipt.qualityPolicySummary)
            assertEquals(PullStatusDb.PENDING_APPROVAL, pullDao.rows[RG]?.status)
        }

    @Test
    public fun `an accepted request leaves the album acquiring, not owned`(): Unit = runTest {
        v1.requestAlbumResponse = {
            RequestAcceptedDto(success = true, musicbrainzId = RG, status = "queued")
        }

        repository.requestAlbum(request)

        assertEquals(RequestStatus.ACCEPTED, RequestStatus.fromServerToken("queued"))
        assertEquals(AlbumStateDb.ACQUIRING, albumDao.rows[RG]?.state)
        assertFalse(albumDao.rows[RG]?.inLibrary ?: true)
    }

    /**
     * An accepted 202 whose body says nothing about `success` must not land as a failure.
     *
     * REQUIREMENTS.md "Placing a request", item 1. The server's structs emit every field including
     * nulls, the decoder coerces an unexpected null onto the field's default, and a default of
     * `false` turned every such acceptance into [RequestStatus.REJECTED] - which wrote the `pull` row
     * `FAILED` and the mirror's album `FAILED` too. The regression is asserted on the two rows rather
     * than only on the receipt, because the rows are what the user sees afterwards.
     */
    @Test
    public fun `an accepted request with no success flag is not stored as a failure`(): Unit = runTest {
        v1.requestAlbumResponse = { RequestAcceptedDto(musicbrainzId = RG, status = "queued") }

        val receipt: RequestReceipt =
            (repository.requestAlbum(request) as Outcome.Success).value

        assertEquals(RequestStatus.ACCEPTED, receipt.status)
        assertEquals(PullStatusDb.SEARCHING, pullDao.rows[RG]?.status)
        assertEquals(AlbumStateDb.ACQUIRING, albumDao.rows[RG]?.state)
        assertNull(pullDao.rows[RG]?.error)
    }

    @Test
    public fun `an offline request is journalled and reports queued rather than failing`(): Unit =
        runTest {
            network.goOffline()

            val receipt: RequestReceipt =
                (repository.requestAlbum(request) as Outcome.Success).value

            assertEquals(RequestStatus.QUEUED_OFFLINE, receipt.status)
            assertEquals(1, writeQueueDao.rows.size)
            assertEquals(WriteOperationTypeDb.PULL_REQUEST, writeQueueDao.rows.single().operationType)
            assertTrue(v1.calls.isEmpty())
        }

    @Test
    public fun `an offline request still shows on the Pulls screen`(): Unit = runTest {
        network.goOffline()

        repository.requestAlbum(request)

        val pull: Pull = repository.observePulls().first().single()
        assertEquals(RG, pull.releaseGroupMbid.value)
        assertTrue(pull.isPendingSubmission)
    }

    @Test
    public fun `an offline request creates the album row a pull renders against`(): Unit = runTest {
        network.goOffline()

        repository.requestAlbum(request)

        assertNotNull(albumDao.rows[RG])
        assertEquals("Spiderland", albumDao.rows[RG]?.title)
    }

    @Test
    public fun `a retryable failure is journalled too, because the intent is the same`(): Unit =
        runTest {
            v1.failWith = { app.needler.core.network.NetworkError.Offline(java.io.IOException("x")) }

            val receipt: RequestReceipt =
                (repository.requestAlbum(request) as Outcome.Success).value

            assertEquals(RequestStatus.QUEUED_OFFLINE, receipt.status)
            assertEquals(1, writeQueueDao.rows.size)
        }

    @Test
    public fun `a permanent rejection is reported rather than silently queued`(): Unit = runTest {
        v1.failWith = {
            app.needler.core.network.NetworkError.InvalidRequest(
                lane = app.needler.core.network.ApiLane.V1,
                statusCode = 422,
                serverMessage = "unknown release group",
            )
        }

        val result = repository.requestAlbum(request)

        assertTrue(result is Outcome.Failure)
        assertTrue(writeQueueDao.rows.isEmpty())
    }

    @Test
    public fun `a batch is chunked to the server's decode-time cap`(): Unit = runTest {
        val requests: List<AlbumRequest> = List(600) {
            AlbumRequest(releaseGroupMbid = ReleaseGroupMbid("mbid-$it"))
        }

        repository.requestAlbums(requests)

        // 501 items is a 422 before the handler runs, so the caller chunks: two calls, not one.
        assertEquals(2, v1.calls.count { it.startsWith("requestAlbums(") })
        assertTrue(v1.calls.contains("requestAlbums(500)"))
        assertTrue(v1.calls.contains("requestAlbums(100)"))
    }

    @Test
    public fun `the batch overflow field is reported as zero, because the server never sets it`(): Unit =
        runTest {
            v1.requestAlbumsResponse = {
                app.needler.core.network.v1.dto.BatchRequestResponseDto(
                    success = true,
                    requested = it.items.size,
                    overflow = 99,
                    status = "pending",
                )
            }

            val receipt = (
                repository.requestAlbums(listOf(request)) as Outcome.Success
                ).value

            assertEquals(0, receipt.overflow)
        }

    // ------------------------------------------------------- a half-sent batch
    //
    // REQUIREMENTS.md "Placing a request" caps the endpoint at 500 and makes chunking the caller's
    // job, and says nothing about a chunk failing partway through. This used to return the failure
    // and discard the receipts for chunks already accepted - whose `pull` rows had already been
    // written - so the user asked for 500 albums, 200 were really being fetched, and the screen said
    // it had failed.

    @Test
    public fun `a batch that fails on its second chunk reports what the server did take`(): Unit =
        runTest {
            val requests: List<AlbumRequest> = List(600) {
                AlbumRequest(releaseGroupMbid = ReleaseGroupMbid("mbid-$it"))
            }
            v1.requestAlbumsResponse = failingAfterFirstChunk {
                app.needler.core.network.NetworkError.InvalidRequest(
                    lane = app.needler.core.network.ApiLane.V1,
                    statusCode = 422,
                    serverMessage = "no",
                )
            }

            val receipt = (repository.requestAlbums(requests) as Outcome.Success).value

            assertEquals(500, receipt.requested.size)
            assertEquals(100, receipt.notSubmitted.size)
            assertTrue(receipt.isPartial)
            assertNotNull(receipt.failure)
            // And the 500 rows really are in the mirror, which is why calling this a failure lied.
            assertEquals(500, pullDao.rows.size)
        }

    @Test
    public fun `a batch that fails on its first chunk is a plain failure, not a partial success`(): Unit =
        runTest {
            v1.failWith = {
                app.needler.core.network.NetworkError.InvalidRequest(
                    lane = app.needler.core.network.ApiLane.V1,
                    statusCode = 422,
                    serverMessage = "no",
                )
            }

            val result = repository.requestAlbums(listOf(request))

            assertTrue(result is Outcome.Failure)
            assertTrue(pullDao.rows.isEmpty())
        }

    /**
     * A retryable mid-batch failure journals the remainder instead of abandoning it.
     *
     * The same treatment [DefaultPullRepository.requestAlbum] gives one album and the offline path
     * gives the whole list: the user's intent was "acquire these", and a connection that dropped
     * halfway is not a reason to throw away half of it. The remainder therefore comes back as
     * accepted-with-[RequestStatus.QUEUED_OFFLINE] rather than as `notSubmitted`.
     */
    @Test
    public fun `a retryable failure mid-batch journals the rest rather than dropping it`(): Unit =
        runTest {
            val requests: List<AlbumRequest> = List(600) {
                AlbumRequest(releaseGroupMbid = ReleaseGroupMbid("mbid-$it"))
            }
            v1.requestAlbumsResponse = failingAfterFirstChunk {
                app.needler.core.network.NetworkError.Offline(java.io.IOException("x"))
            }

            val receipt = (repository.requestAlbums(requests) as Outcome.Success).value

            assertTrue(receipt.notSubmitted.isEmpty())
            assertEquals(600, receipt.requested.size)
            assertEquals(
                100,
                receipt.requested.count { it.status == RequestStatus.QUEUED_OFFLINE },
            )
            assertEquals(100, writeQueueDao.rows.size)
        }

    /**
     * The skipped count is per chunk, not across the whole request.
     *
     * The response reports counts rather than naming what it took, so the accepted items are the
     * first `requested` of *that chunk* and the skipped ones are what follows them in it. The old
     * global filter took "everything not accepted" - which, once a chunk can fail, includes albums
     * the server never saw, reported as already present.
     */
    @Test
    public fun `skipped albums are counted within the chunk that reported them`(): Unit = runTest {
        val requests: List<AlbumRequest> = List(3) {
            AlbumRequest(releaseGroupMbid = ReleaseGroupMbid("mbid-$it"))
        }
        v1.requestAlbumsResponse = {
            app.needler.core.network.v1.dto.BatchRequestResponseDto(
                success = true,
                requested = 1,
                skipped = 2,
                status = "pending",
            )
        }

        val receipt = (repository.requestAlbums(requests) as Outcome.Success).value

        assertEquals(listOf("mbid-0"), receipt.requested.map { it.releaseGroupMbid?.value })
        assertEquals(listOf("mbid-1", "mbid-2"), receipt.skipped.map { it.value })
    }

    // -------------------------------------------------- cancel and retry, in-band

    @Test
    public fun `a refused request cancel is a 200 with success false, and is a failure here`(): Unit =
        runTest {
            v1.cancelRequestResponse = {
                app.needler.core.network.v1.dto.CancelRequestDto(
                    success = false,
                    message = "Cannot cancel request with status 'processing'",
                )
            }
            pullDao.rows[RG] = pullRow()

            val result = repository.cancelRequest(ReleaseGroupMbid(RG))

            assertTrue(result is Outcome.Failure)
            // The row survives: the server still holds the request.
            assertNotNull(pullDao.rows[RG])
        }

    @Test
    public fun `a successful request cancel removes the local row`(): Unit = runTest {
        pullDao.rows[RG] = pullRow()

        val result = repository.cancelRequest(ReleaseGroupMbid(RG))

        assertTrue(result is Outcome.Success)
        assertNull(pullDao.rows[RG])
    }

    @Test
    public fun `an offline cancel is journalled`(): Unit = runTest {
        network.goOffline()

        repository.cancelRequest(ReleaseGroupMbid(RG))

        assertEquals(WriteOperationTypeDb.PULL_CANCEL, writeQueueDao.rows.single().operationType)
    }

    // ------------------------------------------- cancel and retry know the kind
    //
    // REQUIREMENTS.md "Placing a request": cancel and retry of a request "take `request_kind`,
    // either `album` or `track`", and a track request is "keyed on recording MBID, not release
    // group". The `pull` row is keyed on the release group either way, so the kind and the recording
    // are columns on it - without them this lane sent `request_kind=album` and the release-group id
    // for every row, which for a track request asks the server to cancel the whole album.

    @Test
    public fun `cancelling a track request sends the recording MBID and request_kind track`(): Unit =
        runTest {
            pullDao.rows[RG] = pullRow(
                taskId = null,
                status = PullStatusDb.SEARCHING,
                requestKind = PullEntity.REQUEST_KIND_TRACK,
                recordingMbid = RECORDING,
            )

            val result = repository.cancelRequest(ReleaseGroupMbid(RG))

            assertTrue(result is Outcome.Success)
            assertTrue(v1.calls.toString(), v1.calls.contains("cancelRequest($RECORDING,track)"))
            assertFalse(v1.calls.any { it.contains("cancelRequest($RG,") })
        }

    @Test
    public fun `cancelling an album request still sends the release group and request_kind album`(): Unit =
        runTest {
            pullDao.rows[RG] = pullRow(taskId = null, status = PullStatusDb.SEARCHING)

            repository.cancelRequest(ReleaseGroupMbid(RG))

            assertTrue(v1.calls.toString(), v1.calls.contains("cancelRequest($RG,album)"))
        }

    @Test
    public fun `retrying a track request is keyed the same way as cancelling one`(): Unit = runTest {
        pullDao.rows[RG] = pullRow(
            taskId = null,
            status = PullStatusDb.FAILED,
            requestKind = PullEntity.REQUEST_KIND_TRACK,
            recordingMbid = RECORDING,
        )

        repository.retryRequest(ReleaseGroupMbid(RG))

        assertTrue(v1.calls.toString(), v1.calls.contains("retryRequest($RECORDING,track)"))
    }

    /**
     * A row with no `pull` behind it is an album request, which is the safe direction.
     *
     * The release group is the id the caller named and the one the album screen holds, so nothing is
     * guessed; a track request always has a row, because the only way one exists is that a lane
     * reported it.
     */
    @Test
    public fun `a cancel for an unknown pull is treated as an album request`(): Unit = runTest {
        repository.cancelRequest(ReleaseGroupMbid(RG))

        assertTrue(v1.calls.toString(), v1.calls.contains("cancelRequest($RG,album)"))
    }

    /**
     * The one case that refuses rather than guessing: a track row written before the column existed.
     *
     * Sending the release group under `request_kind=track` is a `404`; sending it under `album`
     * cancels an album the user never named. So nothing is sent at all and the failure says why.
     */
    @Test
    public fun `a track request with no recording MBID is refused, not sent as an album`(): Unit =
        runTest {
            pullDao.rows[RG] = pullRow(
                taskId = null,
                status = PullStatusDb.SEARCHING,
                requestKind = PullEntity.REQUEST_KIND_TRACK,
                recordingMbid = null,
            )

            val result = repository.cancelRequest(ReleaseGroupMbid(RG))

            assertTrue(result is Outcome.Failure)
            assertTrue((result as Outcome.Failure).error is NeedlerError.Rejected)
            assertTrue("nothing may be sent: " + v1.calls, v1.calls.isEmpty())
            assertNotNull(pullDao.rows[RG])
        }

    @Test
    public fun `an offline track cancel journals the kind and the recording, not just the album`(): Unit =
        runTest {
            pullDao.rows[RG] = pullRow(
                taskId = null,
                status = PullStatusDb.SEARCHING,
                requestKind = PullEntity.REQUEST_KIND_TRACK,
                recordingMbid = RECORDING,
            )
            network.goOffline()

            repository.cancelRequest(ReleaseGroupMbid(RG))

            val decoded = writeQueue.toEntry(writeQueueDao.rows.single())!!.operation
            val cancel = decoded as app.needler.core.domain.model.WriteOperation.CancelRequest
            assertEquals(app.needler.core.domain.model.RequestTarget.TRACK, cancel.requestKind)
            assertEquals(RECORDING, cancel.recordingMbid?.value)
            // And the id the replay will put in the path is the recording, not the release group.
            assertEquals(RECORDING, cancel.endpointMbid)
        }

    @Test
    public fun `retrying a download task re-keys the local row to the new task id`(): Unit = runTest {
        // `retryDownload` creates a new task rather than reviving the old one. A row left on the old
        // id would have the screen polling a task the server has forgotten.
        pullDao.rows[RG] = pullRow(taskId = "old-task", status = PullStatusDb.FAILED)
        v1.retryDownloadResponse = {
            app.needler.core.network.v1.dto.RetryDownloadDto(success = true, taskId = "task-42")
        }

        repository.retryTask(PullTaskId("old-task"))

        assertEquals("task-42", pullDao.rows[RG]?.taskId)
        assertEquals(PullStatusDb.SEARCHING, pullDao.rows[RG]?.status)
    }

    @Test
    public fun `cancelling a download task marks the row cancelled`(): Unit = runTest {
        pullDao.rows[RG] = pullRow(taskId = "task-1")

        repository.cancelTask(PullTaskId("task-1"))

        assertEquals(PullStatusDb.CANCELLED, pullDao.rows[RG]?.status)
    }

    /**
     * The download pair's refusals are statuses, and they must not go through the request pair's
     * in-band check.
     *
     * REQUIREMENTS.md "Placing a request", item 3: the two pairs of endpoints use opposite
     * conventions, "so their error handling cannot be shared". [DefaultPullRepository.cancelRequest]
     * unpacks `success=false` on a `200`; [DefaultPullRepository.cancelTask] must not look for one,
     * because on this lane a `200` is a cancellation and a refusal arrives as `403`, `404` or `400`.
     * The row is left alone on a refusal: the server still owns that task.
     */
    @Test
    public fun `a refused download cancel is a status, and leaves the row as it was`(): Unit = runTest {
        pullDao.rows[RG] = pullRow(taskId = "task-1", status = PullStatusDb.DOWNLOADING)
        v1.failWith = {
            app.needler.core.network.NetworkError.Forbidden(
                lane = app.needler.core.network.ApiLane.V1,
                serverMessage = "not your task",
            )
        }

        val result = repository.cancelTask(PullTaskId("task-1"))

        assertTrue(result is Outcome.Failure)
        assertTrue((result as Outcome.Failure).error is NeedlerError.PermissionDenied)
        assertEquals(PullStatusDb.DOWNLOADING, pullDao.rows[RG]?.status)
    }

    /**
     * And the reverse: a `200` on the download lane is a cancellation whatever its body says.
     *
     * `CancelDownloadResponse` carries a `success` field, so the temptation is to read it the way
     * `CancelRequestResponse`'s is read. That would import the request lane's convention onto the
     * endpoint REQUIREMENTS.md explicitly contrasts with it, and a client that did so would report a
     * cancellation the server performed as a failure.
     */
    @Test
    public fun `a download cancel that answered 200 is a cancellation, not an in-band refusal`(): Unit =
        runTest {
            pullDao.rows[RG] = pullRow(taskId = "task-1", status = PullStatusDb.DOWNLOADING)

            val result = repository.cancelTask(PullTaskId("task-1"))

            assertTrue(result is Outcome.Success)
            assertEquals(PullStatusDb.CANCELLED, pullDao.rows[RG]?.status)
        }

    // ------------------------------------------------------------------ refresh

    @Test
    public fun `refreshing pages blindly and stops on a short page`(): Unit = runTest {
        v1.downloadsResponse = { page ->
            if (page == 1) {
                DownloadListDto(items = List(DefaultPullRepository.PAGE_SIZE) { task("t$it", "mbid-$it") })
            } else {
                DownloadListDto(items = listOf(task("last", "mbid-last")))
            }
        }

        repository.refreshPulls()

        assertEquals(2, v1.calls.count { it.startsWith("downloads(") })
        assertEquals(DefaultPullRepository.PAGE_SIZE + 1, pullDao.rows.size)
    }

    // ------------------------------------------------ the mirror is reconciled
    //
    // A task the server drops without a terminal state used to stay active in the mirror for ever,
    // and `observePullBadgeCount` counts active rows - REQUIREMENTS.md calls that badge "the
    // reliable channel". `deleteFinishedBefore` was never the fix: it prunes rows that reached a
    // terminal state, and these never do. The hard part is that `GET /api/v1/downloads` has no
    // `total` and no `total_pages`, so an absence only means something when the whole list was
    // walked.

    @Test
    public fun `an active row a complete walk did not report is dropped`(): Unit = runTest {
        pullDao.rows["gone"] = pullRow(
            mbid = "gone",
            taskId = "task-gone",
            status = PullStatusDb.DOWNLOADING,
            updatedAt = STALE,
        )
        v1.downloadsResponse = { DownloadListDto(items = listOf(task("t1", RG))) }

        repository.refreshPulls()

        assertNull(pullDao.rows["gone"])
        assertNotNull(pullDao.rows[RG])
        assertEquals(1, repository.observePullBadgeCount().first())
    }

    /**
     * A full page means there may be another, so nothing may be deleted on a walk that never ended.
     *
     * REQUIREMENTS.md: "paging is blind: ask for a page, and a full page means there may be
     * another." A server that always answers full pages exhausts [DefaultPullRepository.MAX_PAGES]
     * without ever proving it has finished, which is the exact case where a row this poll did not
     * see certainly exists.
     */
    @Test
    public fun `nothing is dropped when the page walk never reached a short page`(): Unit = runTest {
        pullDao.rows["gone"] = pullRow(
            mbid = "gone",
            taskId = "task-gone",
            status = PullStatusDb.DOWNLOADING,
            updatedAt = STALE,
        )
        v1.downloadsResponse = { page ->
            DownloadListDto(
                items = List(DefaultPullRepository.PAGE_SIZE) { task("t$page-$it", "mbid-$page-$it") },
            )
        }

        repository.refreshPulls()

        assertNotNull(pullDao.rows["gone"])
    }

    /**
     * `requests/active` failing must not look like "the user has no parked approvals".
     *
     * [DefaultPullRepository.refreshPulls] swallows a failure on that call into an empty list, which
     * is right for rendering and would be catastrophic for pruning: every approval would go the
     * first time that one request timed out.
     */
    @Test
    public fun `nothing is dropped when the approvals call failed`(): Unit = runTest {
        pullDao.rows["waiting"] = pullRow(
            mbid = "waiting",
            taskId = null,
            status = PullStatusDb.PENDING_APPROVAL,
            updatedAt = STALE,
        )
        v1.downloadsResponse = { DownloadListDto(items = emptyList()) }
        v1.activeRequestsResponse = {
            throw app.needler.core.network.NetworkError.Offline(java.io.IOException("x"))
        }

        repository.refreshPulls()

        assertNotNull(pullDao.rows["waiting"])
    }

    @Test
    public fun `a request journalled while offline survives a reconcile that cannot see it`(): Unit =
        runTest {
            // The server has deliberately never been told about this one: the write queue owns it.
            pullDao.rows["offline"] = pullRow(
                mbid = "offline",
                taskId = null,
                status = PullStatusDb.QUEUED,
                updatedAt = STALE,
            )
            v1.downloadsResponse = { DownloadListDto(items = emptyList()) }

            repository.refreshPulls()

            assertNotNull(pullDao.rows["offline"])
        }

    /**
     * A row the user's own tap just created is younger than the grace window and must not vanish.
     *
     * `applyReceipt` writes a `pull` row the moment the server accepts, which is before the server
     * has a download task to report for it - and the poll behind this runs every two seconds.
     */
    @Test
    public fun `a row written moments ago is inside the grace window`(): Unit = runTest {
        pullDao.rows["fresh"] = pullRow(
            mbid = "fresh",
            taskId = null,
            status = PullStatusDb.SEARCHING,
            updatedAt = NOW,
        )
        v1.downloadsResponse = { DownloadListDto(items = emptyList()) }

        repository.refreshPulls()

        assertNotNull(pullDao.rows["fresh"])
    }

    /**
     * Finished rows are [PullDao.deleteFinishedBefore]'s business, not the reconcile's.
     *
     * A completed row the server has forgotten is still the truthful record that the pull landed,
     * and it is what the unseen-completions half of the badge counts. Age retires those; silence
     * does not.
     */
    @Test
    public fun `a completed row the server no longer reports is left alone`(): Unit = runTest {
        pullDao.rows["done"] = pullRow(
            mbid = "done",
            taskId = "task-done",
            status = PullStatusDb.COMPLETED,
            updatedAt = STALE,
        )
        v1.downloadsResponse = { DownloadListDto(items = emptyList()) }

        repository.refreshPulls()

        assertNotNull(pullDao.rows["done"])
    }

    // ------------------------------------------------- the row's own subtitle
    //
    // `PullRow` carried no `updated_at` and no quality summary, so `EntityMappers.pull(row)` left
    // both null for every row the list queries produce: a landed pull's subtitle dated from when it
    // was *requested*, and the pack's FLAC / MP3 320 badge appeared only in the screenshot fixtures.

    @Test
    public fun `a pull on the list carries when it last changed, not only when it was asked for`(): Unit =
        runTest {
            pullDao.albumLookup = { albumDao.rows[it] }
            pullDao.rows[RG] = pullRow(status = PullStatusDb.COMPLETED, createdAt = 100L, updatedAt = 9_000L)

            val pull: Pull = repository.observePulls().first().single()

            assertEquals(9_000L, pull.updatedAt?.toEpochMilliseconds())
            assertEquals(100L, pull.createdAt?.toEpochMilliseconds())
        }

    /**
     * REQUIREMENTS.md "Design pack discrepancies": `quality_snapshot_summary` is "the honest thing to
     * show", because quality is a server-side policy a user cannot override. It rides on the `album`
     * row, which this projection already joins for the title.
     */
    @Test
    public fun `a pull on the list carries the quality the server went looking for`(): Unit = runTest {
        pullDao.albumLookup = { albumDao.rows[it] }
        albumDao.rows[RG] = albumRow(qualityPolicySummary = "FLAC")
        pullDao.rows[RG] = pullRow()

        val pull: Pull = repository.observePulls().first().single()

        assertEquals("FLAC", pull.qualityPolicySummary)
    }

    @Test
    public fun `a download task's quality summary reaches the album row the row joins to`(): Unit =
        runTest {
            pullDao.albumLookup = { albumDao.rows[it] }
            v1.downloadsResponse = {
                DownloadListDto(items = listOf(task("t1", RG, qualitySummary = "MP3 320")))
            }

            repository.refreshPulls()

            assertEquals("MP3 320", albumDao.rows[RG]?.qualityPolicySummary)
            assertEquals("MP3 320", repository.observePulls().first().single().qualityPolicySummary)
        }

    @Test
    public fun `a receipt's quality summary is not overwritten by a task summary`(): Unit = runTest {
        v1.requestAlbumResponse = {
            RequestAcceptedDto(
                success = true,
                musicbrainzId = RG,
                status = "queued",
                qualitySnapshotSummary = "FLAC",
            )
        }
        repository.requestAlbum(request)
        v1.downloadsResponse = {
            DownloadListDto(items = listOf(task("t1", RG, qualitySummary = "MP3 256")))
        }

        repository.refreshPulls()

        assertEquals("FLAC", albumDao.rows[RG]?.qualityPolicySummary)
    }

    // --------------------------------------------------- the 2 s foreground poll
    //
    // REQUIREMENTS.md "Polling schedule": `GET /api/v1/downloads` every 2 s while the Pulls screen
    // is foregrounded. Nothing drove that loop at all, which left the one screen whose purpose is
    // watching something move static between the refresh it did on open and whatever WorkManager
    // happened to do a quarter of an hour later. Polling, not SSE - per-task SSE exists and
    // REQUIREMENTS.md keeps it out of v1.

    @Test
    public fun `the live flow polls the task list as soon as it is collected`(): Unit = runTest {
        backgroundScope.launch { repository.observePullsLive().collect { } }
        runCurrent()

        assertEquals(1, v1.calls.count { it.startsWith("downloads(") })
    }

    @Test
    public fun `the live flow polls again every two seconds`(): Unit = runTest {
        backgroundScope.launch { repository.observePullsLive().collect { } }
        runCurrent()

        advanceTimeBy(PollSchedule.FOREGROUND_TASK_LIST_INTERVAL)
        runCurrent()
        assertEquals(2, v1.calls.count { it.startsWith("downloads(") })

        advanceTimeBy(PollSchedule.FOREGROUND_TASK_LIST_INTERVAL)
        runCurrent()
        assertEquals(3, v1.calls.count { it.startsWith("downloads(") })
    }

    @Test
    public fun `the poll stops when nothing is collecting any more`(): Unit = runTest {
        val job = backgroundScope.launch { repository.observePullsLive().collect { } }
        runCurrent()
        job.cancel()

        advanceTimeBy(PollSchedule.FOREGROUND_TASK_LIST_INTERVAL * 5)
        runCurrent()

        assertEquals(1, v1.calls.count { it.startsWith("downloads(") })
    }

    /** A failed poll must not end the flow and take the mirror's emissions with it. */
    @Test
    public fun `a failed poll leaves the flow alive and still serving the mirror`(): Unit = runTest {
        pullDao.rows[RG] = pullRow()
        v1.failWith = { app.needler.core.network.NetworkError.Offline(java.io.IOException("x")) }
        val seen: MutableList<Int> = mutableListOf()

        backgroundScope.launch { repository.observePullsLive().collect { seen += it.size } }
        runCurrent()
        advanceTimeBy(PollSchedule.FOREGROUND_TASK_LIST_INTERVAL * 3)
        runCurrent()

        assertEquals(listOf(1), seen)
        assertTrue(v1.calls.count { it.startsWith("downloads(") } >= 3)
    }

    @Test
    public fun `the activity summary has its own slower cadence`(): Unit = runTest {
        backgroundScope.launch { repository.observeActivitySummaryLive().collect { } }
        runCurrent()
        assertEquals(1, v1.calls.count { it == "downloadActivitySummary" })

        // Ten times slower than the task list, and on the cheap endpoint.
        advanceTimeBy(PollSchedule.FOREGROUND_TASK_LIST_INTERVAL)
        runCurrent()
        assertEquals(1, v1.calls.count { it == "downloadActivitySummary" })

        advanceTimeBy(PollSchedule.FOREGROUND_SUMMARY_INTERVAL)
        runCurrent()
        assertEquals(2, v1.calls.count { it == "downloadActivitySummary" })
    }

    // ---------------------------------------------------------------- the title
    //
    // `pull` holds no title: REQUIREMENTS.md "Identity model" makes the release-group MBID the join
    // key, so the Pulls screen reads the title from `album` through a LEFT JOIN. That works only if
    // something puts a title in `album`, and for a long time nothing did on this path - the
    // `album_title` both lanes carry was dropped, a blank placeholder was written instead, and
    // because a blank row is a *known* row it was never corrected. On a real device that was 34 of 35
    // rows with no name. These are the tests that were missing.

    @Test
    public fun `a download task's own title fills the album row the screen joins to`(): Unit =
        runTest {
            v1.downloadsResponse = { DownloadListDto(items = listOf(task("t1", RG))) }

            repository.refreshPulls()

            assertEquals("Spiderland", albumDao.rows[RG]?.title)
            assertEquals("Slint", albumDao.rows[RG]?.artistName)
            assertEquals(SortKeys.normalise("Spiderland"), albumDao.rows[RG]?.titleNormalised)
        }

    @Test
    public fun `an album row left blank by an earlier refresh is repaired, not kept for ever`(): Unit =
        runTest {
            // The device's actual state: a placeholder written with no title, which every later
            // refresh then reported as already known.
            albumDao.rows[RG] = albumRow(
                title = "",
                artist = "",
                artistMbid = null,
                state = AlbumStateDb.NOT_OWNED,
                year = null,
            )
            v1.downloadsResponse = { DownloadListDto(items = listOf(task("t1", RG, year = 1991))) }

            repository.refreshPulls()

            assertEquals("Spiderland", albumDao.rows[RG]?.title)
            assertEquals("Slint", albumDao.rows[RG]?.artistName)
            assertEquals(SortKeys.normalise("Spiderland"), albumDao.rows[RG]?.titleNormalised)
            assertEquals(SortKeys.normalise("Slint"), albumDao.rows[RG]?.artistNormalised)
            assertEquals(1991, albumDao.rows[RG]?.year)
        }

    @Test
    public fun `a title the mirror already has is never overwritten by a task summary`(): Unit =
        runTest {
            // The mirror knows more about an owned album than a download task ever will, so the
            // repair fills gaps only. Letting a task's summary win would trade a visible bug for an
            // invisible one.
            albumDao.rows[RG] = albumRow(title = "Spiderland (Remastered)", artist = "Slint Band")
            v1.downloadsResponse = { DownloadListDto(items = listOf(task("t1", RG))) }

            repository.refreshPulls()

            assertEquals("Spiderland (Remastered)", albumDao.rows[RG]?.title)
            assertEquals("Slint Band", albumDao.rows[RG]?.artistName)
        }

    @Test
    public fun `the requests lane supplies the title when the downloads lane omits it`(): Unit =
        runTest {
            // Both lanes are on the one screen - REQUIREMENTS.md, "Queue screen requirements" item 6
            // - and each omits fields the other carries, so neither alone can be trusted for a name.
            v1.downloadsResponse = {
                DownloadListDto(items = listOf(task("t1", RG, albumTitle = null, artistName = null)))
            }
            v1.activeRequestsResponse = {
                app.needler.core.network.v1.dto.ActiveRequestsDto(
                    items = listOf(
                        app.needler.core.network.v1.dto.ActiveRequestItemDto(
                            musicbrainzId = RG,
                            artistName = "Slint",
                            albumTitle = "Spiderland",
                            status = "awaiting_approval",
                        ),
                    ),
                    count = 1,
                )
            }

            repository.refreshPulls()

            assertEquals("Spiderland", albumDao.rows[RG]?.title)
            assertEquals("Slint", albumDao.rows[RG]?.artistName)
        }

    @Test
    public fun `a blank title on the wire is stored as absent, not as whitespace`(): Unit = runTest {
        // A server that sends `"   "` has told us nothing. Storing it would give the screen a title
        // that passes `isNotEmpty` and draws as a hole, which is the same defect without the
        // evidence.
        v1.downloadsResponse = {
            DownloadListDto(items = listOf(task("t1", RG, albumTitle = "   ", artistName = " ")))
        }

        repository.refreshPulls()

        assertEquals("", albumDao.rows[RG]?.title)
        assertEquals("", albumDao.rows[RG]?.artistName)
        // And the pull itself still exists to be rendered, with the screen's own fallback.
        assertNotNull(pullDao.rows[RG])
    }

    @Test
    public fun `pending approvals come from the requests lane, which has no paging`(): Unit = runTest {
        v1.activeRequestsResponse = {
            app.needler.core.network.v1.dto.ActiveRequestsDto(
                items = listOf(
                    app.needler.core.network.v1.dto.ActiveRequestItemDto(
                        musicbrainzId = RG,
                        artistName = "Slint",
                        albumTitle = "Spiderland",
                        status = "awaiting_approval",
                    ),
                ),
                count = 1,
            )
        }

        repository.refreshPulls()

        val approvals: List<Pull> = repository.observePendingApprovals().first()
        assertEquals(1, approvals.size)
        assertEquals(PullState.PENDING_APPROVAL, approvals.single().state)
        assertTrue(approvals.single().awaitingApproval)
    }

    @Test
    public fun `the activity summary is cached so an unchanged revision can be skipped`(): Unit =
        runTest {
            v1.activitySummaryResponse = {
                DownloadActivitySummaryDto(revision = 7L, activeCount = 2, heldCount = 1)
            }

            val summary = (repository.refreshActivitySummary() as Outcome.Success).value

            assertEquals(7L, summary.revision)
            assertEquals(7L, repository.observeActivitySummary().first()?.revision)
        }

    // -------------------------------------------------------------------- badge

    @Test
    public fun `the badge counts active pulls plus unseen completions`(): Unit = runTest {
        pullDao.rows["a"] = pullRow(mbid = "a", status = PullStatusDb.DOWNLOADING)
        pullDao.rows["b"] = pullRow(mbid = "b", status = PullStatusDb.SEARCHING)
        pullDao.rows["c"] = pullRow(mbid = "c", status = PullStatusDb.COMPLETED)

        assertEquals(3, repository.observePullBadgeCount().first())
    }

    @Test
    public fun `marking completions seen clears them from the badge but keeps the row`(): Unit =
        runTest {
            pullDao.rows["c"] = pullRow(mbid = "c", status = PullStatusDb.COMPLETED)

            repository.markCompletionsSeen(setOf(ReleaseGroupMbid("c")))

            assertEquals(0, repository.observePullBadgeCount().first())
            // The Completed bucket still lists it: seen is not the same as gone.
            assertNotNull(pullDao.rows["c"])
        }

    @Test
    public fun `buckets match the server's own grouping`(): Unit = runTest {
        pullDao.rows["a"] = pullRow(mbid = "a", status = PullStatusDb.DOWNLOADING)
        pullDao.rows["b"] = pullRow(mbid = "b", status = PullStatusDb.COMPLETED)
        pullDao.rows["c"] = pullRow(mbid = "c", status = PullStatusDb.PARTIAL)

        assertEquals(1, repository.observePulls(PullBucket.ACTIVE).first().size)
        assertEquals(1, repository.observePulls(PullBucket.COMPLETED).first().size)
        // Partial sits with the failures, because it is what the user has to act on.
        assertEquals(1, repository.observePulls(PullBucket.FAILED).first().size)
    }

    /**
     * One download task.
     *
     * [albumTitle] and [artistName] are parameters because the interesting cases are the ones where
     * the server sends nothing or sends whitespace - the default supplied a title to every fixture,
     * which is how a screen that could not name 34 of its 35 rows passed review.
     */
    private fun task(
        id: String,
        mbid: String,
        albumTitle: String? = "Spiderland",
        artistName: String? = "Slint",
        year: Int? = null,
        qualitySummary: String? = null,
    ): DownloadTaskDto = DownloadTaskDto(
        id = id,
        releaseGroupMbid = mbid,
        artistName = artistName,
        albumTitle = albumTitle,
        year = year,
        status = "downloading",
        progressPercent = 10,
        createdAt = 1_700_000_000.0,
        qualitySnapshotSummary = qualitySummary,
    )

    /**
     * A batch response that accepts the first chunk and then raises [error] for every chunk after it.
     *
     * The failure is thrown from the response rather than set on `FakeV1Api.failWith`, which fails
     * every call from the first: the case under test is specifically the *second* chunk, since a
     * first-chunk failure has nothing accepted and is a plain failure.
     */
    private fun failingAfterFirstChunk(
        error: () -> Throwable,
    ): (app.needler.core.network.v1.dto.BatchAlbumRequestDto) ->
    app.needler.core.network.v1.dto.BatchRequestResponseDto {
        var chunk = 0
        return { dto ->
            chunk += 1
            if (chunk > 1) throw error()
            app.needler.core.network.v1.dto.BatchRequestResponseDto(
                success = true,
                requested = dto.items.size,
                status = "pending",
            )
        }
    }

    private companion object {
        const val NOW: Long = 1_000L

        /** A recording MBID, which is the id a `request_kind=track` cancel is keyed on. */
        const val RECORDING: String = "9d9f2a1b-0c3d-4e5f-8a7b-6c5d4e3f2a1b"

        /**
         * An `updated_at` far enough in the past to be outside `PollSchedule.RECONCILE_GRACE`.
         *
         * Negative because the fake clock's [NOW] is 1000 ms, and the grace window is two minutes:
         * "older than two minutes before `NOW`" has nowhere else to go.
         */
        const val STALE: Long = -1_000_000L
    }
}
