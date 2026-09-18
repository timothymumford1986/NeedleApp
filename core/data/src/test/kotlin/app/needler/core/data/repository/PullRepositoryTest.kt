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
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.PullStatusDb
import app.needler.core.data.local.entity.WriteOperationTypeDb
import app.needler.core.data.writequeue.WriteQueue
import app.needler.core.domain.model.AlbumRequest
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

    private fun task(id: String, mbid: String): DownloadTaskDto = DownloadTaskDto(
        id = id,
        releaseGroupMbid = mbid,
        artistName = "Slint",
        albumTitle = "Spiderland",
        status = "downloading",
        progressPercent = 10,
        createdAt = 1_700_000_000.0,
    )

    private companion object {
        const val NOW: Long = 1_000L
    }
}
