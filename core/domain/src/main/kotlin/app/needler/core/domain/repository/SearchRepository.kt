package app.needler.core.domain.repository

import app.needler.core.domain.model.CatalogueSearchPage
import app.needler.core.domain.model.CatalogueSearchResults
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.SearchSuggestion
import kotlinx.coroutines.flow.Flow

/**
 * The two search lanes, kept separate here and merged by
 * [app.needler.core.domain.usecase.UnifiedSearchUseCase].
 *
 * They are deliberately not merged in the repository: local FTS must emit on the first keystroke with
 * no network call, while the catalogue lane is debounced, slow, and allowed to fail. Merging at this
 * level would force the fast lane to wait for the slow one.
 */
public interface SearchRepository {

    /**
     * Local FTS over the mirror (`album_fts`, `track_fts`). Emits immediately, works offline, and must
     * return in under 50 ms for a 10,000-album library.
     */
    public fun searchLocal(query: String, limit: Int = 50): Flow<LocalSearchResults>

    /**
     * Catalogue search via `GET /api/v1/search`.
     *
     * Reaches MusicBrainz through the server and is slow relative to local search. Fails with
     * [app.needler.core.domain.model.NeedlerError.Offline] when unreachable and
     * [app.needler.core.domain.model.NeedlerError.SessionExpired] on a degraded session; in both cases
     * the caller keeps showing library results and says plainly that catalogue search needs a
     * connection.
     */
    public suspend fun searchCatalogue(
        query: String,
        limitArtists: Int = 10,
        limitAlbums: Int = 20,
    ): Outcome<CatalogueSearchResults>

    /** One page of a single bucket, via `GET /api/v1/search/{artists|albums}`. */
    public suspend fun searchCatalogueBucket(
        bucket: SearchBucket,
        query: String,
        limit: Int = 20,
        offset: Int = 0,
    ): Outcome<CatalogueSearchPage>

    /** Completions from `GET /api/v1/search/suggest`. */
    public suspend fun suggest(query: String, limit: Int = 8): Outcome<List<SearchSuggestion>>

    /** Recent queries, kept locally for the empty-search state. */
    public fun observeRecentQueries(limit: Int = 10): Flow<List<String>>

    public suspend fun recordRecentQuery(query: String)

    public suspend fun clearRecentQueries()
}
