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

    @Test
    public fun `clearing the recent list empties it`(): Unit = runTest {
        repository.recordRecentQuery("slint")
        repository.clearRecentQueries()

        assertTrue(repository.observeRecentQueries().first().isEmpty())
        assertFalse(v1.calls.contains("search(slint)"))
    }
}
