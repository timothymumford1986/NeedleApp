package app.needler.core.data.background

import app.needler.core.data.fake.FakeAlbumDao
import app.needler.core.data.fake.FakeAudioCacheDao
import app.needler.core.data.fake.FakePinDao
import app.needler.core.data.fake.FakeTrackByteSource
import app.needler.core.data.fake.FakeTrackDao
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.albumRow
import app.needler.core.data.fake.cacheRow
import app.needler.core.data.fake.pinRow
import app.needler.core.data.fake.trackRow
import app.needler.core.data.local.cache.AudioCacheStoreWriter
import app.needler.core.data.local.cache.CacheIndex
import app.needler.core.data.local.cache.DeviceFreeSpace
import app.needler.core.data.local.dao.AudioCacheDao
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.domain.model.NeedlerError
import app.needler.core.network.ApiLane
import app.needler.core.network.NetworkError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * "Pull local", which before this work was a button with nothing behind it.
 *
 * The store is the real [AudioCacheStoreWriter] over the real [CacheIndex] on purpose. The
 * free-space floor, the deterministic part-file naming and the refusal to publish a short transfer
 * are the rules this download path is supposed to *inherit* rather than restate, so a test that
 * faked the store would be testing the wrong thing entirely - it would pass just as happily
 * against a downloader that had quietly grown its own copy of them.
 */
public class AlbumDownloadTest {

    @get:Rule
    public val folder: TemporaryFolder = TemporaryFolder()

    private val pinDao = FakePinDao()
    private val trackDao = FakeTrackDao()
    private val audioCacheDao = FakeAudioCacheDao()
    private val albumDao = FakeAlbumDao()
    private val byteSource = FakeTrackByteSource(completeLength = TRACK_BYTES)

    private fun downloader(
        freeBytes: Long = 100_000_000L,
        floorBytes: Long = 1_000L,
        cacheDao: AudioCacheDao = audioCacheDao,
    ): AlbumDownloader = AlbumDownloader(
        pinDao = pinDao,
        trackDao = trackDao,
        audioCacheDao = cacheDao,
        albumDao = albumDao,
        store = AudioCacheStoreWriter(
            audioCacheDao = cacheDao,
            cacheIndex = CacheIndex(
                audioCacheDao = cacheDao,
                deviceFreeSpace = DeviceFreeSpace.of(freeBytes = freeBytes, floorBytes = floorBytes),
            ),
            audioDirectory = folder.root,
            nowMillis = { NOW },
        ),
        byteSource = byteSource,
        nowMillis = { NOW },
    )

    private suspend fun seedAlbum(trackCount: Int = 2, fileIds: List<String?>? = null) {
        albumDao.upsert(albumRow())
        pinDao.upsert(pinRow(state = DownloadStateDb.QUEUED, tracksComplete = 0, tracksTotal = trackCount))
        repeat(trackCount) { index ->
            trackDao.upsert(
                trackRow(
                    track = index + 1,
                    title = "Track " + (index + 1),
                    fileId = if (fileIds == null) "880" + index else fileIds[index],
                    sizeBytes = TRACK_BYTES,
                ),
            )
        }
    }

    private fun pin(): PinEntity = requireNotNull(pinDao.rows[RG])

    // ------------------------------------------------------------------ the happy path

    @Test
    public fun `an album is fetched track by track and marked complete`(): Unit = runTest {
        seedAlbum(trackCount = 2)
        val progress = ArrayList<AlbumDownloadProgress>()

        val outcome = downloader().download(RG) { progress.add(it) }

        assertTrue(outcome is AlbumDownloadOutcome.Success)
        assertEquals(
            "one Range GET per track, never the album zip",
            listOf("8800", "8801"),
            byteSource.requested,
        )
        assertEquals(DownloadStateDb.COMPLETE, pin().downloadState)
        assertEquals(2, pin().tracksComplete)
        assertEquals(
            "progress has to move while the job runs, or the album screen shows nothing",
            listOf(1, 2),
            progress.map { it.tracksComplete },
        )
        assertEquals(2, audioCacheDao.rows.size)
        assertTrue("downloaded rows join the tier that is never evicted", audioCacheDao.rows.values.all { it.pinned })
        assertTrue(audioCacheDao.rows.values.all { it.complete })
    }

    @Test
    public fun `bytes already cached from streaming are promoted rather than re-fetched`(): Unit = runTest {
        seedAlbum(trackCount = 1, fileIds = listOf("8801"))
        // The same server-side file, already complete on the device -- and the bytes are really
        // there. A row on its own is not enough any more, and deliberately so: claiming a file that
        // is not on disk is how an album reported COMPLETE for ever and could never re-download.
        val cached = File(folder.root, "already-streamed.audio").apply {
            writeBytes(ByteArray(TRACK_BYTES.toInt()))
        }
        audioCacheDao.upsert(
            cacheRow(
                track = 1,
                sourceFileId = "8801",
                complete = true,
                path = cached.path,
                sizeBytes = TRACK_BYTES,
            ),
        )

        val outcome = downloader().download(RG)

        assertTrue(outcome is AlbumDownloadOutcome.Success)
        assertTrue("nothing to gain by rewriting identical bytes", byteSource.requested.isEmpty())
        assertEquals(DownloadStateDb.COMPLETE, pin().downloadState)
    }

    // ------------------------------------------------------------------------- resume

    @Test
    public fun `an interrupted track resumes from the bytes already on disk`(): Unit = runTest {
        seedAlbum(trackCount = 1, fileIds = listOf("8801"))
        // Half the track arrived before the process died. The part file survives on purpose: it is
        // what the Range header asks the server to continue from.
        byteSource.bytesPerCall = TRACK_BYTES / 2

        val first = downloader().download(RG)
        assertTrue("a short body is a retry, never a published track", first is AlbumDownloadOutcome.Retry)
        assertTrue("nothing may claim the partial bytes", audioCacheDao.rows.isEmpty())
        assertEquals(
            "a partial album stays DOWNLOADING rather than showing an error the user cannot act on",
            DownloadStateDb.DOWNLOADING,
            pin().downloadState,
        )

        byteSource.bytesPerCall = Long.MAX_VALUE
        val second = downloader().download(RG)

        assertTrue(second is AlbumDownloadOutcome.Success)
        assertEquals(
            "the second attempt must start where the first stopped, not at zero",
            listOf(0L, TRACK_BYTES / 2),
            byteSource.lastResumeOffsets,
        )
        assertEquals(TRACK_BYTES, audioCacheDao.rows.values.single().sizeBytes)
    }

    @Test
    public fun `a 416 discards the partial file and refetches from zero`(): Unit = runTest {
        seedAlbum(trackCount = 1, fileIds = listOf("8801"))
        byteSource.bytesPerCall = TRACK_BYTES / 2
        downloader().download(RG)

        // The server replaced the file underneath us, so the length on disk no longer matches.
        // REQUIREMENTS.md: discard and refetch.
        byteSource.bytesPerCall = Long.MAX_VALUE
        byteSource.failNextWithRangeMismatch()
        val outcome = downloader().download(RG)

        assertTrue(outcome is AlbumDownloadOutcome.Success)
        assertEquals(
            "the third call starts from zero because the partial was thrown away",
            listOf(0L, TRACK_BYTES / 2, 0L),
            byteSource.lastResumeOffsets,
        )
        assertEquals(TRACK_BYTES, audioCacheDao.rows.values.single().sizeBytes)
    }

    // ----------------------------------------------------------------------- failures

    @Test
    public fun `a 403 stops the album and records that download is disabled`(): Unit = runTest {
        seedAlbum(trackCount = 2)
        byteSource.failures.addLast(NetworkError.Forbidden(lane = ApiLane.Subsonic))

        val outcome = downloader().download(RG)

        assertEquals(
            AlbumDownloadOutcome.Failed(NeedlerError.DownloadForbidden),
            outcome,
        )
        assertEquals(DownloadStateDb.FAILED, pin().downloadState)
        assertEquals(
            "the album screen has to be able to say what happened",
            NeedlerError.DownloadForbidden.diagnostic,
            pin().error,
        )
        assertEquals("a permanent failure stops rather than working through the album", 1, byteSource.requested.size)
    }

    @Test
    public fun `a failure after one track landed leaves the album partial rather than failed`(): Unit = runTest {
        seedAlbum(trackCount = 2)
        byteSource.failures.addLast(null)
        byteSource.failures.addLast(NetworkError.Forbidden(lane = ApiLane.Subsonic))

        downloader().download(RG)

        assertEquals(DownloadStateDb.PARTIAL, pin().downloadState)
        assertEquals(1, pin().tracksComplete)
    }

    @Test
    public fun `a transport failure asks for a retry and keeps the bytes`(): Unit = runTest {
        seedAlbum(trackCount = 1)
        byteSource.failures.addLast(
            NetworkError.Offline(
                cause = java.net.SocketTimeoutException("read timed out"),
                kind = NetworkError.Offline.Kind.Timeout,
            ),
        )

        val outcome = downloader().download(RG)

        assertTrue(outcome is AlbumDownloadOutcome.Retry)
        assertEquals(DownloadStateDb.DOWNLOADING, pin().downloadState)
        assertEquals("a retryable failure is not an error to show", null, pin().error)
    }

    @Test
    public fun `no room stops the album and never evicts a download to make space`(): Unit = runTest {
        seedAlbum(trackCount = 1)
        // A device whose free space is already under its floor, with nothing evictable on it.
        val outcome = downloader(freeBytes = 500L, floorBytes = 10_000L).download(RG)

        assertTrue(outcome is AlbumDownloadOutcome.Failed)
        assertEquals(
            NeedlerError.InsufficientStorage(requiredBytes = TRACK_BYTES),
            (outcome as AlbumDownloadOutcome.Failed).error,
        )
        assertEquals(DownloadStateDb.FAILED, pin().downloadState)
        assertTrue("nothing was fetched, so nothing was written", audioCacheDao.rows.isEmpty())
    }

    // -------------------------------------------------------------- partial deliveries

    @Test
    public fun `tracks the server has no file for are skipped, not failed`(): Unit = runTest {
        // A part-delivered pull: the album is in the library and plays what arrived, and the
        // missing tracks exist nowhere - not on the server and not on any device.
        seedAlbum(trackCount = 2, fileIds = listOf("8801", null))

        val outcome = downloader().download(RG)

        assertTrue(outcome is AlbumDownloadOutcome.Success)
        assertEquals(listOf("8801"), byteSource.requested)
        assertEquals(
            "one of two tracks on the device is partial, and the green check must not be drawn",
            DownloadStateDb.PARTIAL,
            pin().downloadState,
        )
    }

    @Test
    public fun `an album with no tracks yet is not reported as complete`(): Unit = runTest {
        albumDao.upsert(albumRow())
        pinDao.upsert(pinRow(state = DownloadStateDb.QUEUED, tracksComplete = 0, tracksTotal = 0))

        val outcome = downloader().download(RG)

        assertEquals(AlbumDownloadOutcome.NothingToDo, outcome)
        assertFalse(
            "an empty album marked complete is the same silent lie as a truncated file",
            pin().downloadState == DownloadStateDb.COMPLETE,
        )
    }

    @Test
    public fun `an album that is not pinned is left alone`(): Unit = runTest {
        albumDao.upsert(albumRow())
        trackDao.upsert(trackRow())

        assertEquals(AlbumDownloadOutcome.NothingToDo, downloader().download(RG))
        assertTrue(byteSource.requested.isEmpty())
    }

    @Test
    public fun `a published track is a real file on disk`(): Unit = runTest {
        seedAlbum(trackCount = 1, fileIds = listOf("8801"))

        downloader().download(RG)

        val row = audioCacheDao.rows.values.single()
        val file = File(row.filePath)
        assertTrue(file.isFile)
        assertEquals(TRACK_BYTES, file.length())
        assertFalse(
            "the part file must not survive a commit",
            File(folder.root, RG + "_1_1.part").exists(),
        )
        assertEquals(
            "the fingerprint recorded is the one the bytes were fetched with",
            "8801",
            row.sourceFileId,
        )
    }

    @Test
    public fun `a failure to index the bytes is a retry, not a worker that dies in silence`(): Unit =
        runTest {
            // Room can fail the index write for reasons that have nothing to do with the download -
            // a full disk, a locked database. Left unguarded that throwable escaped the `Worker`,
            // which `WorkManager` records as a plain failure: no backoff, no second attempt, the pin
            // row frozen on DOWNLOADING and nothing in logcat. The album screen would spin for ever
            // over a condition that clears itself in a minute.
            seedAlbum(trackCount = 1, fileIds = listOf("8801"))
            val refusesToIndex = object : AudioCacheDao by audioCacheDao {
                override suspend fun upsert(row: AudioCacheEntity) {
                    error("database is locked")
                }
            }

            val outcome = downloader(cacheDao = refusesToIndex).download(RG)

            assertTrue(outcome is AlbumDownloadOutcome.Retry)
            assertEquals(
                "a condition that may clear is not a permanent failure to show the user",
                DownloadStateDb.DOWNLOADING,
                pin().downloadState,
            )
            assertTrue("nothing may claim bytes the index refused", audioCacheDao.rows.isEmpty())
        }

    @Test
    public fun `a pass ends by reclaiming partial files whose tracks are on the device`(): Unit =
        runTest {
            // Nothing outside the store ever looks at a partial, so one left beside a published
            // track is bytes that only an uninstall would reclaim. The sweep belongs to the job
            // because the job is the only thing that knows no download of these tracks is running.
            seedAlbum(trackCount = 1, fileIds = listOf("8801"))
            val onDevice = File(folder.root, "already.audio")
            onDevice.writeBytes(ByteArray(TRACK_BYTES.toInt()) { 3 })
            audioCacheDao.upsert(
                cacheRow(
                    track = 1,
                    path = onDevice.path,
                    complete = true,
                    pinned = true,
                    sourceFileId = "8801",
                    sizeBytes = TRACK_BYTES,
                ),
            )
            val stray = File(folder.root, RG + "_1_1.part")
            stray.writeBytes(ByteArray(8) { 9 })

            val outcome = downloader().download(RG)

            assertTrue(outcome is AlbumDownloadOutcome.Success)
            assertTrue("bytes already on the device are never re-fetched", byteSource.requested.isEmpty())
            assertFalse("a partial beside a published track stands for nothing", stray.exists())
            assertTrue("the published bytes are untouched", onDevice.isFile)
        }

    private companion object {
        const val TRACK_BYTES = 64L
        const val NOW = 1_700_000_000_000L
    }
}
