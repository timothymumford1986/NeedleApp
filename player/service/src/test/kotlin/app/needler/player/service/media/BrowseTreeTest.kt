package app.needler.player.service.media

import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.Favourites
import app.needler.core.domain.model.Genre
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackListKind
import app.needler.core.domain.repository.FavouriteRepository
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PlaylistRepository
import app.needler.core.domain.repository.SearchRepository
import app.needler.core.domain.usecase.UnifiedSearchUseCase
import app.needler.player.service.Fixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The tree Android Auto walks.
 *
 * Three rules carry the requirement and each has a test of its own here, because none of them fails visibly
 * in a car - they fail as an empty list, a repeated first page, or a row that cannot play:
 *
 *  * the tree is the one REQUIREMENTS.md "Android Auto" names, in the order it names it;
 *  * `page` and `pageSize` are honoured, since a 5,000-album library cannot be handed over in one list;
 *  * nothing reaches a car that the server does not own, and nothing here touches the network - which means
 *    neither `observeArtistDiscography` nor `searchCatalogue`, the two places the lanes meet.
 */
class BrowseTreeTest {

    private val library: LibraryRepository = mockk(relaxed = true)
    private val playlists: PlaylistRepository = mockk(relaxed = true)
    private val favourites: FavouriteRepository = mockk(relaxed = true)
    private val pins: PinRepository = mockk(relaxed = true)
    private val search: SearchRepository = mockk(relaxed = true)

    private val tree = BrowseTree(
        library = library,
        playlists = playlists,
        favourites = favourites,
        pins = pins,
        search = search,
    )

    /**
     * Every flow the tree can reach is stubbed empty up front.
     *
     * A relaxed mock hands back a flow that never emits, and `first()` on one of those fails with a message
     * about an empty flow rather than about the branch under test. Each test then overrides only the one list
     * it is about.
     */
    @Before
    fun stubEverythingEmpty() {
        every { library.observeAlbumList(any(), any(), any()) } returns flowOf(emptyList())
        every { library.observeTracks(any(), any(), any()) } returns flowOf(emptyList())
        every { library.observeArtists() } returns flowOf(emptyList())
        every { library.observeArtist(any()) } returns flowOf<Artist?>(null)
        every { library.observeOwnedAlbumsByArtist(any()) } returns flowOf(emptyList())
        every { library.observeAlbumTracks(any()) } returns flowOf(emptyList())
        every { library.observeGenres() } returns flowOf(emptyList())
        every { library.observeTracksByGenre(any(), any(), any()) } returns flowOf(emptyList())
        every { playlists.observePlaylists() } returns flowOf(emptyList())
        every { playlists.observePlaylist(any()) } returns flowOf<Playlist?>(null)
        every { playlists.observePlaylistEntries(any()) } returns flowOf(emptyList())
        every { favourites.observeFavourites() } returns flowOf(Favourites())
        every { pins.observeDownloadedAlbums() } returns flowOf(emptyList())
        every { search.searchLocal(any(), any()) } returns flowOf(LocalSearchResults(query = ""))
    }

    @Test
    fun `the root is the five nodes the requirement names, in that order`() = runTest {
        val rows = tree.children(MediaId.BROWSE_ROOT, page = 0, pageSize = 20)

        assertEquals(
            listOf(
                BrowseTree.LABEL_LIBRARY,
                BrowseTree.LABEL_RECENTLY_ADDED,
                BrowseTree.LABEL_PLAYLISTS,
                BrowseTree.LABEL_FAVOURITES,
                BrowseTree.LABEL_ON_DEVICE,
            ),
            rows.map { it.title },
        )
        assertTrue(rows.all { it.kind.isBrowsable && !it.kind.isPlayable })
    }

    @Test
    fun `library holds albums, artists and songs, plus the genres the mirror already serves`() = runTest {
        val rows = tree.children(MediaId.BROWSE_LIBRARY, page = 0, pageSize = 20)

        assertEquals(
            listOf(
                MediaId.BROWSE_ALBUMS,
                MediaId.BROWSE_ARTISTS,
                MediaId.BROWSE_SONGS,
                MediaId.BROWSE_GENRES,
            ),
            rows.map { it.mediaId },
        )
    }

    /**
     * The page becomes an SQL window where the repository takes one, which is the only version of paging that
     * helps: slicing a five-thousand-row query in memory would have read the five thousand rows first.
     */
    @Test
    fun `a paged node asks the mirror for that page and no more`() = runTest {
        every {
            library.observeAlbumList(AlbumListKind.ALPHABETICAL_BY_NAME, 20, 40)
        } returns flowOf(listOf(Fixtures.album(title = "Page three")))

        val rows = tree.children(MediaId.BROWSE_ALBUMS, page = 2, pageSize = 20)

        assertEquals(listOf("Page three"), rows.map { it.title })
        verify { library.observeAlbumList(AlbumListKind.ALPHABETICAL_BY_NAME, 20, 40) }
    }

    @Test
    fun `songs is the library's own song ordering, paged`() = runTest {
        every {
            library.observeTracks(TrackListKind.ALPHABETICAL_BY_TITLE, 5, 5)
        } returns flowOf(listOf(Fixtures.track(number = 2, title = "Second")))

        val rows = tree.children(MediaId.BROWSE_SONGS, page = 1, pageSize = 5)

        assertEquals(listOf("Second"), rows.map { it.title })
        assertEquals(BrowseRowKind.TRACK, rows.single().kind)
        assertEquals(Fixtures.key(track = 2).canonicalString, rows.single().mediaId)
    }

    /** A list the repository cannot window is sliced here, and the slice has to be the right one. */
    @Test
    fun `a list without repository paging is sliced by page`() = runTest {
        every { library.observeArtists() } returns flowOf(
            (1..5).map { Fixtures.artist(mbid = artistMbid(it), name = "Artist " + it) },
        )

        val page = tree.children(MediaId.BROWSE_ARTISTS, page = 1, pageSize = 2)

        assertEquals(listOf("Artist 3", "Artist 4"), page.map { it.title })
    }

    @Test
    fun `a page past the end is empty rather than an error`() = runTest {
        every { library.observeArtists() } returns flowOf(listOf(Fixtures.artist()))

        assertEquals(emptyList<BrowseRow>(), tree.children(MediaId.BROWSE_ARTISTS, page = 9, pageSize = 20))
    }

    /**
     * A browser that does not page sends page 0 with `Int.MAX_VALUE` as the size. In `Int` arithmetic that
     * multiplication overflows to a negative offset and throws out of the list instead of returning it.
     */
    @Test
    fun `no paging at all returns the whole list`() = runTest {
        every { library.observeArtists() } returns flowOf(
            (1..3).map { Fixtures.artist(mbid = artistMbid(it), name = "Artist " + it) },
        )

        val rows = tree.children(MediaId.BROWSE_ARTISTS, page = 0, pageSize = Int.MAX_VALUE)

        assertEquals(3, rows.size)
    }

    /**
     * REQUIREMENTS.md "Android Auto" restricts Auto to owned music "since pulling while driving makes no
     * sense", and the mirror holds catalogue-only albums as well - so the artist node reads the owned list and
     * must never reach for the discography, which is a network call in a car.
     */
    @Test
    fun `an artist lists owned albums and never the catalogue lane`() = runTest {
        val mbid = ArtistMbid(Fixtures.ARTIST_A)
        every { library.observeOwnedAlbumsByArtist(mbid) } returns flowOf(
            listOf(Fixtures.album(title = "Owned")),
        )

        val rows = tree.children(MediaId.forArtist(mbid), page = 0, pageSize = 20)

        assertEquals(listOf("Owned"), rows.map { it.title })
        verify(exactly = 0) { library.observeArtistDiscography(any()) }
        coVerify(exactly = 0) { library.refreshArtistDiscography(any()) }
    }

    /** An artist known only from a discography lookup owns nothing, so in a car it is an empty folder. */
    @Test
    fun `artists with no owned music are dropped`() = runTest {
        every { library.observeArtists() } returns flowOf(
            listOf(
                Fixtures.artist(mbid = artistMbid(1), name = "Owned", ownedAlbumCount = 2),
                Fixtures.artist(mbid = artistMbid(2), name = "Catalogue only", ownedAlbumCount = 0),
            ),
        )

        val rows = tree.children(MediaId.BROWSE_ARTISTS, page = 0, pageSize = 20)

        assertEquals(listOf("Owned"), rows.map { it.title })
    }

    @Test
    fun `an un-owned album never appears in a list`() = runTest {
        every { library.observeAlbumList(any(), any(), any()) } returns flowOf(
            listOf(
                Fixtures.album(
                    mbid = Fixtures.ALBUM_A,
                    title = "On the server",
                    state = AlbumState.Owned,
                ),
                Fixtures.album(
                    mbid = Fixtures.ALBUM_B,
                    title = "Only in the catalogue",
                    state = AlbumState.NotOwned,
                ),
            ),
        )

        val rows = tree.children(MediaId.BROWSE_ALBUMS, page = 0, pageSize = 20)

        assertEquals(listOf("On the server"), rows.map { it.title })
    }

    @Test
    fun `an album lists its tracks, and its rows are playable songs`() = runTest {
        val mbid = ReleaseGroupMbid(Fixtures.ALBUM_A)
        every { library.observeAlbumTracks(mbid) } returns flowOf(
            listOf(Fixtures.track(number = 1, title = "One"), Fixtures.track(number = 2, title = "Two")),
        )

        val rows = tree.children(MediaId.forAlbum(mbid), page = 0, pageSize = 20)

        assertEquals(listOf("One", "Two"), rows.map { it.title })
        assertTrue(rows.all { it.kind == BrowseRowKind.TRACK && it.kind.isPlayable })
    }

    @Test
    fun `playlists are rows that open on the playlist and play it whole`() = runTest {
        every { playlists.observePlaylists() } returns flowOf(
            listOf(Fixtures.playlist(id = "7", name = "Long drive")),
        )

        val row = tree.children(MediaId.BROWSE_PLAYLISTS, page = 0, pageSize = 20).single()

        assertEquals("Long drive", row.title)
        assertEquals(MediaId.forPlaylist(PlaylistId("7")), row.mediaId)
        assertTrue(row.kind.isBrowsable && row.kind.isPlayable)
    }

    @Test
    fun `a playlist lists its entries in playlist order`() = runTest {
        val id = PlaylistId("7")
        every { playlists.observePlaylistEntries(id) } returns flowOf(
            listOf(
                Fixtures.entry(1, Fixtures.track(number = 4, title = "First in the playlist")),
                Fixtures.entry(2, Fixtures.track(number = 1, title = "Second in the playlist")),
            ),
        )

        val rows = tree.children(MediaId.forPlaylist(id), page = 0, pageSize = 20)

        assertEquals(listOf("First in the playlist", "Second in the playlist"), rows.map { it.title })
    }

    /**
     * `observeDownloadedAlbums` orders by size, because the screen it exists for is Storage. A browse list
     * that rearranged itself as downloads grew would be one nobody could learn.
     */
    @Test
    fun `on device lists downloads alphabetically`() = runTest {
        every { pins.observeDownloadedAlbums() } returns flowOf(
            listOf(
                Fixtures.download(mbid = Fixtures.ALBUM_A, title = "Zenith", sizeBytes = 900_000_000L),
                Fixtures.download(mbid = Fixtures.ALBUM_B, title = "Amber", sizeBytes = 100_000_000L),
            ),
        )

        val rows = tree.children(MediaId.BROWSE_ON_DEVICE, page = 0, pageSize = 20)

        assertEquals(listOf("Amber", "Zenith"), rows.map { it.title })
        assertEquals(MediaId.forAlbum(ReleaseGroupMbid(Fixtures.ALBUM_B)), rows.first().mediaId)
    }

    @Test
    fun `favourites are the starred albums, artists and songs in one flat list`() = runTest {
        every { favourites.observeFavourites() } returns flowOf(
            Favourites(
                albums = listOf(Fixtures.album(title = "Starred album")),
                artists = listOf(Fixtures.artist(name = "Starred artist")),
                tracks = listOf(Fixtures.track(title = "Starred song")),
            ),
        )

        val rows = tree.children(MediaId.BROWSE_FAVOURITES, page = 0, pageSize = 20)

        assertEquals(listOf("Starred album", "Starred artist", "Starred song"), rows.map { it.title })
        assertEquals(
            listOf(BrowseRowKind.ALBUM, BrowseRowKind.ARTIST, BrowseRowKind.TRACK),
            rows.map { it.kind },
        )
    }

    @Test
    fun `genres are listed by name and open on the mirror's own genre query`() = runTest {
        every { library.observeGenres() } returns flowOf(listOf(Genre(name = "Hip-Hop/Rap")))
        every { library.observeTracksByGenre("Hip-Hop/Rap", 20, 0) } returns flowOf(
            listOf(Fixtures.track(title = "A song in that genre")),
        )

        val genres = tree.children(MediaId.BROWSE_GENRES, page = 0, pageSize = 20)
        val tracks = tree.children(genres.single().mediaId, page = 0, pageSize = 20)

        assertEquals("Hip-Hop/Rap", genres.single().title)
        assertEquals(listOf("A song in that genre"), tracks.map { it.title })
    }

    @Test
    fun `an unknown parent is empty, not an error`() = runTest {
        assertEquals(emptyList<BrowseRow>(), tree.children("needler:pulls", page = 0, pageSize = 20))
        assertEquals(emptyList<BrowseRow>(), tree.children("not-an-id-at-all", page = 0, pageSize = 20))
    }

    /**
     * REQUIREMENTS.md "Android Auto": voice search "maps to the same unified search, restricted to owned
     * music, since pulling while driving makes no sense". So: the local FTS lane, never the catalogue one.
     */
    @Test
    fun `search reads the mirror and never the catalogue lane`() = runTest {
        every { search.searchLocal("rain", any()) } returns flowOf(
            LocalSearchResults(
                query = "rain",
                artists = listOf(Fixtures.artist(name = "An artist match")),
                albums = listOf(Fixtures.album(title = "An album match")),
                tracks = listOf(Fixtures.track(title = "A song match")),
            ),
        )

        val rows = tree.searchRows("rain", page = 0, pageSize = 20)

        assertEquals(listOf("An album match", "An artist match", "A song match"), rows.map { it.title })
        coVerify(exactly = 0) { search.searchCatalogue(any(), any(), any()) }
        coVerify(exactly = 0) { search.searchCatalogueBucket(any(), any(), any(), any()) }
    }

    @Test
    fun `search drops albums the server does not own`() = runTest {
        every { search.searchLocal(any(), any()) } returns flowOf(
            LocalSearchResults(
                query = "rain",
                albums = listOf(
                    Fixtures.album(
                        mbid = Fixtures.ALBUM_A,
                        title = "On the server",
                        state = AlbumState.Owned,
                    ),
                    Fixtures.album(
                        mbid = Fixtures.ALBUM_B,
                        title = "Only in the catalogue",
                        state = AlbumState.NotOwned,
                    ),
                ),
            ),
        )

        val rows = tree.searchRows("rain", page = 0, pageSize = 20)

        assertEquals(listOf("On the server"), rows.map { it.title })
    }

    /**
     * One record the mirror holds twice reaches the car once.
     *
     * `SearchRepository.searchLocal`'s contract says this lane can return one record twice and names this
     * tree as the caller that would show it: `refreshArtistDiscographyPage` caches an artist's MusicBrainz
     * discography as un-owned rows keyed on ids the mirror does not already hold, so opening the artist
     * screen for an artist you own writes a second row for every record of theirs you own - same title, same
     * artist, different release-group MBID, un-owned.
     *
     * The surviving row is the owned one, which is the half of this that matters in a car: its media id is
     * the id with files behind it, so the row plays. A twin that won would open an empty list.
     */
    @Test
    fun `an album the mirror holds twice is one row in the car`() = runTest {
        every { search.searchLocal(any(), any()) } returns flowOf(duplicatedInTheMirror)

        val rows = tree.searchRows("dido", page = 0, pageSize = 20)

        assertEquals(listOf("Safe Trip Home"), rows.map { it.title })
        assertEquals(MediaId.forAlbum(ReleaseGroupMbid(Fixtures.ALBUM_A)), rows.single().mediaId)
        assertEquals(1, tree.searchRowCount("dido"))
    }

    /** And a spoken "play ..." lands on the same row, rather than on the copy with no files. */
    @Test
    fun `a spoken query cannot reach the copy that is not owned`() = runTest {
        every { search.searchLocal(any(), any()) } returns flowOf(duplicatedInTheMirror)
        every { library.observeAlbumTracks(ReleaseGroupMbid(Fixtures.ALBUM_A)) } returns
            flowOf(listOf(Fixtures.track()))

        assertEquals(listOf("Track 1"), tree.tracksForQuery("dido").map { it.title })
    }

    /**
     * And running the merge the other screens run would make no difference, which is why this tree does not.
     *
     * `BrowseTree.searchRows` records the decision; this is the evidence for it. Every row
     * [UnifiedSearchUseCase.mergeAlbums] drops from a local-only call is a row the owned-only filter drops
     * anyway, so calling it before the filter would add a dependency and change nothing. Asserted rather than
     * argued, so that a change to either side has to answer for the difference.
     */
    @Test
    fun `the unified merge would collapse no row this tree does not`() = runTest {
        val merged: LocalSearchResults = duplicatedInTheMirror.copy(
            albums = UnifiedSearchUseCase.mergeAlbums(
                local = duplicatedInTheMirror.albums,
                catalogue = emptyList(),
            ),
        )
        every { search.searchLocal(any(), any()) } returns flowOf(duplicatedInTheMirror)
        val withoutTheMerge = tree.searchRows("dido", page = 0, pageSize = 20)

        every { search.searchLocal(any(), any()) } returns flowOf(merged)
        val withTheMerge = BrowseTree(
            library = library,
            playlists = playlists,
            favourites = favourites,
            pins = pins,
            search = search,
        ).searchRows("dido", page = 0, pageSize = 20)

        assertEquals(withTheMerge, withoutTheMerge)
    }

    /**
     * One record, two mirror rows: the shape the device proved, with the un-owned copy second.
     *
     * The ids differ because that is the whole defect - the two lanes disagree about the release-group
     * MBID - and the title and artist match because that is what makes them one record. The artist is
     * carried too, since `album_fts` matched both rows on it.
     */
    private val duplicatedInTheMirror: LocalSearchResults = LocalSearchResults(
        query = "dido",
        albums = listOf(
            Fixtures.album(
                mbid = Fixtures.ALBUM_A,
                title = "Safe Trip Home",
                artistName = "Dido",
                state = AlbumState.Owned,
            ),
            Fixtures.album(
                mbid = Fixtures.ALBUM_B,
                title = "Safe Trip Home",
                artistName = "Dido",
                state = AlbumState.NotOwned,
            ),
        ),
    )

    /**
     * A browser asks for the count and then for the pages of the same query. Asking the mirror again each time
     * risks a list whose length contradicts the count the car was just given.
     */
    @Test
    fun `the count and the page that follows it are one query`() = runTest {
        every { search.searchLocal("rain", any()) } returns flowOf(
            LocalSearchResults(query = "rain", tracks = listOf(Fixtures.track(), Fixtures.track(number = 2))),
        )

        assertEquals(2, tree.searchRowCount("rain"))
        assertEquals(1, tree.searchRows("rain", page = 1, pageSize = 1).size)

        verify(exactly = 1) { search.searchLocal("rain", any()) }
    }

    @Test
    fun `an empty query finds nothing and asks nothing`() = runTest {
        assertEquals(0, tree.searchRowCount("   "))

        verify(exactly = 0) { search.searchLocal(any(), any()) }
    }

    /**
     * The other half of a browsable node being playable: selecting an album in Auto has to expand to the whole
     * record in order, or the crate gets one row that cannot play.
     */
    @Test
    fun `playing an album expands to the whole record in order`() = runTest {
        val mbid = ReleaseGroupMbid(Fixtures.ALBUM_A)
        val tracks = listOf(
            Fixtures.track(number = 1, title = "One"),
            Fixtures.track(number = 2, title = "Two"),
            Fixtures.track(number = 3, title = "Three"),
        )
        every { library.observeAlbumTracks(mbid) } returns flowOf(tracks)

        assertEquals(tracks, tree.tracksFor(MediaId.forAlbum(mbid)))
    }

    @Test
    fun `playing a playlist expands to its entries in order`() = runTest {
        val id = PlaylistId("7")
        val first = Fixtures.track(number = 9, title = "First")
        val second = Fixtures.track(number = 2, title = "Second")
        every { playlists.observePlaylistEntries(id) } returns flowOf(
            listOf(Fixtures.entry(1, first), Fixtures.entry(2, second)),
        )

        assertEquals(listOf(first, second), tree.tracksFor(MediaId.forPlaylist(id)))
    }

    @Test
    fun `playing one song is one song`() = runTest {
        val track = Fixtures.track()
        coEvery { library.getTrack(track.key) } returns track

        assertEquals(listOf(track), tree.tracksFor(MediaId.forTrack(track.key)))
    }

    /**
     * A folder is browsable and not playable, and "Songs" is the reason: one mistaken tap at a junction must
     * not put a whole library in the crate.
     */
    @Test
    fun `playing a folder enqueues nothing`() = runTest {
        for (folder in listOf(
            MediaId.BROWSE_ROOT,
            MediaId.BROWSE_LIBRARY,
            MediaId.BROWSE_ALBUMS,
            MediaId.BROWSE_SONGS,
            MediaId.BROWSE_FAVOURITES,
            MediaId.BROWSE_ON_DEVICE,
        )) {
            assertEquals(folder, emptyList<Track>(), tree.tracksFor(folder))
        }
    }

    /** A spoken "play ..." must play the row the same search would have shown first. */
    @Test
    fun `a spoken query plays the first row that search would have shown`() = runTest {
        val mbid = ReleaseGroupMbid(Fixtures.ALBUM_B)
        val albumTracks = listOf(Fixtures.track(album = Fixtures.ALBUM_B, title = "First on the record"))
        every { search.searchLocal("blue nile", any()) } returns flowOf(
            LocalSearchResults(
                query = "blue nile",
                albums = listOf(Fixtures.album(mbid = Fixtures.ALBUM_B, title = "Hats")),
                tracks = listOf(Fixtures.track(title = "Some other song")),
            ),
        )
        every { library.observeAlbumTracks(mbid) } returns flowOf(albumTracks)

        assertEquals(albumTracks, tree.tracksForQuery("blue nile"))
    }

    @Test
    fun `a spoken query that matches nothing plays nothing`() = runTest {
        assertEquals(emptyList<Track>(), tree.tracksForQuery("nothing like this"))
    }

    /** `onGetItem` is answered for a browse node as well as for a track. */
    @Test
    fun `one row can be fetched by its id`() = runTest {
        val mbid = ReleaseGroupMbid(Fixtures.ALBUM_A)
        coEvery { library.getAlbum(mbid) } returns Fixtures.album(title = "An Album")

        assertEquals("An Album", tree.row(MediaId.forAlbum(mbid))?.title)
        assertEquals(BrowseTree.LABEL_ON_DEVICE, tree.row(MediaId.BROWSE_ON_DEVICE)?.title)
        assertEquals(BrowseTree.LABEL_ROOT, tree.row(MediaId.BROWSE_ROOT)?.title)
        assertNull(tree.row("needler:pulls"))
    }

    private fun artistMbid(n: Int): String = "0000000" + n + "-0000-0000-0000-00000000000" + n
}
