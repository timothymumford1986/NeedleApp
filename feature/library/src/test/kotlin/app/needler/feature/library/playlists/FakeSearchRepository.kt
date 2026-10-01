package app.needler.feature.library.playlists

import app.needler.core.domain.model.CatalogueSearchPage
import app.needler.core.domain.model.CatalogueSearchResults
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.SearchSuggestion
import app.needler.core.domain.model.Track
import app.needler.core.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A [SearchRepository] with only the local lane working.
 *
 * The add-tracks picker uses `searchLocal` and nothing else, and it must stay that
 * way: a track that exists only in the MusicBrainz catalogue has no file to play and
 * cannot be a playlist entry. So every catalogue member here throws, and a test that
 * starts using one will say so loudly rather than quietly passing.
 *
 * [queries] records what was asked, which is how the picker's cap and its
 * "no debounce on the local lane" behaviour are asserted.
 */
internal class FakeSearchRepository : SearchRepository {

    /** Tracks to answer with, keyed on the exact query. */
    val tracksByQuery = MutableStateFlow<Map<String, List<Track>>>(emptyMap())

    /** Every `searchLocal` call, as (query, limit) pairs. */
    val queries: MutableList<Pair<String, Int>> = mutableListOf()

    override fun searchLocal(query: String, limit: Int): Flow<LocalSearchResults> {
        queries += query to limit
        return tracksByQuery.map { byQuery ->
            LocalSearchResults(query = query, tracks = byQuery[query].orEmpty())
        }
    }

    override suspend fun searchCatalogue(
        query: String,
        limitArtists: Int,
        limitAlbums: Int,
    ): Outcome<CatalogueSearchResults> = error("the playlist picker must not reach the catalogue")

    override suspend fun searchCatalogueBucket(
        bucket: SearchBucket,
        query: String,
        limit: Int,
        offset: Int,
    ): Outcome<CatalogueSearchPage> = error("the playlist picker must not reach the catalogue")

    override suspend fun suggest(query: String, limit: Int): Outcome<List<SearchSuggestion>> =
        error("the playlist picker must not reach the catalogue")

    override fun observeRecentQueries(limit: Int): Flow<List<String>> =
        error("not used by the playlist screens")

    override suspend fun recordRecentQuery(query: String): Unit =
        error("not used by the playlist screens")

    override suspend fun clearRecentQueries(): Unit =
        error("not used by the playlist screens")
}
