package app.needler.core.data.repository

import app.needler.core.data.fake.FakeAlbumDao
import app.needler.core.data.fake.FakeAppStateStore
import app.needler.core.data.fake.FakeArtistDao
import app.needler.core.data.fake.FakeTrackDao
import app.needler.core.data.fake.FakeV1Api
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.albumRow
import app.needler.core.data.fake.artistRow
import app.needler.core.data.fake.trackRow
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.CatalogueSearchResults
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.UnifiedSearchResults
import app.needler.core.domain.usecase.UnifiedSearchUseCase
import app.needler.core.network.v1.dto.SearchResponseDto
import app.needler.core.network.v1.dto.SearchResultDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two search lanes and the merge that joins them on the release-group MBID.
 *
 * The repository keeps the lanes apart on purpose - local FTS must answer on the first keystroke with
 * no network call, while the catalogue lane is debounced, slow and allowed to fail. The merge happens
 * above, and the rule it enforces is that **an album present locally takes the local record**: that
 * row knows the true state, track count, size and format, and the catalogue copy knows none of it.
 */
public class SearchMergeTest {

    private val albumDao = FakeAlbumDao()
    private val trackDao = FakeTrackDao()
    private val artistDao = FakeArtistDao()
    private val appState = FakeAppStateStore()
    private val v1 = FakeV1Api()

    private val repository = DefaultSearchRepository(
        albumDao = albumDao,
        trackDao = trackDao,
        artistDao = artistDao,
        appStateStore = appState,
        v1 = v1,
    )

    private val catalogueOnly = "11111111-2222-3333-4444-555555555555"

    // ------------------------------------------------------------- the local lane

    @Test
    public fun `local search reads the mirror and makes no network call`(): Unit = runTest {
        albumDao.rows[RG] = albumRow(title = "Spiderland")
        trackDao.rows["$RG/1/1"] = trackRow(title = "Spiderland reprise")
        artistDao.rows["artist-1"] = artistRow(mbid = "artist-1", name = "Spiderland Ensemble")

        val results: LocalSearchResults = repository.searchLocal("spiderland").first()

        assertEquals(1, results.albums.size)
        assertEquals(1, results.tracks.size)
        assertEquals(1, results.artists.size)
        assertTrue(v1.calls.isEmpty())
    }

    /**
     * The artist half of the bug that shipped.
     *
     * Searching "wonder" returned four catalogue strangers and not "Oh Wonder", three of whose albums
     * were on the device. The artist index is a prefix `LIKE` on `sort_name_normalised`, and
     * "oh wonder" does not begin with "wonder", so the mirror's own artist could not be found by the
     * one word anybody would type to find them. `album_fts` indexes the artist name beside the album
     * title, so the query that finds the albums now also finds their artist.
     */
    @Test
    public fun `local search finds an owned artist the query does not begin`(): Unit = runTest {
        albumDao.rows["rg-ultralife"] = albumRow(
            mbid = "rg-ultralife",
            title = "Ultralife",
            artist = "Oh Wonder",
            artistMbid = "ar-oh-wonder",
        )
        artistDao.rows["ar-oh-wonder"] = artistRow(mbid = "ar-oh-wonder", name = "Oh Wonder")

        val results: LocalSearchResults = repository.searchLocal("wonder").first()

        assertEquals("Oh Wonder", results.artists.single().name)
        // Still the fast lane: rule 1 means no network call at all.
        assertTrue(v1.calls.isEmpty())
    }

    /**
     * The other side of that guard: `album_fts` matches an album on its *title* too, and the artist of
     * an album that merely shares a word with the query has no business in the artist block.
     */
    @Test
    public fun `an album matched by title does not put its artist in the artist results`(): Unit =
        runTest {
            albumDao.rows["rg-wonderland"] = albumRow(
                mbid = "rg-wonderland",
                title = "Wonderland",
                artist = "Taylor Swift",
                artistMbid = "ar-swift",
            )
            artistDao.rows["ar-swift"] = artistRow(mbid = "ar-swift", name = "Taylor Swift")

            val results: LocalSearchResults = repository.searchLocal("wonder").first()

            assertEquals(1, results.albums.size)
            assertTrue("Taylor Swift does not answer \"wonder\"", results.artists.isEmpty())
        }

    @Test
    public fun `a blank query is an empty result rather than a whole-library scan`(): Unit = runTest {
        albumDao.rows[RG] = albumRow()

        val results: LocalSearchResults = repository.searchLocal("   ").first()

        assertTrue(results.isEmpty)
    }

    // --------------------------------------------------------- the catalogue lane

    @Test
    public fun `catalogue search maps hits onto the same Album type`(): Unit = runTest {
        v1.searchResponse = {
            SearchResponseDto(
                albums = listOf(
                    SearchResultDto(type = "album", title = "Tweez", musicbrainzId = catalogueOnly),
                ),
            )
        }

        val result: Outcome<CatalogueSearchResults> = repository.searchCatalogue("tweez")

        val albums: List<Album> = (result as Outcome.Success).value.albums
        assertEquals(1, albums.size)
        assertEquals(AlbumState.NotOwned, albums.single().state)
    }

    @Test
    public fun `a degraded upstream is reported without failing the search`(): Unit = runTest {
        v1.searchResponse = {
            SearchResponseDto(serviceStatus = mapOf("musicbrainz" to "degraded"))
        }

        val result = repository.searchCatalogue("anything") as Outcome.Success

        assertTrue(result.value.serviceStatus?.isDegraded == true)
    }

    @Test
    public fun `an offline catalogue search fails while the local lane is untouched`(): Unit = runTest {
        albumDao.rows[RG] = albumRow(title = "Spiderland")
        v1.failWith = { app.needler.core.network.NetworkError.Offline(java.io.IOException("offline")) }

        val catalogue: Outcome<CatalogueSearchResults> = repository.searchCatalogue("spiderland")
        val local: LocalSearchResults = repository.searchLocal("spiderland").first()

        assertTrue(catalogue is Outcome.Failure)
        assertEquals(1, local.albums.size)
    }

    @Test
    public fun `a one-character query never asks for suggestions, which need two`(): Unit = runTest {
        val result = repository.suggest("s") as Outcome.Success

        assertTrue(result.value.isEmpty())
        assertTrue(v1.calls.isEmpty())
    }

    @Test
    public fun `bucket paging reports hasMore from a full page, since there is no total`(): Unit =
        runTest {
            v1.searchBucketResponse = { bucket, offset ->
                app.needler.core.network.v1.dto.SearchBucketResponseDto(
                    bucket = bucket.wire,
                    offset = offset,
                    results = List(20) {
                        SearchResultDto(type = "album", title = "x$it", musicbrainzId = "mbid-$it")
                    },
                )
            }

            val page = (
                repository.searchCatalogueBucket(SearchBucket.ALBUMS, "x", limit = 20, offset = 0)
                    as Outcome.Success
                ).value

            assertTrue(page.hasMore)
            assertEquals(20, page.albums.size)
        }

    // -------------------------------------------------------------------- merging

    @Test
    public fun `an album in both lanes keeps the local record and drops the catalogue copy`() {
        val local = LocalSearchResults(
            query = "spiderland",
            albums = listOf(
                app.needler.core.data.mapper.EntityMappers.album(albumRow(state = AlbumStateDb.PINNED)),
            ),
        )
        val catalogue = CatalogueSearchResults(
            query = "spiderland",
            albums = listOf(
                app.needler.core.data.mapper.CatalogueMappers.album(
                    SearchResultDto(type = "album", title = "Spiderland", musicbrainzId = RG),
                )!!,
            ),
        )

        val merged: UnifiedSearchResults = UnifiedSearchUseCase.merge(
            query = "spiderland",
            local = local,
            catalogue = catalogue,
            lane = CatalogueLaneState.Ready(),
        )

        assertEquals(1, merged.albums.size)
        // The local row knows it is on the device. The catalogue copy would have said NotOwned.
        assertTrue(merged.albums.single().state is AlbumState.Pinned)
    }

    @Test
    public fun `an album only the catalogue knows is appended as un-owned`() {
        val local = LocalSearchResults(
            query = "s",
            albums = listOf(app.needler.core.data.mapper.EntityMappers.album(albumRow())),
        )
        val catalogue = CatalogueSearchResults(
            query = "s",
            albums = listOf(
                app.needler.core.data.mapper.CatalogueMappers.album(
                    SearchResultDto(type = "album", title = "Tweez", musicbrainzId = catalogueOnly),
                )!!,
            ),
        )

        val merged: UnifiedSearchResults = UnifiedSearchUseCase.merge(
            query = "s",
            local = local,
            catalogue = catalogue,
            lane = CatalogueLaneState.Ready(),
        )

        assertEquals(2, merged.albums.size)
        assertEquals(RG, merged.albums.first().releaseGroupMbid.value)
        assertEquals(AlbumState.NotOwned, merged.albums.last().state)
    }

    @Test
    public fun `merging before the catalogue answers shows library results alone`() {
        val local = LocalSearchResults(
            query = "s",
            albums = listOf(app.needler.core.data.mapper.EntityMappers.album(albumRow())),
        )

        val merged: UnifiedSearchResults = UnifiedSearchUseCase.merge(
            query = "s",
            local = local,
            catalogue = null,
            lane = CatalogueLaneState.Loading,
        )

        assertEquals(1, merged.albums.size)
        assertEquals(CatalogueLaneState.Loading, merged.catalogue)
    }

    /**
     * The whole of the "wonder" bug, end to end through the real mappers.
     *
     * On the device this returned "Jr. Wonder", "Wonder", "wonder", "wonder" - four rows, two of them
     * the same string twice, all subtitled "Not in your library yet" - and no "Oh Wonder" at all. All
     * three defects are asserted here at once: the owned artist is present, it leads, and the
     * byte-identical catalogue names have collapsed to one row.
     */
    @Test
    public fun `an owned artist leads and byte-identical catalogue names collapse`() {
        val local = LocalSearchResults(
            query = "wonder",
            artists = listOf(
                app.needler.core.data.mapper.EntityMappers.artist(
                    artistRow(mbid = "ar-oh-wonder", name = "Oh Wonder"),
                ),
            ),
        )
        val catalogue = CatalogueSearchResults(
            query = "wonder",
            // MusicBrainz's own order, which is what put the worst match first on the device.
            artists = listOfNotNull(
                catalogueArtist("ar-jr-wonder", "Jr. Wonder"),
                catalogueArtist("ar-wonder-1", "Wonder"),
                catalogueArtist("ar-wonder-2", "wonder"),
                catalogueArtist("ar-wonder-3", "wonder"),
            ),
        )

        val merged: UnifiedSearchResults = UnifiedSearchUseCase.merge(
            query = "wonder",
            local = local,
            catalogue = catalogue,
            lane = CatalogueLaneState.Ready(),
        )

        assertEquals(
            listOf("Oh Wonder", "Wonder", "Jr. Wonder"),
            merged.artists.map { it.name },
        )
        // The leading row is the mirror's, so it knows how much of them is on the server.
        assertEquals(2, merged.artists.first().ownedAlbumCount)
    }

    /**
     * The Dido duplication, still on the device at v0.0.13, reproduced where it really comes from:
     * **one lane**.
     *
     * The merge's second rule was written against the wrong half. `UnifiedSearchMergeTest` puts the
     * four MusicBrainz copies in [CatalogueSearchResults], and against that fixture the rule works -
     * which is why it passed while the device went on drawing eight rows for four albums. The device
     * gets both copies from the **mirror**.
     *
     * How the mirror comes to hold two rows for one record: opening Dido's artist screen pages her
     * discography through `DefaultLibraryRepository.refreshArtistDiscographyPage`, which writes every
     * release group the mirror does not already hold as an un-owned row (`CatalogueMappers.albumEntity`,
     * `state = NOT_OWNED`, `in_library = 0`) so artist detail still renders on a train. Because the two
     * sides disagree about the ids - the evidence `UnifiedSearchUseCase.mergeAlbums` records - "does
     * not already hold" is true of every record the user owns, so all four are written a second time
     * under MusicBrainz's ids. `album_fts` then indexes the artist name on all eight rows and
     * [app.needler.core.data.local.dao.AlbumDao.observeAlbumSearch] filters on nothing, so the local
     * lane on its own returns the library block and the to-pull block together.
     *
     * The catalogue lane is deliberately absent from this test. With no network at all the screen still
     * drew both blocks, which is the shortest proof that the second copy was never the catalogue's.
     */
    @Test
    public fun `a catalogue row the mirror cached is not offered for pull beside the album you own`():
        Unit = runTest {
        // What the library sync wrote, under the ids DroppedNeedle's import matched.
        albumDao.rows["rg-still"] = didoRow("rg-still", "Still on My Mind", 2019)
        albumDao.rows["rg-life"] = didoRow("rg-life", "Life for Rent", 2003)
        albumDao.rows["rg-angel"] = didoRow("rg-angel", "No Angel", 1999, AlbumStateDb.PINNED)
        albumDao.rows["rg-safe"] = didoRow("rg-safe", "Safe Trip Home", 2008)
        // What paging the artist screen's discography wrote, under the ids MusicBrainz returned.
        albumDao.rows["mb-still"] = didoRow("mb-still", "Still on My Mind", 2019, AlbumStateDb.NOT_OWNED)
        albumDao.rows["mb-life"] = didoRow("mb-life", "Life for Rent", 2003, AlbumStateDb.NOT_OWNED)
        albumDao.rows["mb-angel"] = didoRow("mb-angel", "No Angel", 1999, AlbumStateDb.NOT_OWNED)
        albumDao.rows["mb-safe"] = didoRow("mb-safe", "Safe Trip Home", 2008, AlbumStateDb.NOT_OWNED)
        // An unrelated record that merely shares the query's spelling. The to-pull block is for this.
        albumDao.rows["mb-aria"] = albumRow(
            mbid = "mb-aria",
            title = "Dido",
            artist = "Aria",
            artistMbid = "ar-aria",
            state = AlbumStateDb.NOT_OWNED,
            year = 2000,
        )

        val local: LocalSearchResults = repository.searchLocal("dido").first()
        val merged: UnifiedSearchResults = UnifiedSearchUseCase.merge(
            query = "dido",
            local = local,
            catalogue = null,
            lane = CatalogueLaneState.Unavailable(app.needler.core.domain.model.NeedlerError.Offline()),
        )

        // Four albums the server holds, and one it does not. The order is the DAO's own,
        // `in_library DESC, title_normalised ASC`, which the merge must not disturb.
        assertEquals(
            listOf("Life for Rent", "No Angel", "Safe Trip Home", "Still on My Mind", "Dido"),
            merged.albums.map { it.title },
        )
        // The surviving copy of each is the row that knows where the record is.
        assertEquals(
            listOf("rg-life", "rg-angel", "rg-safe", "rg-still", "mb-aria"),
            merged.albums.map { it.releaseGroupMbid.value },
        )
        // And the un-owned half holds only the album the server genuinely does not have.
        assertEquals(
            listOf("Aria"),
            merged.albums.filter { it.state == AlbumState.NotOwned }.map { it.artistName },
        )
    }

    /** One Dido row, owned unless told otherwise, as the mirror stores it. */
    private fun didoRow(
        mbid: String,
        title: String,
        year: Int,
        state: AlbumStateDb = AlbumStateDb.OWNED,
    ) = albumRow(
        mbid = mbid,
        title = title,
        artist = "Dido",
        artistMbid = "ar-dido",
        state = state,
        year = year,
    )

    @Test
    public fun `the merge is idempotent for a duplicate MBID within the catalogue half`() {
        val duplicate = app.needler.core.data.mapper.CatalogueMappers.album(
            SearchResultDto(type = "album", title = "Spiderland", musicbrainzId = RG),
        )!!
        val local = LocalSearchResults(
            query = "s",
            albums = listOf(app.needler.core.data.mapper.EntityMappers.album(albumRow())),
        )

        val merged: UnifiedSearchResults = UnifiedSearchUseCase.merge(
            query = "s",
            local = local,
            catalogue = CatalogueSearchResults(query = "s", albums = listOf(duplicate, duplicate)),
            lane = CatalogueLaneState.Ready(),
        )

        assertEquals(1, merged.albums.size)
    }

    // ------------------------------------------------------------ recent queries

    @Test
    public fun `recent queries are kept most-recent-first without duplicates`(): Unit = runTest {
        repository.recordRecentQuery("slint")
        repository.recordRecentQuery("spiderland")
        repository.recordRecentQuery("Slint")

        assertEquals(listOf("Slint", "spiderland"), repository.observeRecentQueries().first())
    }

    /** One catalogue artist hit, through the mapper the repository itself uses. */
    private fun catalogueArtist(mbid: String, name: String) =
        app.needler.core.data.mapper.CatalogueMappers.artist(
            SearchResultDto(type = "artist", title = name, musicbrainzId = mbid),
        )

    @Test
    public fun `clearing the recent list empties it`(): Unit = runTest {
        repository.recordRecentQuery("slint")
        repository.clearRecentQueries()

        assertTrue(repository.observeRecentQueries().first().isEmpty())
        assertFalse(v1.calls.contains("search(slint)"))
    }
}
