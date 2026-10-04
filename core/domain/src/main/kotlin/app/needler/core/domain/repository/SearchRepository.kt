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
     *
     * Artists are part of this, and are found by name rather than by the leading word of their sort
     * name: "wonder" must return "Oh Wonder" when the mirror holds their records, because an owned
     * artist missing from an artist search reads as music the app has lost.
     *
     * ## This lane can return one record twice, and every caller must expect it
     *
     * [LocalSearchResults.albums] is one row per mirror row, and the mirror holds **more than one row
     * for some records**. `LibraryRepository.refreshArtistDiscographyPage` caches an artist's
     * MusicBrainz discography as un-owned rows so artist detail renders offline, keyed on the ids
     * MusicBrainz returned - and those are not the ids the mirror holds for the same records, which a
     * device proved twice (see
     * [app.needler.core.domain.usecase.UnifiedSearchUseCase.mergeAlbums]). An owned album and a cached
     * catalogue copy of it are then two rows, both matched by `album_fts` on the artist name, and this
     * query filters on neither state nor `in_library`.
     *
     * Collapsing them is [app.needler.core.domain.usecase.UnifiedSearchUseCase.mergeAlbums]'s job and
     * is deliberately not done here: the rule compares titles and artists across a whole result set,
     * and a second copy of it in the data layer would be a second thing to keep in step. A caller that
     * lists these albums without going through the merge will show the duplicate.
     *
     * **This used to name Android Auto's browse tree as that caller, and it was wrong.** The tree
     * filters its album rows on `Album::isOwned`, and the surplus row the discography cache writes is
     * `NotOwned` by construction, so the duplicate cannot reach a car row - verified by tests in
     * `BrowseTreeTest` rather than by reading. Auto also has a reason not to adopt the merge even if
     * it could: the title-and-artist rule only ever drops an **un-owned** row, which on the search
     * screen is a shopping-list entry, whereas the equivalent reach in a browse tree would collapse
     * two **owned** rows and hide playable music in the one place a listener cannot go looking for it.
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

    /**
     * One page of a single bucket, via `GET /api/v1/search/{artists|albums}`, per REQUIREMENTS.md rule
     * 5.
     *
     * This is what the "show all" row under a capped catalogue block calls, and then calls again for
     * each further page. The combined search of rule 2 caps each bucket so the first screenful arrives
     * quickly; everything past that cap comes from here.
     */
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
