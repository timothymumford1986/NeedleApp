package app.needler.core.data.repository

import app.needler.core.data.local.FtsQuery
import app.needler.core.data.local.SortKeys
import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.ArtistDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.mapper.CatalogueMappers
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.data.mapper.networkCall
import app.needler.core.data.platform.AppStateStore
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.CatalogueSearchPage
import app.needler.core.domain.model.CatalogueSearchResults
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.SearchSuggestion
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.map
import app.needler.core.domain.repository.SearchRepository
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.SearchBucketResponseDto
import app.needler.core.network.v1.dto.SearchResponseDto
import app.needler.core.network.v1.dto.SuggestResponseDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import app.needler.core.network.v1.SearchBucket as WireBucket

/**
 * The two search lanes, kept apart.
 *
 * They are deliberately **not** merged here. Local FTS must emit on the first keystroke with no
 * network call at all; the catalogue lane is debounced, reaches MusicBrainz through the server, and
 * is allowed to fail. Merging at this level would make the fast lane wait for the slow one, which is
 * the one thing search must never do. `UnifiedSearchUseCase` merges them on the release-group MBID,
 * where a local hit wins - the mirror's row knows the true state, track count, size and format, and
 * the catalogue copy knows none of that.
 */
public class DefaultSearchRepository(
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val artistDao: ArtistDao,
    private val appStateStore: AppStateStore,
    private val v1: V1Api,
) : SearchRepository {

    /**
     * Local FTS over `album_fts` and `track_fts`, plus an artist prefix scan.
     *
     * Artists have no FTS table - the Artists screen is an alphabetical index with a jump list, and
     * a prefix scan over the normalised sort name is what that screen's own affordance already is.
     */
    override fun searchLocal(query: String, limit: Int): Flow<LocalSearchResults> {
        val match: String = FtsQuery.forPrefixSearch(query)
            ?: return flowOf(LocalSearchResults(query = query.trim()))
        val trimmed: String = query.trim()
        return combine(
            albumDao.observeAlbumSearch(match, limit),
            trackDao.observeTrackSearch(match, limit),
        ) { albums: List<AlbumEntity>, tracks: List<TrackEntity> ->
            albums to tracks
        }.map { (albums, tracks) ->
            LocalSearchResults(
                query = trimmed,
                artists = artistDao
                    .searchArtistsByPrefix(SortKeys.normalise(trimmed), limit)
                    .map(EntityMappers::artist),
                albums = albums.map { EntityMappers.album(it) },
                tracks = tracks.map { EntityMappers.track(it) },
            )
        }
    }

    override suspend fun searchCatalogue(
        query: String,
        limitArtists: Int,
        limitAlbums: Int,
    ): Outcome<CatalogueSearchResults> {
        val call: Outcome<SearchResponseDto> = networkCall {
            v1.search(query = query, limitArtists = limitArtists, limitAlbums = limitAlbums)
        }
        return call.map { dto ->
            CatalogueSearchResults(
                query = query,
                artists = dto.artists.mapNotNull(CatalogueMappers::artist),
                albums = dto.albums.mapNotNull(CatalogueMappers::album),
                // Upstream degradation, not a failure. It surfaces as a quiet inline note: the
                // results beside it are still good, and an error dialog over a partial MusicBrainz
                // response would be a lie about what happened.
                serviceStatus = CatalogueMappers.serviceStatus(dto.serviceStatus),
            )
        }
    }

    /**
     * One page of one bucket.
     *
     * There is no total count on this endpoint, so paging is blind: a full page means there may be
     * another. `hasMore` is derived from exactly that and nothing else.
     */
    override suspend fun searchCatalogueBucket(
        bucket: SearchBucket,
        query: String,
        limit: Int,
        offset: Int,
    ): Outcome<CatalogueSearchPage> {
        val wire: WireBucket = when (bucket) {
            SearchBucket.ARTISTS -> WireBucket.Artists
            SearchBucket.ALBUMS -> WireBucket.Albums
        }
        val call: Outcome<SearchBucketResponseDto> = networkCall {
            v1.searchBucket(bucket = wire, query = query, limit = limit, offset = offset)
        }
        return call.map { dto ->
            val artists: List<Artist> = dto.results.mapNotNull(CatalogueMappers::artist)
            val albums: List<Album> = dto.results.mapNotNull(CatalogueMappers::album)
            CatalogueSearchPage(
                bucket = bucket,
                query = query,
                offset = dto.offset,
                artists = artists,
                albums = albums,
                hasMore = dto.results.size >= limit && limit > 0,
                serviceStatus = CatalogueMappers.serviceStatus(
                    mapOf("bucket" to dto.status).takeIf { !dto.status.equals("ok", true) },
                ),
            )
        }
    }

    override suspend fun suggest(query: String, limit: Int): Outcome<List<SearchSuggestion>> {
        // The endpoint requires at least two characters; asking with one is a guaranteed 400 and a
        // wasted round trip on every first keystroke.
        if (query.trim().length < MIN_SUGGEST_LENGTH) return Outcome.Success(emptyList())
        val call: Outcome<SuggestResponseDto> = networkCall { v1.suggest(query, limit) }
        return call.map { dto -> dto.results.mapNotNull(CatalogueMappers::suggestion) }
    }

    override fun observeRecentQueries(limit: Int): Flow<List<String>> =
        appStateStore.observeRecentQueries().map { it.take(limit) }

    override suspend fun recordRecentQuery(query: String) {
        appStateStore.recordRecentQuery(query)
    }

    override suspend fun clearRecentQueries() {
        appStateStore.clearRecentQueries()
    }

    /** Convenience for callers that want local tracks alone, e.g. the Auto browse tree. */
    public suspend fun searchLocalTracks(query: String, limit: Int = 50): List<Track> {
        val match: String = FtsQuery.forPrefixSearch(query) ?: return emptyList()
        return trackDao.searchTracks(match, limit).map { EntityMappers.track(it) }
    }

    public companion object {
        public const val MIN_SUGGEST_LENGTH: Int = 2
    }
}
