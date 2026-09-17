package app.needler.core.domain.model

import kotlinx.datetime.Instant

/**
 * An artist, keyed on the MusicBrainz artist MBID.
 *
 * [ownedAlbumCount] comes from the mirror. [catalogueAlbumCount] is only known once the artist's
 * discography has been fetched from `GET /api/v1/artists/{mbid}/releases`, which is why it is nullable:
 * artist detail is one of the two places where both server lanes are visible at once.
 */
public data class Artist(
    val mbid: ArtistMbid,
    val name: String,
    /** Name used for alphabetical ordering and the index jump on the Artists screen. */
    val sortName: String = name,
    val ownedAlbumCount: Int = 0,
    val catalogueAlbumCount: Int? = null,
    val artwork: ArtworkRef? = null,
    val genres: List<String> = emptyList(),
    val isFavourite: Boolean = false,
    /**
     * True when the user asked to be told about this artist's future releases, via the
     * `monitor_artist` flag on a request. Drives the "new release from a followed artist"
     * notification; the full following UI is out of v1 scope.
     */
    val isMonitored: Boolean = false,
)

/** A genre bucket, from Subsonic `getGenres`. */
public data class Genre(
    val name: String,
    val albumCount: Int? = null,
    val trackCount: Int? = null,
)

/**
 * The library totals in the header of screens 02, 09 and 13 ("176 albums - 42 GB") plus the
 * "last scan 47m ago" line.
 *
 * `GET /api/v1/library/stats` is authoritative; the sum over the mirror is the offline fallback, and
 * [source] says which one this instance came from so the UI can be honest when offline.
 */
public data class LibraryStats(
    val albumCount: Int,
    val artistCount: Int,
    val trackCount: Int,
    val totalSizeBytes: Long?,
    val lastScanAt: Instant? = null,
    val source: StatsSource = StatsSource.LOCAL_MIRROR,
)

/** Where a [LibraryStats] snapshot came from. */
public enum class StatsSource {
    /** `GET /api/v1/library/stats`. */
    SERVER,

    /** Summed over the local metadata mirror, used offline or with an expired session. */
    LOCAL_MIRROR,
}
