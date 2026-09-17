package app.needler.core.domain.model

import kotlinx.datetime.Instant

/**
 * What a favourite can point at.
 *
 * Needler offers **binary favourites only**, via Subsonic `star`/`unstar`. `setRating` on this server
 * validates its input and returns success without persisting anything, so offering star ratings would
 * silently do nothing - the domain therefore has no rating concept at all.
 */
public sealed interface FavouriteTarget {
    public data class OfAlbum(val releaseGroupMbid: ReleaseGroupMbid) : FavouriteTarget

    public data class OfArtist(val mbid: ArtistMbid) : FavouriteTarget

    /** Keyed on the stable track key, never on `file_id`. */
    public data class OfTrack(val key: TrackKey) : FavouriteTarget
}

/** A starred item and when it was starred; `getStarred2` orders favourites recently-starred first. */
public data class Favourite(
    val target: FavouriteTarget,
    val starredAt: Instant?,
    /** True while the star/unstar has not yet been replayed to the server from the write queue. */
    val isPendingSync: Boolean = false,
)

/** The three favourite collections the Favourites screen shows, sourced from `getStarred2`. */
public data class Favourites(
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val tracks: List<Track> = emptyList(),
)
