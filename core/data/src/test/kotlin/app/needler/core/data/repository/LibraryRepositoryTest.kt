package app.needler.core.data.repository

import app.needler.core.data.fake.ARTIST_MBID
import app.needler.core.data.fake.FakeAlbumDao
import app.needler.core.data.fake.FakeArtistDao
import app.needler.core.data.fake.FakeAudioCacheDao
import app.needler.core.data.fake.FakeFavouriteDao
import app.needler.core.data.fake.FakePinDao
import app.needler.core.data.fake.FakePullDao
import app.needler.core.data.fake.FakeSubsonicApi
import app.needler.core.data.fake.FakeSyncStateDao
import app.needler.core.data.fake.FakeTrackDao
import app.needler.core.data.fake.FakeV1Api
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.albumRow
import app.needler.core.data.fake.artistRow
import app.needler.core.data.fake.pinRow
import app.needler.core.data.fake.pullRow
import app.needler.core.data.fake.trackRow
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PullStatusDb
import app.needler.core.data.mapper.GenreCodec
import app.needler.core.data.sync.AlbumSyncer
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.Genre
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.StatsSource
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.network.v1.dto.ArtistReleasesDto
import app.needler.core.network.v1.dto.ReleaseItemDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library, served from the mirror.
 *
 * Every read here is a Room query and nothing else, which is what makes offline need no separate
 * code path. The only network-touching members write into the mirror; nothing the UI renders comes
 * from a DTO.
 */
public class LibraryRepositoryTest {

    private val albumDao = FakeAlbumDao()
    private val artistDao = FakeArtistDao()
    private val trackDao = FakeTrackDao()
    private val pinDao = FakePinDao()
    private val pullDao = FakePullDao()
    private val favouriteDao = FakeFavouriteDao()
    private val syncStateDao = FakeSyncStateDao()
    private val audioCacheDao = FakeAudioCacheDao()
    private val subsonic = FakeSubsonicApi()
    private val v1 = FakeV1Api()

    private val repository = DefaultLibraryRepository(
        albumDao = albumDao,
        artistDao = artistDao,
        trackDao = trackDao,
        pinDao = pinDao,
        pullDao = pullDao,
        favouriteDao = favouriteDao,
        syncStateDao = syncStateDao,
        albumSyncer = AlbumSyncer(
            subsonic = subsonic,
            albumDao = albumDao,
            trackDao = trackDao,
            audioCacheDao = audioCacheDao,
            pinDao = pinDao,
            deleteFile = { true },
            nowMillis = { NOW },
        ),
        v1 = v1,
        nowMillis = { NOW },
    )

    private val catalogueOnly = "11111111-2222-3333-4444-555555555555"

    // -------------------------------------------------------------------- reading

    @Test
    public fun `browsing reads the mirror and makes no network call`(): Unit = runTest {
        albumDao.rows[RG] = albumRow()
        artistDao.rows[ARTIST_MBID] = artistRow()
        trackDao.rows["$RG/1/1"] = trackRow()

        assertEquals(1, repository.observeArtists().first().size)
        assertEquals(1, repository.observeAlbumList(AlbumListKind.NEWEST).first().size)
        assertEquals(1, repository.observeAlbumTracks(ReleaseGroupMbid(RG)).first().size)
        assertTrue(v1.calls.isEmpty())
        assertTrue(subsonic.calls.isEmpty())
    }

    @Test
    public fun `an album detail carries its pin's download progress`(): Unit = runTest {
        albumDao.rows[RG] = albumRow(state = AlbumStateDb.PINNED)
        pinDao.rows[RG] = pinRow(state = DownloadStateDb.DOWNLOADING, tracksComplete = 2, tracksTotal = 6)

        val album: Album = repository.observeAlbum(ReleaseGroupMbid(RG)).first()!!

        val state = album.state as AlbumState.Pinned
        val download = state.download as OfflineDownloadState.Downloading
        assertEquals(2, download.tracksComplete)
        assertEquals(6, download.tracksTotal)
    }

    @Test
    public fun `an acquiring album carries its pull's percentage`(): Unit = runTest {
        albumDao.rows[RG] = albumRow(state = AlbumStateDb.ACQUIRING)
        pullDao.rows[RG] = pullRow(status = PullStatusDb.DOWNLOADING, percent = 40)

        val album: Album = repository.observeAlbum(ReleaseGroupMbid(RG)).first()!!

        assertEquals(40, (album.state as AlbumState.Acquiring).progress.percent)
    }

    @Test
    public fun `a list query does not join the pin table, because a grid needs the badge only`(): Unit =
        runTest {
            albumDao.rows[RG] = albumRow(state = AlbumStateDb.PINNED)

            val album: Album = repository.observeAlbumList(AlbumListKind.NEWEST).first().single()

            assertTrue(album.state is AlbumState.Pinned)
            assertEquals(OfflineDownloadState.Queued, (album.state as AlbumState.Pinned).download)
        }

    @Test
    public fun `an owned album's artwork comes from the Subsonic lane`(): Unit = runTest {
        albumDao.rows[RG] = albumRow(state = AlbumStateDb.OWNED)

        val album: Album = repository.observeAlbum(ReleaseGroupMbid(RG)).first()!!

        assertTrue(album.artwork is ArtworkRef.Owned)
    }

    @Test
    public fun `a catalogue-only album's artwork comes from the other lane's cover endpoint`(): Unit =
        runTest {
            albumDao.rows[catalogueOnly] =
                albumRow(mbid = catalogueOnly, state = AlbumStateDb.NOT_OWNED)

            val album: Album = repository.observeAlbum(ReleaseGroupMbid(catalogueOnly)).first()!!

            assertTrue(album.artwork is ArtworkRef.Catalogue)
        }

    @Test
    public fun `alphabetical and recently-added are different orderings`(): Unit = runTest {
        albumDao.rows["a"] = albumRow(mbid = "a", title = "Zeta", addedAt = 3_000L)
        albumDao.rows["b"] = albumRow(mbid = "b", title = "Alpha", addedAt = 1_000L)

        assertEquals(
            listOf("Zeta", "Alpha"),
            repository.observeAlbumList(AlbumListKind.NEWEST).first().map { it.title },
        )
        assertEquals(
            listOf("Alpha", "Zeta"),
            repository.observeAlbumList(AlbumListKind.ALPHABETICAL_BY_NAME).first().map { it.title },
        )
    }

    @Test
    public fun `getTracks returns the requested order, not database order`(): Unit = runTest {
        // The crate and a playlist are restored through here; a queue that came back in database
        // order would silently reshuffle itself.
        trackDao.rows["$RG/1/1"] = trackRow(track = 1, title = "One")
        trackDao.rows["$RG/1/2"] = trackRow(track = 2, title = "Two")
        val keys: List<TrackKey> = listOf(
            TrackKey(ReleaseGroupMbid(RG), 1, 2),
            TrackKey(ReleaseGroupMbid(RG), 1, 1),
        )

        val tracks: List<Track> = repository.getTracks(keys)

        assertEquals(listOf("Two", "One"), tracks.map { it.title })
    }

    @Test
    public fun `getTracks drops keys the mirror has lost rather than failing`(): Unit = runTest {
        trackDao.rows["$RG/1/1"] = trackRow(track = 1)

        val tracks: List<Track> = repository.getTracks(
            listOf(TrackKey(ReleaseGroupMbid(RG), 1, 1), TrackKey(ReleaseGroupMbid(RG), 1, 9)),
        )

        assertEquals(1, tracks.size)
    }

    // --------------------------------------------------------------------- genres

    @Test
    public fun `genres are counted over the denormalised column`(): Unit = runTest {
        albumDao.rows["a"] =
            albumRow(mbid = "a", genres = GenreCodec.encode(listOf("post-rock", "slowcore")))
        albumDao.rows["b"] = albumRow(mbid = "b", genres = GenreCodec.encode(listOf("post-rock")))

        val genres: List<Genre> = repository.observeGenres().first()

        assertEquals(listOf("post-rock", "slowcore"), genres.map { it.name })
        assertEquals(2, genres.first().albumCount)
    }

    @Test
    public fun `the genre like pattern brackets the term so rock does not match rockabilly`() {
        val pattern: String = GenreCodec.likePattern("rock")

        assertEquals("%|rock|%", pattern)
        assertTrue(GenreCodec.encode(listOf("rock"))!!.contains("|rock|"))
        assertFalse(GenreCodec.encode(listOf("rockabilly"))!!.contains("|rock|"))
    }

    // ---------------------------------------------------------------------- stats

    @Test
    public fun `stats fall back to the mirror when the server has never been reached`(): Unit =
        runTest {
            albumDao.rows[RG] = albumRow(sizeBytes = 1_000L)
            trackDao.rows["$RG/1/1"] = trackRow()
            artistDao.rows[ARTIST_MBID] = artistRow()

            val stats: LibraryStats = repository.observeLibraryStats().first()

            assertEquals(StatsSource.LOCAL_MIRROR, stats.source)
            assertEquals(1, stats.albumCount)
            assertEquals(1_000L, stats.totalSizeBytes)
        }

    @Test
    public fun `the server's totals win once they have been read`(): Unit = runTest {
        albumDao.rows[RG] = albumRow(sizeBytes = 1_000L)
        syncStateDao.row = syncStateDao.row?.copy(
            serverAlbumCount = 176,
            serverSizeBytes = 42_000_000_000L,
        )

        val stats: LibraryStats = repository.observeLibraryStats().first()

        assertEquals(StatsSource.SERVER, stats.source)
        assertEquals(176, stats.albumCount)
    }

    // ------------------------------------------------------------- discographies

    @Test
    public fun `refreshing a discography inserts only what the mirror does not have`(): Unit =
        runTest {
            albumDao.rows[RG] = albumRow(state = AlbumStateDb.PINNED)
            artistDao.rows[ARTIST_MBID] = artistRow()
            v1.artistReleasesResponse = {
                ArtistReleasesDto(
                    albums = listOf(
                        ReleaseItemDto(id = RG, title = "Spiderland", year = 1991),
                        ReleaseItemDto(id = catalogueOnly, title = "Tweez", year = 1989),
                    ),
                )
            }

            repository.refreshArtistDiscography(ArtistMbid(ARTIST_MBID))

            // The owned row is untouched: overwriting it with the catalogue copy would downgrade it
            // to a search result and lose the badge on artist detail.
            assertEquals(AlbumStateDb.PINNED, albumDao.rows[RG]?.state)
            assertEquals(AlbumStateDb.NOT_OWNED, albumDao.rows[catalogueOnly]?.state)
        }

    @Test
    public fun `the discography reads owned first, then everything un-owned`(): Unit = runTest {
        albumDao.rows[catalogueOnly] = albumRow(
            mbid = catalogueOnly,
            title = "Tweez",
            state = AlbumStateDb.NOT_OWNED,
            year = 1989,
        )
        albumDao.rows[RG] = albumRow(title = "Spiderland", state = AlbumStateDb.OWNED, year = 1991)

        val albums: List<Album> = repository.observeArtistDiscography(ArtistMbid(ARTIST_MBID)).first()

        assertEquals(listOf("Spiderland", "Tweez"), albums.map { it.title })
        assertEquals(AlbumState.NotOwned, albums.last().state)
    }

    @Test
    public fun `owned albums by artist exclude the catalogue half`(): Unit = runTest {
        albumDao.rows[RG] = albumRow(state = AlbumStateDb.OWNED)
        albumDao.rows[catalogueOnly] = albumRow(mbid = catalogueOnly, state = AlbumStateDb.NOT_OWNED)

        val owned: List<Album> = repository.observeOwnedAlbumsByArtist(ArtistMbid(ARTIST_MBID)).first()

        assertEquals(1, owned.size)
        assertEquals(RG, owned.single().releaseGroupMbid.value)
    }

    @Test
    public fun `an empty discography response writes nothing`(): Unit = runTest {
        repository.refreshArtistDiscography(ArtistMbid(ARTIST_MBID))

        assertTrue(albumDao.rows.isEmpty())
    }

    @Test
    public fun `a degraded session fails the discography without disturbing the owned half`(): Unit =
        runTest {
            albumDao.rows[RG] = albumRow()
            v1.failWith = {
                app.needler.core.network.NetworkError.Unauthorised(
                    app.needler.core.network.ApiLane.V1,
                )
            }

            val result = repository.refreshArtistDiscography(ArtistMbid(ARTIST_MBID))

            assertTrue(result is app.needler.core.domain.model.Outcome.Failure)
            assertEquals(1, repository.observeOwnedAlbumsByArtist(ArtistMbid(ARTIST_MBID)).first().size)
        }

    // ------------------------------------------------------------------- refresh

    @Test
    public fun `refreshing one album delegates to the single staleness checkpoint`(): Unit = runTest {
        subsonic.albumResponse = { app.needler.core.data.fake.albumDto() }

        repository.refreshAlbum(ReleaseGroupMbid(RG))

        assertTrue(subsonic.calls.any { it.startsWith("getAlbum(al-") })
        assertEquals(1, trackDao.rows.size)
    }

    @Test
    public fun `a missing album reads as null rather than an empty shell`(): Unit = runTest {
        assertNull(repository.getAlbum(ReleaseGroupMbid(RG)))
        assertNull(repository.observeAlbum(ReleaseGroupMbid(RG)).first())
    }

    private companion object {
        const val NOW: Long = 1_000L
    }
}
