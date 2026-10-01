package app.needler.core.data.repository

import app.needler.core.data.local.FtsQuery
import app.needler.core.data.local.SortKeys
import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.ArtistDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.ArtistEntity
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
import app.needler.core.domain.usecase.UnifiedSearchUseCase
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
     * Local FTS over `album_fts` and `track_fts`, plus the two-source artist lane below.
     *
     * Artists have no FTS table of their own - REQUIREMENTS.md's persistence table specifies FTS4 over
     * album title plus artist name and over track title, and `album_fts` therefore already indexes
     * every owned artist's name.
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
                artists = ownedArtists(trimmed, albums, limit),
                albums = albums.map { EntityMappers.album(it) },
                tracks = tracks.map { EntityMappers.track(it) },
            )
        }
    }

    /**
     * The owned artists a query finds, from the artist index **and** from the artists of the albums
     * `album_fts` just matched.
     *
     * The second source is the fix for a bug that shipped: searching "wonder" returned four catalogue
     * strangers and not "Oh Wonder", three of whose albums were on the device. The artist index is
     * queried with [ArtistDao.searchArtistsByPrefix], a prefix `LIKE` on `sort_name_normalised`, so it
     * can only ever find a name that *begins* with what was typed - and "oh wonder" does not begin
     * with "wonder". Every screen that filters the alphabetical Artists list is well served by that
     * query; a search field is not, because nobody types an artist's first word to find them.
     *
     * `album_fts` covers the gap exactly, at no extra index cost: it tokenises the artist name beside
     * the album title, so the very query that found the three owned albums also proves their artist
     * matches. Their artist MBIDs are taken from those rows and the real mirror rows read back, so the
     * owned album count and the artwork reference are the row's own and not inferred from an album.
     *
     * Two guards on that second source:
     *
     *  - the candidate's **name** must match what was typed, per
     *    [UnifiedSearchUseCase.artistMatches]. An album matches `album_fts` on its title as well as
     *    its artist, and a search for "wonder" that found the album *Wonder* must not put its
     *    unrelated artist into the artist results. That predicate is borrowed from the use case rather
     *    than written again here: it is the same rule the merge ranks with, and two copies of it would
     *    let this lane admit an artist the merge then sorts last for not matching.
     *  - a candidate with no row in `artist` is skipped rather than synthesised from the album. An
     *    artist the mirror has never heard of is not an artist the user owns, and a row invented here
     *    would carry a zero album count and no artwork into a list whose whole purpose is to show what
     *    is owned.
     *
     * Rejected alternative: a third FTS table over artist names. It would answer this in one query,
     * and it would also be a third inverted index for sync to keep consistent, for a list capped at
     * [limit] rows that `album_fts` already answers inside the 50 ms local-search budget.
     */
    private suspend fun ownedArtists(
        query: String,
        matchedAlbums: List<AlbumEntity>,
        limit: Int,
    ): List<Artist> {
        val found: LinkedHashMap<String, Artist> = LinkedHashMap()
        for (row in artistDao.searchArtistsByPrefix(SortKeys.normalise(query), limit)) {
            found[row.artistMbid] = EntityMappers.artist(row)
        }
        for (album in matchedAlbums) {
            if (found.size >= limit) break
            val mbid: String = album.artistMbid ?: continue
            if (found.containsKey(mbid)) continue
            // Checked before the read, so an album that matched on its title alone costs no query.
            if (!UnifiedSearchUseCase.artistMatches(query, album.artistName)) continue
            val artist: ArtistEntity = artistDao.getArtist(mbid) ?: continue
            found[mbid] = EntityMappers.artist(artist)
        }
        return found.values.toList()
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
