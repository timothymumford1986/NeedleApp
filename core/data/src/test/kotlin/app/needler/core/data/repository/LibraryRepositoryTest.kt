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
import app.needler.core.data.local.entity.FavouriteEntity
import app.needler.core.data.local.entity.FavouriteTypeDb
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
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.StatsSource
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.TrackListKind
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
    private val favouriteDao = FakeFavouriteDao()

    // The Songs tab's queries are joins, so the fake is given the two tables it joins to. Declared
    // after both, because a property initialiser can only see what is already above it.
    private val trackDao = FakeTrackDao(albums = albumDao, favourites = favouriteDao)
    private val pinDao = FakePinDao()
    private val pullDao = FakePullDao()
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

    /**
     * The id from the device log: a UUID v5, third group `5e80`.
     *
     * DroppedNeedle derived it from the artist's name, so the catalogue lane refuses it and
     * always will.
     */
    private val NAME_DERIVED_ARTIST = "8cfce742-445e-5e80-93a8-d8f924d56984"

    /** A version 4 UUID, which is what MusicBrainz mints. */
    private val REAL_ARTIST_MBID = "b3d01a12-9d1d-4e4e-9b1a-7a2f2b49a2c9"

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

    // ------------------------------------------------------------------ songs tab

    /*
     * The Songs tab had no query of its own and was assembled from the tracks of the first forty
     * albums of the *album* list. Two faults followed from that and both were seen on a device: the
     * tab showed a sample rather than the library, and "Title" ordered by album title, so choosing
     * it produced a single record in track order. The tests below are written against those two
     * symptoms rather than against the implementation, so a future attempt to serve songs from an
     * album list fails them again.
     */

    @Test
    public fun `the songs tab lists every track in the library, not a sample of it`(): Unit =
        runTest {
            albumDao.rows["a"] = albumRow(mbid = "a")
            albumDao.rows["b"] = albumRow(mbid = "b")
            trackDao.rows["a/1/1"] = trackRow(mbid = "a", track = 1, title = "One")
            trackDao.rows["a/1/2"] = trackRow(mbid = "a", track = 2, title = "Two")
            trackDao.rows["b/1/1"] = trackRow(mbid = "b", track = 1, title = "Three")

            val songs: List<Track> = repository.observeTracks(TrackListKind.NEWEST).first()

            assertEquals(3, songs.size)
        }

    @Test
    public fun `sorting songs by title sorts by the song's title, not its album's`(): Unit =
        runTest {
            // The regression, stated as data: the album ordering and the song ordering disagree,
            // and only the song ordering is correct on this tab. Under the old stand-in this
            // returned Aardvark's two tracks first because its *album* sorts first.
            albumDao.rows["a"] = albumRow(mbid = "a", title = "Aardvark")
            albumDao.rows["z"] = albumRow(mbid = "z", title = "Zoology")
            trackDao.rows["a/1/1"] = trackRow(mbid = "a", track = 1, title = "Yesterday")
            trackDao.rows["a/1/2"] = trackRow(mbid = "a", track = 2, title = "Zanzibar")
            trackDao.rows["z/1/1"] = trackRow(mbid = "z", track = 1, title = "Anthem")

            val titles: List<String> =
                repository.observeTracks(TrackListKind.ALPHABETICAL_BY_TITLE).first().map { it.title }

            assertEquals(listOf("Anthem", "Yesterday", "Zanzibar"), titles)
        }

    @Test
    public fun `sorting songs by artist keeps each record whole and in running order`(): Unit =
        runTest {
            albumDao.rows["b"] = albumRow(mbid = "b", title = "Bee", artist = "Zither")
            albumDao.rows["a"] = albumRow(mbid = "a", title = "Ay", artist = "Aviary")
            trackDao.rows["b/1/2"] = trackRow(mbid = "b", track = 2, title = "Second")
            trackDao.rows["b/1/1"] = trackRow(mbid = "b", track = 1, title = "First")
            trackDao.rows["a/1/1"] = trackRow(mbid = "a", track = 1, title = "Only")

            val titles: List<String> = repository
                .observeTracks(TrackListKind.ALPHABETICAL_BY_ARTIST)
                .first()
                .map { it.title }

            // Aviary's record first, then Zither's - and Zither's in track order, not alphabetical.
            assertEquals(listOf("Only", "First", "Second"), titles)
        }

    @Test
    public fun `recently added songs arrive with their album, undated albums last`(): Unit = runTest {
        albumDao.rows["old"] = albumRow(mbid = "old", title = "Old", addedAt = 1_000L)
        albumDao.rows["new"] = albumRow(mbid = "new", title = "New", addedAt = 9_000L)
        albumDao.rows["undated"] = albumRow(mbid = "undated", title = "Undated", addedAt = null)
        trackDao.rows["old/1/1"] = trackRow(mbid = "old", title = "From the old one")
        trackDao.rows["new/1/1"] = trackRow(mbid = "new", title = "From the new one")
        trackDao.rows["undated/1/1"] = trackRow(mbid = "undated", title = "From the undated one")

        val titles: List<String> =
            repository.observeTracks(TrackListKind.NEWEST).first().map { it.title }

        assertEquals(
            listOf("From the new one", "From the old one", "From the undated one"),
            titles,
        )
    }

    @Test
    public fun `most played falls back to recently added, because the mirror counts no plays`(): Unit =
        runTest {
            // Not an oversight and not a placeholder: `getAlbumList2?type=frequent` is computed
            // server-side, and the only play count in the mirror is on `audio_cache`, which is
            // deleted with the bytes it describes. Ordering by it would make a well-worn song
            // vanish from "most played" the moment the cache reclaimed it.
            albumDao.rows["old"] = albumRow(mbid = "old", addedAt = 1_000L)
            albumDao.rows["new"] = albumRow(mbid = "new", addedAt = 9_000L)
            trackDao.rows["old/1/1"] = trackRow(mbid = "old", title = "Older")
            trackDao.rows["new/1/1"] = trackRow(mbid = "new", title = "Newer")

            assertEquals(
                repository.observeTracks(TrackListKind.NEWEST).first().map { it.title },
                repository.observeTracks(TrackListKind.FREQUENT).first().map { it.title },
            )
        }

    @Test
    public fun `starred songs come from the favourite table, recently starred first`(): Unit =
        runTest {
            albumDao.rows[RG] = albumRow()
            trackDao.rows["$RG/1/1"] = trackRow(track = 1, title = "Starred early")
            trackDao.rows["$RG/1/2"] = trackRow(track = 2, title = "Starred late")
            trackDao.rows["$RG/1/3"] = trackRow(track = 3, title = "Not starred")
            favouriteDao.rows["track/$RG/1/1"] = starredTrack(track = 1, starredAt = 1_000L)
            favouriteDao.rows["track/$RG/1/2"] = starredTrack(track = 2, starredAt = 5_000L)

            val songs: List<Track> = repository.observeTracks(TrackListKind.STARRED).first()

            assertEquals(listOf("Starred late", "Starred early"), songs.map { it.title })
            // Every row this ordering can return is starred by construction, so the flag is taken
            // from the query rather than from a second lookup per song.
            assertTrue(songs.all { it.isFavourite })
        }

    @Test
    public fun `a song row carries its album title and borrows the album artist when it has none`(): Unit =
        runTest {
            albumDao.rows[RG] = albumRow(title = "Spiderland", artist = "Slint")
            trackDao.rows["$RG/1/1"] = trackRow(title = "Nosferatu Man", artist = null)

            val song: Track = repository.observeTracks(TrackListKind.NEWEST).first().single()

            assertEquals("Spiderland", song.albumTitle)
            // The server sends a per-track artist only where it differs from the album's, so a row
            // rendered "· Spiderland" with nothing before the separator would read as a fault.
            assertEquals("Slint", song.artistName)
        }

    @Test
    public fun `a track whose album has left the library is not a song in the library`(): Unit =
        runTest {
            albumDao.rows[RG] = albumRow(state = AlbumStateDb.NOT_OWNED)
            trackDao.rows["$RG/1/1"] = trackRow()

            assertTrue(repository.observeTracks(TrackListKind.NEWEST).first().isEmpty())
        }

    @Test
    public fun `the songs query is bounded, so a large library is never loaded whole`(): Unit =
        runTest {
            albumDao.rows[RG] = albumRow()
            repeat(10) { index ->
                trackDao.rows["$RG/1/${index + 1}"] =
                    trackRow(track = index + 1, title = "Song " + ('a' + index))
            }

            val first: List<Track> =
                repository.observeTracks(TrackListKind.ALPHABETICAL_BY_TITLE, limit = 3).first()
            val second: List<Track> = repository
                .observeTracks(TrackListKind.ALPHABETICAL_BY_TITLE, limit = 3, offset = 3)
                .first()

            assertEquals(listOf("Song a", "Song b", "Song c"), first.map { it.title })
            assertEquals(listOf("Song d", "Song e", "Song f"), second.map { it.title })
        }

    private fun starredTrack(track: Int, starredAt: Long): FavouriteEntity = FavouriteEntity(
        entityType = FavouriteTypeDb.TRACK,
        entityId = "$RG/1/$track",
        starredAt = starredAt,
        releaseGroupMbid = RG,
        discNo = 1,
        trackNo = track,
    )

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

    /**
     * A warming response is not an empty discography.
     *
     * The device case: an artist with four owned albums and a dozen un-owned ones showed the four
     * and said nothing at all about the rest - no section, no sentence, no retry, nothing in
     * logcat. `warming` means the server is still resolving the artist upstream and the three
     * buckets come back empty in that window, and reporting that as [Outcome.Ok] tells the caller
     * the artist genuinely has nothing else. The screen then has no reason to draw anything and no
     * reason to ask again.
     */
    @Test
    public fun `a warming response with nothing in it is a failure, not an empty discography`(): Unit =
        runTest {
            v1.artistReleasesResponse = { ArtistReleasesDto(warming = true, sourceTotalCount = null) }

            val result = repository.refreshArtistDiscography(ArtistMbid(REAL_ARTIST_MBID))

            assertTrue("warming reported as success: " + result, result is Outcome.Failure)
            assertTrue(
                (result as Outcome.Failure).error is NeedlerError.CapabilityUnavailable,
            )
            assertTrue(albumDao.rows.isEmpty())
        }

    /** A partial answer is still an answer: the flag alone must not throw away resolved rows. */
    @Test
    public fun `a warming response that still carried releases is kept`(): Unit = runTest {
        v1.artistReleasesResponse = {
            ArtistReleasesDto(
                warming = true,
                albums = listOf(ReleaseItemDto(id = catalogueOnly, title = "Tweez", year = 1989)),
            )
        }

        val result = repository.refreshArtistDiscography(ArtistMbid(REAL_ARTIST_MBID))

        assertTrue(result is Outcome.Success)
        assertEquals("Tweez", albumDao.rows[catalogueOnly]?.title)
    }

    /**
     * An album cached by catalogue *search* is linked to its artist by the discography fetch.
     *
     * The second way a discography can come up empty with nothing to show for it. Catalogue search
     * names an artist in text and never by id, so `CatalogueMappers.album(SearchResultDto)` leaves
     * `artistMbid` null - and `AlbumDao.observeAlbumsByArtist` is `WHERE artist_mbid = :artistMbid`,
     * so such a row is invisible to every artist screen. "Insert only what the mirror does not
     * have" then skipped it for being known, which made the one fetch that could have supplied the
     * link the one that guaranteed it never would.
     */
    @Test
    public fun `a cached catalogue row with no artist id is linked to the artist that names it`(): Unit =
        runTest {
            artistDao.rows[ARTIST_MBID] = artistRow()
            albumDao.rows[catalogueOnly] = albumRow(
                mbid = catalogueOnly,
                title = "Tweez",
                artist = "",
                artistMbid = null,
                state = AlbumStateDb.NOT_OWNED,
                year = 1989,
            )
            v1.artistReleasesResponse = {
                ArtistReleasesDto(
                    albums = listOf(ReleaseItemDto(id = catalogueOnly, title = "Tweez", year = 1989)),
                )
            }

            repository.refreshArtistDiscography(ArtistMbid(ARTIST_MBID))

            assertEquals(ARTIST_MBID, albumDao.rows[catalogueOnly]?.artistMbid)
            // The name too, since the row had none to render and this response knows it.
            assertEquals("Slint", albumDao.rows[catalogueOnly]?.artistName)
            assertEquals(
                listOf("Tweez"),
                repository.observeArtistDiscography(ArtistMbid(ARTIST_MBID)).first().map { it.title },
            )
        }

    /**
     * The link is written only where there is none, and never onto an owned row.
     *
     * A release group already credited to another artist is a collaboration or a various-artists
     * record, and overwriting the credit would move it off that artist's screen onto this one. An
     * owned row's credit comes from Subsonic sync, which is authoritative about what the server
     * itself believes; two writers for one column is how they come to disagree.
     */
    @Test
    public fun `the artist link is never stolen from another artist or written onto an owned row`(): Unit =
        runTest {
            val otherArtist = "99999999-8888-7777-6666-555555555555"
            artistDao.rows[ARTIST_MBID] = artistRow()
            albumDao.rows[RG] = albumRow(artistMbid = null, state = AlbumStateDb.PINNED)
            albumDao.rows[catalogueOnly] = albumRow(
                mbid = catalogueOnly,
                artistMbid = otherArtist,
                state = AlbumStateDb.NOT_OWNED,
            )
            v1.artistReleasesResponse = {
                ArtistReleasesDto(
                    albums = listOf(
                        ReleaseItemDto(id = RG, title = "Spiderland", year = 1991),
                        ReleaseItemDto(id = catalogueOnly, title = "Tweez", year = 1989),
                    ),
                )
            }

            repository.refreshArtistDiscography(ArtistMbid(ARTIST_MBID))

            assertNull(albumDao.rows[RG]?.artistMbid)
            assertEquals(AlbumStateDb.PINNED, albumDao.rows[RG]?.state)
            assertEquals(otherArtist, albumDao.rows[catalogueOnly]?.artistMbid)
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

    /**
     * A DroppedNeedle name-derived artist id never reaches the catalogue.
     *
     * The device log was full of
     * `Rejected 400: Use the local library artist route for a DroppedNeedle artist ID` for
     * `8cfce742-445e-5e80-93a8-d8f924d56984` - a UUID v5, so name-derived, so permanently
     * unacceptable to a route that resolves against MusicBrainz. The fix is not to handle the
     * 400 more gracefully; it is not to make the call. `v1.calls` being empty is the assertion
     * that matters.
     */
    @Test
    public fun `a name-derived artist id never reaches the catalogue route`(): Unit = runTest {
        albumDao.rows[RG] = albumRow()

        val result = repository.refreshArtistDiscography(ArtistMbid(NAME_DERIVED_ARTIST))

        assertTrue(result is Outcome.Failure)
        val error = (result as Outcome.Failure).error
        assertTrue(error is NeedlerError.CapabilityUnavailable)
        // Not retryable, so a write-queue entry carrying it is dropped rather than replayed
        // against a route that will refuse it for ever.
        assertFalse(error.isRetryable)
        assertTrue("the catalogue was called anyway: " + v1.calls, v1.calls.isEmpty())
        // And the owned half is untouched, exactly as for any other discography failure.
        assertEquals(1, repository.observeOwnedAlbumsByArtist(ArtistMbid(ARTIST_MBID)).first().size)
    }

    @Test
    public fun `a real MusicBrainz artist id does reach the catalogue route`(): Unit = runTest {
        repository.refreshArtistDiscography(ArtistMbid(REAL_ARTIST_MBID))

        assertTrue(v1.calls.any { it.startsWith("artistReleases(") })
    }

    // ------------------------------------------------------------------ favourites

    /**
     * A track row carries its starred state.
     *
     * It did not, and the absence was invisible in the worst way: `Track.isFavourite` defaults
     * to false, so every track on album detail read as un-starred whatever the mirror held, and
     * a star the user had tapped came back off next time the screen opened. REQUIREMENTS.md
     * "Playlists" makes binary favourites the supported mechanism; a read path that drops the
     * answer cannot offer it honestly.
     */
    @Test
    public fun `album tracks carry whether each one is starred`(): Unit = runTest {
        albumDao.rows[RG] = albumRow()
        trackDao.rows["$RG/1/1"] = trackRow(track = 1)
        trackDao.rows["$RG/1/2"] = trackRow(track = 2)
        favouriteDao.rows["track/$RG/1/2"] = FavouriteEntity(
            entityType = FavouriteTypeDb.TRACK,
            entityId = "$RG/1/2",
            starredAt = NOW,
            releaseGroupMbid = RG,
            discNo = 1,
            trackNo = 2,
        )

        val tracks: List<Track> = repository.observeAlbumTracks(ReleaseGroupMbid(RG)).first()

        assertEquals(2, tracks.size)
        assertFalse(tracks.first { it.key.trackNumber == 1 }.isFavourite)
        assertTrue(tracks.first { it.key.trackNumber == 2 }.isFavourite)
    }

    /** A star on a track of a *different* album must not leak onto this one. */
    @Test
    public fun `a star on another album's track does not mark this one`(): Unit = runTest {
        albumDao.rows[RG] = albumRow()
        trackDao.rows["$RG/1/1"] = trackRow(track = 1)
        favouriteDao.rows["track/$catalogueOnly/1/1"] = FavouriteEntity(
            entityType = FavouriteTypeDb.TRACK,
            entityId = "$catalogueOnly/1/1",
            starredAt = NOW,
            releaseGroupMbid = catalogueOnly,
            discNo = 1,
            trackNo = 1,
        )

        val tracks: List<Track> = repository.observeAlbumTracks(ReleaseGroupMbid(RG)).first()

        assertFalse(tracks.single().isFavourite)
    }

    /** Disc and track are both part of the key, so track 1 of disc 2 is not track 1 of disc 1. */
    @Test
    public fun `a star is matched on disc as well as track number`(): Unit = runTest {
        albumDao.rows[RG] = albumRow()
        trackDao.rows["$RG/1/1"] = trackRow(disc = 1, track = 1)
        trackDao.rows["$RG/2/1"] = trackRow(disc = 2, track = 1)
        favouriteDao.rows["track/$RG/2/1"] = FavouriteEntity(
            entityType = FavouriteTypeDb.TRACK,
            entityId = "$RG/2/1",
            starredAt = NOW,
            releaseGroupMbid = RG,
            discNo = 2,
            trackNo = 1,
        )

        val tracks: List<Track> = repository.observeAlbumTracks(ReleaseGroupMbid(RG)).first()

        assertFalse(tracks.first { it.key.discNumber == 1 }.isFavourite)
        assertTrue(tracks.first { it.key.discNumber == 2 }.isFavourite)
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
