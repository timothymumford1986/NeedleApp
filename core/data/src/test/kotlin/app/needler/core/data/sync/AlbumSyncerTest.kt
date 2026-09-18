package app.needler.core.data.sync

import app.needler.core.data.fake.FakeAlbumDao
import app.needler.core.data.fake.FakeAudioCacheDao
import app.needler.core.data.fake.FakePinDao
import app.needler.core.data.fake.FakeSubsonicApi
import app.needler.core.data.fake.FakeTrackDao
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.albumDto
import app.needler.core.data.fake.albumRow
import app.needler.core.data.fake.cacheRow
import app.needler.core.data.fake.pinRow
import app.needler.core.data.fake.songDto
import app.needler.core.data.fake.trackRow
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.domain.model.AlbumSyncReport
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Syncing one album, and the staleness rule that goes with it.
 *
 * The rule the first draft got wrong is the one most of these tests are about: the comparison is
 * against the fingerprint snapshotted on `audio_cache` at download time, **never** against the
 * `track` row. Sync overwrites `track` in this same pass, so comparing against it compares the
 * mirror with itself and reports "unchanged" every time - the user keeps the worse file for ever and
 * nothing says so.
 */
public class AlbumSyncerTest {

    private val subsonic = FakeSubsonicApi()
    private val albumDao = FakeAlbumDao()
    private val trackDao = FakeTrackDao()
    private val audioCacheDao = FakeAudioCacheDao()
    private val pinDao = FakePinDao()
    private val deleted: MutableList<String> = ArrayList()

    private val syncer = AlbumSyncer(
        subsonic = subsonic,
        albumDao = albumDao,
        trackDao = trackDao,
        audioCacheDao = audioCacheDao,
        pinDao = pinDao,
        deleteFile = { path -> deleted.add(path); true },
        nowMillis = { 2_000L },
    )

    // ------------------------------------------------------------ the happy path

    @Test
    public fun `a sync writes the album and its tracks into the mirror`(): Unit = runTest {
        subsonic.albumResponse = {
            albumDto(songs = listOf(songDto(fileId = "1", track = 1), songDto(fileId = "2", track = 2)))
        }

        val result: Outcome<AlbumSyncReport> = syncer.syncAlbum(ReleaseGroupMbid(RG))

        assertTrue(result is Outcome.Success)
        assertEquals(2, (result as Outcome.Success).value.trackCount)
        assertNotNull(albumDao.rows[RG])
        assertEquals(2, trackDao.rows.size)
    }

    @Test
    public fun `a track the server dropped is removed rather than left behind`(): Unit = runTest {
        trackDao.rows["$RG/1/1"] = trackRow(track = 1)
        trackDao.rows["$RG/1/2"] = trackRow(track = 2, fileId = "8802")
        subsonic.albumResponse = { albumDto(songs = listOf(songDto(fileId = "1", track = 1))) }

        syncer.syncAlbum(ReleaseGroupMbid(RG))

        assertEquals(1, trackDao.rows.size)
        assertNotNull(trackDao.rows["$RG/1/1"])
    }

    @Test
    public fun `a pinned album stays pinned across a sync`(): Unit = runTest {
        albumDao.rows[RG] = albumRow(state = AlbumStateDb.PINNED)

        syncer.syncAlbum(ReleaseGroupMbid(RG))

        assertEquals(AlbumStateDb.PINNED, albumDao.rows[RG]?.state)
    }

    @Test
    public fun `an acquiring album becomes owned, because it is now in the library`(): Unit = runTest {
        albumDao.rows[RG] = albumRow(state = AlbumStateDb.ACQUIRING)

        syncer.syncAlbum(ReleaseGroupMbid(RG))

        assertEquals(AlbumStateDb.OWNED, albumDao.rows[RG]?.state)
    }

    // ------------------------------------------------------------ the staleness rule

    @Test
    public fun `a changed file id evicts the cached bytes - this is the quality upgrade`(): Unit =
        runTest {
            audioCacheDao.rows["$RG/1/1"] = cacheRow(sourceFileId = "8801")
            subsonic.albumResponse = { albumDto(songs = listOf(songDto(fileId = "9402", track = 1))) }

            val report: AlbumSyncReport =
                (syncer.syncAlbum(ReleaseGroupMbid(RG)) as Outcome.Success).value

            assertEquals(1, report.staleTracksEvicted.size)
            assertEquals(listOf("/data/audio/1.audio"), deleted)
            assertNull(audioCacheDao.rows["$RG/1/1"])
        }

    @Test
    public fun `the comparison is against the cache fingerprint, not against the track row`(): Unit =
        runTest {
            // The mirror already holds the server's *new* values - exactly what a sync leaves behind,
            // and exactly the state in which comparing `track` against the payload says "unchanged".
            trackDao.rows["$RG/1/1"] = trackRow(fileId = "9402", sizeBytes = 55_000_000L)
            // The bytes on disk were fetched with the old file.
            audioCacheDao.rows["$RG/1/1"] =
                cacheRow(sourceFileId = "8801", sourceSizeBytes = 40_000_000L)
            subsonic.albumResponse = {
                albumDto(songs = listOf(songDto(fileId = "9402", track = 1, size = 55_000_000L)))
            }

            val report: AlbumSyncReport =
                (syncer.syncAlbum(ReleaseGroupMbid(RG)) as Outcome.Success).value

            // A `track`-based comparison would report nothing here. That is the bug this asserts away.
            assertEquals(1, report.staleTracksEvicted.size)
        }

    @Test
    public fun `an unchanged file keeps its bytes`(): Unit = runTest {
        audioCacheDao.rows["$RG/1/1"] = cacheRow()
        subsonic.albumResponse = { albumDto(songs = listOf(songDto())) }

        val report: AlbumSyncReport = (syncer.syncAlbum(ReleaseGroupMbid(RG)) as Outcome.Success).value

        assertTrue(report.staleTracksEvicted.isEmpty())
        assertTrue(deleted.isEmpty())
        assertNotNull(audioCacheDao.rows["$RG/1/1"])
    }

    @Test
    public fun `a null on either side is unknown, never changed`(): Unit = runTest {
        // A server release that stopped reporting durations must not re-download the whole offline
        // library, possibly over mobile data. Unknown is not evidence.
        audioCacheDao.rows["$RG/1/1"] = cacheRow(sourceDurationMs = 353_000L)
        subsonic.albumResponse = {
            albumDto(songs = listOf(songDto(durationSeconds = null, size = null)))
        }

        val report: AlbumSyncReport = (syncer.syncAlbum(ReleaseGroupMbid(RG)) as Outcome.Success).value

        assertTrue(report.staleTracksEvicted.isEmpty())
        assertNotNull(audioCacheDao.rows["$RG/1/1"])
    }

    @Test
    public fun `a cache row written before a column existed is not condemned by its nulls`(): Unit =
        runTest {
            audioCacheDao.rows["$RG/1/1"] = cacheRow(
                sourceSizeBytes = null,
                sourceDurationMs = null,
                sourceFormat = null,
            )
            subsonic.albumResponse = { albumDto(songs = listOf(songDto(fileId = "8801"))) }

            val report: AlbumSyncReport =
                (syncer.syncAlbum(ReleaseGroupMbid(RG)) as Outcome.Success).value

            assertTrue(report.staleTracksEvicted.isEmpty())
        }

    @Test
    public fun `a changed size, duration or format is each stale on its own`(): Unit = runTest {
        audioCacheDao.rows["$RG/1/1"] = cacheRow(sourceSizeBytes = 40_000_000L)
        audioCacheDao.rows["$RG/1/2"] = cacheRow(track = 2, sourceDurationMs = 353_000L)
        audioCacheDao.rows["$RG/1/3"] = cacheRow(track = 3, sourceFormat = "flac")
        subsonic.albumResponse = {
            albumDto(
                songs = listOf(
                    songDto(fileId = "8801", track = 1, size = 41_000_000L),
                    songDto(fileId = "8801", track = 2, durationSeconds = 400),
                    songDto(fileId = "8801", track = 3, suffix = "mp3"),
                ),
            )
        }

        val report: AlbumSyncReport = (syncer.syncAlbum(ReleaseGroupMbid(RG)) as Outcome.Success).value

        assertEquals(3, report.staleTracksEvicted.size)
    }

    @Test
    public fun `a track missing from the payload keeps its bytes, which are still good audio`(): Unit =
        runTest {
            // `audio_cache` deliberately has no foreign key to `track`: a re-import that rewrites a
            // row must not delete perfectly good audio for that position on that record.
            audioCacheDao.rows["$RG/1/9"] = cacheRow(track = 9)
            subsonic.albumResponse = { albumDto(songs = listOf(songDto(track = 1))) }

            val report: AlbumSyncReport =
                (syncer.syncAlbum(ReleaseGroupMbid(RG)) as Outcome.Success).value

            assertTrue(report.staleTracksEvicted.isEmpty())
            assertNotNull(audioCacheDao.rows["$RG/1/9"])
        }

    // ------------------------------------------------------------- re-downloading

    @Test
    public fun `a pinned track that went stale is queued for re-download`(): Unit = runTest {
        audioCacheDao.rows["$RG/1/1"] = cacheRow(pinned = true, sourceFileId = "8801")
        pinDao.rows[RG] = pinRow(state = DownloadStateDb.COMPLETE, tracksComplete = 1, tracksTotal = 1)
        subsonic.albumResponse = { albumDto(songs = listOf(songDto(fileId = "9402"))) }

        val report: AlbumSyncReport = (syncer.syncAlbum(ReleaseGroupMbid(RG)) as Outcome.Success).value

        assertEquals(1, report.tracksQueuedForRedownload.size)
        // The green check has to go: leaving the album marked complete is how a silent downgrade hides.
        assertFalse(pinDao.rows[RG]?.downloadState == DownloadStateDb.COMPLETE)
    }

    @Test
    public fun `an unpinned stale track is evicted but not re-fetched`(): Unit = runTest {
        audioCacheDao.rows["$RG/1/1"] = cacheRow(pinned = false, sourceFileId = "8801")
        subsonic.albumResponse = { albumDto(songs = listOf(songDto(fileId = "9402"))) }

        val report: AlbumSyncReport = (syncer.syncAlbum(ReleaseGroupMbid(RG)) as Outcome.Success).value

        assertEquals(1, report.staleTracksEvicted.size)
        assertTrue(report.tracksQueuedForRedownload.isEmpty())
    }

    // ------------------------------------------------------------------- failure

    @Test
    public fun `a transport failure leaves the mirror untouched`(): Unit = runTest {
        albumDao.rows[RG] = albumRow()
        subsonic.failWith = {
            app.needler.core.network.NetworkError.Offline(java.io.IOException("no route"))
        }

        val result: Outcome<AlbumSyncReport> = syncer.syncAlbum(ReleaseGroupMbid(RG))

        assertTrue(result is Outcome.Failure)
        assertTrue(trackDao.rows.isEmpty())
    }
}
