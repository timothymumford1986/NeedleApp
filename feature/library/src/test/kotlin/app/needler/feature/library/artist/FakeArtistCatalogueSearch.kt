package app.needler.feature.library.artist

import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.CatalogueSearchPage
import app.needler.core.domain.model.CatalogueSearchResults
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.SearchSuggestion
import app.needler.core.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The one catalogue question the artist screen asks, faked.
 *
 * `ArtistViewModel` takes a [SearchRepository] for `onFindInCatalogue` and for nothing else, so this
 * fake implements that one member and refuses the rest rather than returning plausible empties. A
 * fake that quietly answered `suggest` or `searchLocal` would let a later edit start using the
 * search lane from this screen without a single test noticing - and the reason the dependency is
 * narrow is written on the constructor parameter.
 *
 * It lives beside the artist tests rather than in `:feature:library`'s shared `Fakes.kt` because it
 * is the only screen in the module with this dependency; promoting it when a second one appears is a
 * move, not a rewrite.
 */
internal class FakeArtistCatalogueSearch : SearchRepository {

    /** What the artists bucket answers. Replace per test. */
    var bucketOutcome: Outcome<CatalogueSearchPage> = Outcome.Success(
        CatalogueSearchPage(bucket = SearchBucket.ARTISTS, query = "", offset = 0),
    )

    /** Every bucket query made, so a test can assert the name was the query and the limit was asked. */
    val bucketQueries: MutableList<Triple<SearchBucket, String, Int>> = mutableListOf()

    /** Answers with [artists] as the artists bucket. */
    fun returning(vararg artists: Artist) {
        bucketOutcome = Outcome.Success(
            CatalogueSearchPage(
                bucket = SearchBucket.ARTISTS,
                query = "",
                offset = 0,
                artists = artists.toList(),
            ),
        )
    }

    override suspend fun searchCatalogueBucket(
        bucket: SearchBucket,
        query: String,
        limit: Int,
        offset: Int,
    ): Outcome<CatalogueSearchPage> {
        bucketQueries += Triple(bucket, query, limit)
        return bucketOutcome
    }

    override fun searchLocal(query: String, limit: Int): Flow<LocalSearchResults> =
        error("the artist screen does not search the mirror")

    override suspend fun searchCatalogue(
        query: String,
        limitArtists: Int,
        limitAlbums: Int,
    ): Outcome<CatalogueSearchResults> =
        error("the artist screen uses the per-bucket endpoint; see onFindInCatalogue")

    override suspend fun suggest(query: String, limit: Int): Outcome<List<SearchSuggestion>> =
        error("the artist screen offers no completions")

    override fun observeRecentQueries(limit: Int): Flow<List<String>> = flowOf(emptyList())

    override suspend fun recordRecentQuery(query: String) =
        error("the artist screen records no queries")

    override suspend fun clearRecentQueries() =
        error("the artist screen records no queries")
}
