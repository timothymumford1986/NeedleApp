package app.needler.core.domain.model

/**
 * Results from the local FTS mirror. Available on the first keystroke with no network call at all,
 * which is why local search is a `Flow` and catalogue search is a suspending call.
 */
public data class LocalSearchResults(
    val query: String,
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    public val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

/**
 * Results from `GET /api/v1/search`, i.e. the MusicBrainz catalogue by way of the server.
 *
 * Albums here carry [AlbumState.NotOwned] unless the server already owns them; the merge in
 * `UnifiedSearchUseCase` replaces any that the mirror also knows about.
 */
public data class CatalogueSearchResults(
    val query: String,
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    /** Upstream degradation, from the response's `service_status` field. */
    val serviceStatus: ServiceStatus? = null,
)

/** One page of a single catalogue bucket, from `GET /api/v1/search/{artists|albums}`. */
public data class CatalogueSearchPage(
    val bucket: SearchBucket,
    val query: String,
    val offset: Int,
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val hasMore: Boolean = false,
    val serviceStatus: ServiceStatus? = null,
)

/** The paginable catalogue buckets. */
public enum class SearchBucket {
    ARTISTS,
    ALBUMS,
}

/** A completion from `GET /api/v1/search/suggest`. */
public data class SearchSuggestion(
    val text: String,
    val kind: SuggestionKind = SuggestionKind.QUERY,
    /** Set when the suggestion names a specific album, so tapping it can go straight there. */
    val releaseGroupMbid: ReleaseGroupMbid? = null,
    /** Set when the suggestion names a specific artist. */
    val artistMbid: ArtistMbid? = null,
)

public enum class SuggestionKind {
    QUERY,
    ARTIST,
    ALBUM,
}

/**
 * Upstream service health reported alongside catalogue search results.
 *
 * Catalogue search reaches MusicBrainz through the server and can degrade independently of the server
 * itself. This must surface as a quiet inline note, never an error dialog: the local results are still
 * perfectly good.
 */
public data class ServiceStatus(
    val isDegraded: Boolean,
    val message: String? = null,
    val raw: String? = null,
)

/**
 * The merged search state the UI renders.
 *
 * Local results arrive first and are never blocked on the network. [catalogue] tells the UI what
 * happened to the slower lane, so "no catalogue results yet" and "catalogue search needs a connection"
 * are visibly different states.
 */
public data class UnifiedSearchResults(
    val query: String,
    val artists: List<Artist> = emptyList(),
    /**
     * Owned and un-owned albums in one list, merged on release-group MBID. Where both lanes returned
     * the same release group, this holds the *local* record - it knows the true [AlbumState], track
     * count, size and format - and the catalogue copy is discarded.
     */
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val catalogue: CatalogueLaneState = CatalogueLaneState.Idle,
) {
    public val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

/** What the catalogue half of a unified search is doing. */
public sealed interface CatalogueLaneState {
    /** Not started: the query is too short, or the debounce has not elapsed. */
    public data object Idle : CatalogueLaneState

    /** In flight. Results stream in progressively rather than blocking on the slowest bucket. */
    public data object Loading : CatalogueLaneState

    /** Returned. [serviceStatus] may still report upstream degradation. */
    public data class Ready(val serviceStatus: ServiceStatus? = null) : CatalogueLaneState

    /**
     * Unavailable. The UI must say plainly that catalogue search needs a connection when [error] is
     * [NeedlerError.Offline], and that the user should sign in again when it is
     * [NeedlerError.SessionExpired] - in both cases while still showing library results.
     */
    public data class Unavailable(val error: NeedlerError) : CatalogueLaneState
}
