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
