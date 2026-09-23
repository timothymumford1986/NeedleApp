package app.needler.core.domain.model

import kotlinx.datetime.Instant

/**
 * One track of an owned album.
 *
 * Its identity is [key] - release group, disc, track number. The mutable server-side file row lives in
 * [fetch] and is *not* part of the track's identity; see [FileId] for why that separation is
 * load-bearing rather than stylistic.
 *
 * Catalogue-only albums have no tracks: the server has no files for them, so there is nothing to
 * model until acquisition completes.
 */
public data class Track(
    val key: TrackKey,
    val title: String,
    /** Track artist, which may differ from the album artist on compilations. */
    val artistName: String,
    val albumTitle: String? = null,
    val recordingMbid: RecordingMbid? = null,
    /** Display duration in milliseconds. */
    val durationMs: Long? = null,
    /** How to fetch the current bytes, and the fingerprint used to detect that they have changed. */
    val fetch: TrackFetchHandle,
    val artwork: ArtworkRef? = null,
    val isFavourite: Boolean = false,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
) {
    /** Convenience: the album this track belongs to. */
    public val releaseGroupMbid: ReleaseGroupMbid get() = key.releaseGroupMbid

    /** The badge quality for this track (FLAC, MP3 320, ...). */
    public val quality: AudioQuality get() = fetch.quality
}

/**
 * A row in the on-device audio store, keyed on [TrackKey].
 *
 * One store serves both offline tiers: [pinned] decides eviction. Pinned rows are exempt from the
 * storage budget and from LRU; unpinned rows are evicted by [lastPlayedAt] ascending until usage fits
 * the budget. Storing pinned downloads separately would double disk use for no benefit.
 */
public data class CachedAudio(
    val key: TrackKey,
    /** Path inside app-private internal storage. No permissions needed; cleared on uninstall. */
    val filePath: String,
    val sizeOnDiskBytes: Long,
    /** True while bytes are still being fetched by per-track `Range` GETs, which resume. */
    val isComplete: Boolean,
    val lastPlayedAt: Instant? = null,
    val pinned: Boolean = false,
    /**
     * The fetch handle these bytes were downloaded with. Compared against the server's current handle
     * on every album sync; any difference means the cached bytes are a stale, usually lower-quality
     * copy and must be deleted.
     */
    val sourceHandle: TrackFetchHandle,
    val downloadedAt: Instant? = null,
) {
    /**
     * True when these bytes must be discarded because the server has replaced the file.
     *
     * DroppedNeedle upgrades files in place, so without this check a user silently keeps listening to
     * the worse copy they cached months ago.
     */
    public fun isStaleFor(current: TrackFetchHandle): Boolean = sourceHandle.isStaleComparedTo(current)
}

/**
 * The orderings the Songs tab offers, which are deliberately **not** [AlbumListKind].
 *
 * The two enumerations read alike and mean different things, and conflating them is precisely the
 * fault this type exists to make impossible. The Songs tab was once assembled by flattening the
 * tracks of an album list, so picking "Title" sorted by *album* title and produced a single record
 * in track order rather than an alphabet of songs. An ordering over songs has to be expressed
 * against the `track` table, and it therefore needs a vocabulary of its own rather than a borrowed
 * album one.
 *
 * REQUIREMENTS.md "Library browse" fixes the album orderings and is silent on a songs ordering
 * beyond the rule that governs all of them - browsing reads the local mirror, so it behaves
 * identically online and offline. These five follow the sort control the design pack draws on
 * screens 02, 09 and 13, and each means the nearest thing the mirror can honestly answer for a
 * song; where that is nothing,
 * [app.needler.core.domain.repository.LibraryRepository.observeTracks] says so.
 */
public enum class TrackListKind {

    /**
     * Song title, A to Z, over `track.title_normalised` - the song's own title, never its album's.
     * That column is lower-cased and article-stripped on write, so "The Rip" files under R.
     */
    ALPHABETICAL_BY_TITLE,

    /**
     * Album artist, then album, then disc and track number, so an artist's records arrive whole and
     * in running order rather than interleaved by song title.
     */
    ALPHABETICAL_BY_ARTIST,

    /**
     * Recently added first. A track carries no arrival time of its own - it appeared when its album
     * did - so this is the album's `added_at`, and every track of one record shares a position.
     */
    NEWEST,

    /**
     * Most played. The mirror holds no play count for a track, so this cannot be served exactly;
     * [app.needler.core.domain.repository.LibraryRepository.observeTracks] documents what it does
     * instead and why guessing would be worse.
     */
    FREQUENT,

    /** Starred songs, recently starred first, read from the `favourite` table's resolved track key. */
    STARRED,
}
