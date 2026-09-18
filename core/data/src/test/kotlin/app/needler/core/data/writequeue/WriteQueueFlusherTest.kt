package app.needler.core.data.writequeue

import app.needler.core.data.fake.FakeAlbumDao
import app.needler.core.data.fake.FakeTrackDao
import app.needler.core.data.fake.FakeWriteQueueDao
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.albumRow
import app.needler.core.data.fake.trackRow
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.domain.model.AlbumRequest
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlaylistEdit
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.ScrobbleEvent
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.WriteOperation
import app.needler.core.domain.model.WriteQueueFlushReport
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replaying the queue on reconnect.
 *
 * Four rules, each of which is a bug when missing: in order; stop at the first retryable failure;
 * drop on a permanent rejection and say so; and discard a pull for an album that arrived by other
 * means. A fifth is subtler - a scrobble's track id is re-resolved at flush time, because a quality
 * upgrade between the play and the reconnect moves the file the id names.
 */
public class WriteQueueFlusherTest {

    private val dao = FakeWriteQueueDao()
    private val albumDao = FakeAlbumDao()
    private val trackDao = FakeTrackDao()
    private val queue = WriteQueue(dao) { NOW }
    private val executor = RecordingExecutor()

    private val flusher = WriteQueueFlusher(
        queue = queue,
        executor = executor,
        albumDao = albumDao,
        trackDao = trackDao,
    )

    private val trackKey = TrackKey(ReleaseGroupMbid(RG), discNumber = 1, trackNumber = 1)

    // --------------------------------------------------------------------- order

    @Test
    public fun `entries replay in sequence order`(): Unit = runTest {
        queue.enqueue(
            WriteOperation.EditPlaylist(PlaylistEdit.Create("Late night", emptyList())),
            supersede = false,
        )
        queue.enqueue(
            WriteOperation.EditPlaylist(PlaylistEdit.AddTracks(PlaylistId("7"), listOf(trackKey))),
            supersede = false,
        )

        val report: WriteQueueFlushReport = flusher.flush()

        assertEquals(2, report.replayed)
        assertTrue(executor.executed[0] is WriteOperation.EditPlaylist)
        assertTrue(
            (executor.executed[0] as WriteOperation.EditPlaylist).edit is PlaylistEdit.Create,
        )
        assertTrue(
            (executor.executed[1] as WriteOperation.EditPlaylist).edit is PlaylistEdit.AddTracks,
        )
    }

    @Test
    public fun `a retryable failure stops the flush, so nothing overtakes it`(): Unit = runTest {
        // An add-tracks that overtook its own create would be applied to a playlist the server has
        // never heard of.
        queue.enqueue(
            WriteOperation.EditPlaylist(PlaylistEdit.Create("Late night", emptyList())),
            supersede = false,
        )
        queue.enqueue(
            WriteOperation.EditPlaylist(PlaylistEdit.AddTracks(PlaylistId("7"), listOf(trackKey))),
            supersede = false,
        )
        executor.failFirstWith = NeedlerError.Offline()

        val report: WriteQueueFlushReport = flusher.flush()

        assertEquals(0, report.replayed)
        assertEquals(1, executor.executed.size)
        assertEquals(2, report.remaining)
        // Both entries survive: the first backed off, the second untouched.
        assertEquals(2, dao.rows.size)
    }

    @Test
    public fun `a retryable failure records the attempt and backs the entry off`(): Unit = runTest {
        queue.enqueue(WriteOperation.CancelRequest(ReleaseGroupMbid(RG)))
        executor.failFirstWith = NeedlerError.ServerError(503)

        flusher.flush()

        assertEquals(1, dao.rows.single().attempts)
        assertTrue(dao.rows.single().nextAttemptAt > NOW)
    }

    // ------------------------------------------------------------------ dropping

    @Test
    public fun `a permanent rejection drops the entry and reports it`(): Unit = runTest {
        queue.enqueue(WriteOperation.CancelRequest(ReleaseGroupMbid(RG)))
        executor.failFirstWith = NeedlerError.Rejected(message = "cannot cancel while processing")

        val report: WriteQueueFlushReport = flusher.flush()

        assertTrue(dao.rows.isEmpty())
        assertEquals(1, report.dropped.size)
        assertEquals("cannot cancel while processing", (report.dropped.single().error as NeedlerError.Rejected).message)
    }

    @Test
    public fun `a drop does not stop the entries behind it`(): Unit = runTest {
        queue.enqueue(WriteOperation.CancelRequest(ReleaseGroupMbid(RG)))
        queue.enqueue(WriteOperation.RetryRequest(ReleaseGroupMbid(RG)))
        executor.failFirstWith = NeedlerError.Rejected(message = "no")

        val report: WriteQueueFlushReport = flusher.flush()

        assertEquals(1, report.replayed)
        assertEquals(1, report.dropped.size)
        assertEquals(0, report.remaining)
    }

    @Test
    public fun `an unreadable payload is dropped rather than blocking the queue for ever`(): Unit =
        runTest {
            dao.enqueue(
                app.needler.core.data.local.entity.WriteQueueEntity(
                    operationType = app.needler.core.data.local.entity.WriteOperationTypeDb.PULL_REQUEST,
                    entityKey = null,
                    payload = "{ not json",
                    lastError = null,
                    createdAt = NOW,
                ),
            )
            queue.enqueue(WriteOperation.RetryRequest(ReleaseGroupMbid(RG)))

            val report: WriteQueueFlushReport = flusher.flush()

            assertEquals(1, report.replayed)
            assertTrue(dao.rows.isEmpty())
        }

    // ------------------------------------------------------- the silent discard

    @Test
    public fun `a pull for an album that arrived by other means is discarded silently`(): Unit =
        runTest {
            albumDao.rows[RG] = albumRow(state = AlbumStateDb.OWNED)
            queue.enqueue(
                WriteOperation.PlaceAlbumRequest(AlbumRequest(ReleaseGroupMbid(RG))),
            )

            val report: WriteQueueFlushReport = flusher.flush()

            assertEquals(0, report.replayed)
            assertTrue(report.dropped.isEmpty())
            assertTrue(executor.executed.isEmpty())
            assertTrue(dao.rows.isEmpty())
        }

    @Test
    public fun `a pull for an album still absent is replayed`(): Unit = runTest {
        albumDao.rows[RG] = albumRow(state = AlbumStateDb.NOT_OWNED)
        queue.enqueue(WriteOperation.PlaceAlbumRequest(AlbumRequest(ReleaseGroupMbid(RG))))

        val report: WriteQueueFlushReport = flusher.flush()

        assertEquals(1, report.replayed)
    }

    // -------------------------------------------------------- scrobble re-resolve

    @Test
    public fun `a scrobble is re-resolved against the mirror at flush time`(): Unit = runTest {
        // The play happened against file 8801; a quality upgrade has since moved the track to 9402.
        // Replaying the stored handle would scrobble a file row the server has replaced.
        trackDao.rows["$RG/1/1"] = trackRow(fileId = "9402")
        queue.enqueue(
            WriteOperation.SubmitScrobble(
                ScrobbleEvent(
                    trackKey = trackKey,
                    fetchHandle = handle("8801"),
                    playedAt = Instant.fromEpochMilliseconds(1_000L),
                    submission = true,
                ),
            ),
            supersede = false,
        )

        flusher.flush()

        val replayed = executor.executed.single() as WriteOperation.SubmitScrobble
        assertEquals("9402", replayed.scrobble.fetchHandle.fileId.value)
        // The timestamp is the one thing that must NOT be re-resolved.
        assertEquals(1_000L, replayed.scrobble.playedAt.toEpochMilliseconds())
    }

    @Test
    public fun `a scrobble whose track the mirror has lost keeps its stored handle`(): Unit = runTest {
        queue.enqueue(
            WriteOperation.SubmitScrobble(
                ScrobbleEvent(
                    trackKey = trackKey,
                    fetchHandle = handle("8801"),
                    playedAt = Instant.fromEpochMilliseconds(1_000L),
                    submission = false,
                ),
            ),
            supersede = false,
        )

        flusher.flush()

        val replayed = executor.executed.single() as WriteOperation.SubmitScrobble
        assertEquals("8801", replayed.scrobble.fetchHandle.fileId.value)
    }

    // ------------------------------------------------------------------- nothing

    @Test
    public fun `an empty queue is a cheap no-op`(): Unit = runTest {
        val report: WriteQueueFlushReport = flusher.flush()

        assertEquals(0, report.replayed)
        assertTrue(report.dropped.isEmpty())
        assertTrue(executor.executed.isEmpty())
    }

    @Test
    public fun `an entry still backing off is not due`(): Unit = runTest {
        val seq: Long = queue.enqueue(WriteOperation.CancelRequest(ReleaseGroupMbid(RG)))
        queue.recordFailure(seq, attempts = 1, error = NeedlerError.Offline())

        val report: WriteQueueFlushReport = flusher.flush()

        assertEquals(0, report.replayed)
        assertTrue(executor.executed.isEmpty())
        assertNotNull(dao.rows.singleOrNull())
    }

    private fun handle(fileId: String): TrackFetchHandle = TrackFetchHandle(
        fileId = FileId(fileId),
        sizeBytes = null,
        durationMs = null,
        format = null,
        bitrateKbps = null,
    )

    /** Records what it was asked to replay, and can fail the first attempt on demand. */
    private class RecordingExecutor : WriteQueueExecutor {
        val executed: MutableList<WriteOperation> = ArrayList()
        val entityKeys: MutableList<String?> = ArrayList()
        var failFirstWith: NeedlerError? = null

        override suspend fun execute(
            operation: WriteOperation,
            entityKey: String?,
        ): Outcome<Unit> {
            executed.add(operation)
            entityKeys.add(entityKey)
            val failure: NeedlerError? = failFirstWith
            if (failure != null && executed.size == 1) return Outcome.Failure(failure)
            return Outcome.Ok
        }
    }

    private companion object {
        const val NOW: Long = 1_000L
    }
}
