package app.needler.core.data.sync

import app.needler.core.data.fake.ARTIST_MBID
import app.needler.core.data.fake.FakeAlbumDao
import app.needler.core.data.fake.FakeArtistDao
import app.needler.core.data.fake.FakeAudioCacheDao
import app.needler.core.data.fake.FakePinDao
import app.needler.core.data.fake.FakeSubsonicApi
import app.needler.core.data.fake.FakeSyncStateDao
import app.needler.core.data.fake.FakeTrackDao
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.albumDto
import app.needler.core.data.fake.albumRow
import app.needler.core.data.fake.artistDto
import app.needler.core.data.fake.artistRow
import app.needler.core.data.fake.indexesDto
import app.needler.core.data.fake.songDto
import app.needler.core.domain.model.FullSyncReason
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.SyncPhase
import app.needler.core.domain.model.SyncReport
import app.needler.core.network.subsonic.dto.ArtistIndexDto
import app.needler.core.network.subsonic.dto.ArtistsDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delta sync's decisions.
 *
 * The trap this fixes is named in the requirements: **`getArtists` accepts `ifModifiedSince` and
 * ignores it** on this server, returning the whole artist list every time. A delta built on it is a
 * full sync wearing a delta's clothes, and the "one request, under 100 ms on an unchanged library"
 * budget could never be met. Only `getIndexes` honours the parameter, so the request log is asserted
 * as well as the result.
 */
public class LibrarySyncEngineTest {

    private val subsonic = FakeSubsonicApi()
    private val artistDao = FakeArtistDao()
    private val albumDao = FakeAlbumDao()
    private val trackDao = FakeTrackDao()
    private val audioCacheDao = FakeAudioCacheDao()
    private val pinDao = FakePinDao()
    private val syncStateDao = FakeSyncStateDao()

    private val albumSyncer = AlbumSyncer(
        subsonic = subsonic,
        albumDao = albumDao,
        trackDao = trackDao,
        audioCacheDao = audioCacheDao,
        pinDao = pinDao,
        deleteFile = { true },
        nowMillis = { 2_000L },
    )

    private val engine = LibrarySyncEngine(
        subsonic = subsonic,
        artistDao = artistDao,
        albumDao = albumDao,
        syncStateDao = syncStateDao,
        albumSyncer = albumSyncer,
        nowMillis = { 2_000L },
    )

    // --------------------------------------------------------- the unchanged case

    @Test
    public fun `an unchanged library is one request and nothing else`(): Unit = runTest {
        syncStateDao.row = syncStateDao.row?.copy(libraryRevision = "1000")
        subsonic.indexesResponse = { indexesDto(lastModified = 1000L) }

        val report: SyncReport = (engine.deltaSync() as Outcome.Success).value

        assertTrue(report.libraryUnchanged)
        assertEquals(SyncPhase.DELTA, report.phase)
        assertEquals(listOf("getIndexes(1000)"), subsonic.calls)
    }

    @Test
    public fun `a delta never calls getArtists, which ignores ifModifiedSince on this server`(): Unit =
        runTest {
            syncStateDao.row = syncStateDao.row?.copy(libraryRevision = "1000")
            subsonic.indexesResponse = { indexesDto(lastModified = 2000L, artistIds = listOf("ar-$ARTIST_MBID")) }
            subsonic.artistResponse = { artistDto() }
            subsonic.albumResponse = { albumDto() }

            engine.deltaSync()

            assertFalse(subsonic.calls.contains("getArtists"))
            assertTrue(subsonic.calls.any { it.startsWith("getIndexes") })
        }

    @Test
    public fun `the stored revision is fed back as ifModifiedSince`(): Unit = runTest {
        syncStateDao.row = syncStateDao.row?.copy(libraryRevision = "4242")
        subsonic.indexesResponse = { indexesDto(lastModified = 4242L) }

        engine.deltaSync()

        assertTrue(subsonic.calls.contains("getIndexes(4242)"))
    }

    @Test
    public fun `the new revision is recorded so the next delta is the cheap one`(): Unit = runTest {
        syncStateDao.row = syncStateDao.row?.copy(libraryRevision = "1000")
        subsonic.indexesResponse = { indexesDto(lastModified = 5000L) }

        val report: SyncReport = (engine.deltaSync() as Outcome.Success).value

        assertEquals("5000", report.newRevision)
        assertEquals("5000", syncStateDao.row?.libraryRevision)
    }

    // --------------------------------------------------------------- changed work

    @Test
    public fun `a moved revision pulls the changed artists and then their albums`(): Unit = runTest {
        syncStateDao.row = syncStateDao.row?.copy(libraryRevision = "1000")
        subsonic.indexesResponse = { indexesDto(lastModified = 2000L, artistIds = listOf("ar-$ARTIST_MBID")) }
        subsonic.artistResponse = { artistDto(albums = listOf(albumDto())) }
        subsonic.albumResponse = { albumDto(songs = listOf(songDto(), songDto(fileId = "2", track = 2))) }

        val report: SyncReport = (engine.deltaSync() as Outcome.Success).value

        assertFalse(report.libraryUnchanged)
        assertEquals(1, report.artistsUpdated)
        assertEquals(1, report.albumsUpdated)
        assertEquals(2, report.tracksUpdated)
        assertTrue(subsonic.calls.any { it.startsWith("getArtist(ar-") })
        assertTrue(subsonic.calls.any { it.startsWith("getAlbum(al-") })
    }

    @Test
    public fun `additions are found through getAlbumList2 newest, not only through the index`(): Unit =
        runTest {
            // An album can land under an artist the index did not flag, because the artist row itself
            // did not change. Without this step such an album would never appear.
            syncStateDao.row = syncStateDao.row?.copy(libraryRevision = "1000")
            subsonic.indexesResponse = { indexesDto(lastModified = 2000L) }
            subsonic.albumListResponse = { listOf(albumDto()) }
            subsonic.albumResponse = { albumDto() }

            val report: SyncReport = (engine.deltaSync() as Outcome.Success).value

            assertTrue(subsonic.calls.contains("getAlbumList2(newest)"))
            assertEquals(1, report.albumsUpdated)
        }

    @Test
    public fun `an album the mirror already owns is not re-synced from the newest list`(): Unit =
        runTest {
            syncStateDao.row = syncStateDao.row?.copy(libraryRevision = "1000")
            albumDao.rows[RG] = albumRow()
            subsonic.indexesResponse = { indexesDto(lastModified = 2000L) }
            subsonic.albumListResponse = { listOf(albumDto()) }

            val report: SyncReport = (engine.deltaSync() as Outcome.Success).value

            assertEquals(0, report.albumsUpdated)
            assertFalse(subsonic.calls.any { it.startsWith("getAlbum(") })
        }

    @Test
    public fun `force still skips the work when the revision has not moved`(): Unit = runTest {
        // Forcing means "ask the server now", not "re-download a library the server says is the same".
        syncStateDao.row = syncStateDao.row?.copy(libraryRevision = "1000")
        subsonic.indexesResponse = { indexesDto(lastModified = 1000L) }
        subsonic.albumListResponse = { emptyList() }

        val report: SyncReport = (engine.deltaSync(force = true) as Outcome.Success).value

        assertFalse(report.libraryUnchanged)
        assertEquals(0, report.albumsUpdated)
    }

    @Test
    public fun `a first delta with no stored revision does the work`(): Unit = runTest {
        subsonic.indexesResponse = { indexesDto(lastModified = 900L, artistIds = listOf("ar-$ARTIST_MBID")) }
        subsonic.artistResponse = { artistDto() }
        subsonic.albumResponse = { albumDto() }

        val report: SyncReport = (engine.deltaSync() as Outcome.Success).value

        assertFalse(report.libraryUnchanged)
        assertTrue(subsonic.calls.contains("getIndexes(null)"))
    }

    // ------------------------------------------------------------------- failures

    @Test
    public fun `a failed delta leaves the stored revision alone`(): Unit = runTest {
        syncStateDao.row = syncStateDao.row?.copy(libraryRevision = "1000")
        subsonic.failWith = {
            app.needler.core.network.NetworkError.Offline(java.io.IOException("offline"))
        }

        val result: Outcome<SyncReport> = engine.deltaSync()

        assertTrue(result is Outcome.Failure)
        assertEquals("1000", syncStateDao.row?.libraryRevision)
    }

    // ---------------------------------------------------------------- full sync

    @Test
    public fun `a full sync walks every artist and every album`(): Unit = runTest {
        subsonic.artistsResponse = {
            ArtistsDto(
                index = listOf(
                    ArtistIndexDto(name = "S", artist = listOf(artistDto())),
                ),
            )
        }
        subsonic.artistResponse = { artistDto(albums = listOf(albumDto())) }
        subsonic.albumResponse = { albumDto(songs = listOf(songDto())) }
        subsonic.indexesResponse = { indexesDto(lastModified = 7000L) }

        val report: SyncReport = (engine.fullSync(FullSyncReason.FIRST_CONNECT) as Outcome.Success).value

        assertEquals(SyncPhase.FULL, report.phase)
        assertEquals(1, report.artistsUpdated)
        assertEquals(1, report.albumsUpdated)
        // `getArtists` IS the right call here: the whole list is what a full sync wants.
        assertTrue(subsonic.calls.contains("getArtists"))
        assertEquals("7000", syncStateDao.row?.libraryRevision)
        assertTrue((syncStateDao.row?.lastFullSyncAt ?: 0L) > 0L)
    }

    @Test
    public fun `a full sync keeps the user's monitored flag, which the library lane never sees`(): Unit =
        runTest {
            artistDao.rows[ARTIST_MBID] = artistRow(monitored = true)
            subsonic.artistsResponse = {
                ArtistsDto(index = listOf(ArtistIndexDto(name = "S", artist = listOf(artistDto()))))
            }
            subsonic.artistResponse = { artistDto(albums = emptyList()) }
            subsonic.indexesResponse = { indexesDto(lastModified = 1L) }

            engine.fullSync(FullSyncReason.USER_REQUESTED)

            assertTrue(artistDao.rows[ARTIST_MBID]?.monitored == true)
        }

    // --------------------------------------------------------------- scan status

    @Test
    public fun `a settled scan records the last-scan time for the header line`(): Unit = runTest {
        subsonic.scanStatusResponse = {
            app.needler.core.network.subsonic.dto.ScanStatusDto(scanning = false)
        }

        engine.refreshScanStatus()

        assertEquals(2_000L, syncStateDao.row?.lastScanAt)
    }

    @Test
    public fun `a scan still running does not stamp a completion time`(): Unit = runTest {
        subsonic.scanStatusResponse = {
            app.needler.core.network.subsonic.dto.ScanStatusDto(scanning = true)
        }

        engine.refreshScanStatus()

        assertEquals(null, syncStateDao.row?.lastScanAt)
    }
}
