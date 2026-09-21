package app.needler.core.domain.model

import kotlin.jvm.JvmInline

/*
 * Identity types for Needler's domain model.
 *
 * The MusicBrainz release-group MBID is the join key for the whole architecture: DroppedNeedle's
 * Subsonic album IDs are `al-<release-group-mbid>` and its request API (`POST /api/v1/requests/new`)
 * takes the very same MBID. That is what allows one Album type to describe both an album the server
 * owns and an album that exists only in the MusicBrainz catalogue.
 *
 * Everything in this file is an identity except FileId, which is deliberately *not* one. Read its
 * documentation before using it anywhere.
 */

/**
 * A MusicBrainz release-group MBID, e.g. `d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a2f1`.
 *
 * This is the primary key of the album mirror, the cache key prefix for audio, and the one key both
 * server lanes agree on. Store it *without* the Subsonic `al-` prefix.
 */
@JvmInline
public value class ReleaseGroupMbid(public val value: String) {
    init {
        require(value.isNotBlank()) { "ReleaseGroupMbid must not be blank" }
    }

    /** The Subsonic album ID for this release group, i.e. `al-<mbid>`. */
    public val subsonicAlbumId: String get() = SUBSONIC_PREFIX + value

    override fun toString(): String = value

    public companion object {
        public const val SUBSONIC_PREFIX: String = "al-"

        /** Parses a Subsonic album ID (`al-<mbid>`) or a bare MBID. Returns null for anything empty. */
        public fun fromSubsonicAlbumId(id: String): ReleaseGroupMbid? {
            val bare: String = id.removePrefix(SUBSONIC_PREFIX).trim()
            return if (bare.isEmpty()) null else ReleaseGroupMbid(bare)
        }
    }
}

/** A MusicBrainz artist MBID. Subsonic exposes it as `ar-<mbid>`; `/api/v1/artists/{id}` takes it bare. */
@JvmInline
public value class ArtistMbid(public val value: String) {
    init {
        require(value.isNotBlank()) { "ArtistMbid must not be blank" }
    }

    /** The Subsonic artist ID for this artist, i.e. `ar-<mbid>`. */
    public val subsonicArtistId: String get() = SUBSONIC_PREFIX + value

    override fun toString(): String = value

    public companion object {
        public const val SUBSONIC_PREFIX: String = "ar-"

        /** Parses a Subsonic artist ID (`ar-<mbid>`) or a bare MBID. Returns null for anything empty. */
        public fun fromSubsonicArtistId(id: String): ArtistMbid? {
            val bare: String = id.removePrefix(SUBSONIC_PREFIX).trim()
            return if (bare.isEmpty()) null else ArtistMbid(bare)
        }
    }
}

/**
 * A MusicBrainz recording MBID. Optional metadata on a [Track], and the key taken by
 * `POST /api/v1/tracks/{recording_mbid}/request` for single-track requests.
 */
@JvmInline
public value class RecordingMbid(public val value: String) {
    init {
        require(value.isNotBlank()) { "RecordingMbid must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A Subsonic playlist ID.
 *
 * Server-local, not global, which is why changing server identity drops playlists along with the
 * mirror: MBIDs are global but playlist IDs and file IDs are not.
 */
@JvmInline
public value class PlaylistId(public val value: String) {
    init {
        require(value.isNotBlank()) { "PlaylistId must not be blank" }
    }

    /** The Subsonic playlist ID, i.e. `pl-<id>`. */
    public val subsonicPlaylistId: String get() = SUBSONIC_PREFIX + value

    override fun toString(): String = value

    public companion object {
        public const val SUBSONIC_PREFIX: String = "pl-"

        public fun fromSubsonicPlaylistId(id: String): PlaylistId? {
            val bare: String = id.removePrefix(SUBSONIC_PREFIX).trim()
            return if (bare.isEmpty()) null else PlaylistId(bare)
        }
    }
}

/** A `/api/v1/downloads` task ID. Identifies a server-side acquisition task, not an album. */
@JvmInline
public value class PullTaskId(public val value: String) {
    init {
        require(value.isNotBlank()) { "PullTaskId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A server-side track-file row ID: the `file_id` behind Subsonic's `tr-<file_id>`.
 *
 * ## This is a fetch handle, never an identity
 *
 * DroppedNeedle performs automatic quality upgrades, replacing audio files **in place**, and library
 * re-imports rewrite rows too. A `file_id` therefore identifies "whatever bytes currently sit behind
 * this track", not the track itself. Two consequences, both mandatory:
 *
 * 1. **Never key a cache, map, database row, playlist entry or UI list on a [FileId].** The identity of
 *    a track is [TrackKey] (release group, disc number, track number). Keying on `file_id` means that
 *    after a server-side quality upgrade the app keeps serving the older, worse bytes it downloaded
 *    months ago and nothing ever reports an error. The failure is silent, which is exactly why this
 *    rule exists.
 * 2. Use it only to *fetch* - `stream?id=tr-<file_id>`, `download?id=tr-<file_id>`, `scrobble` - and as
 *    the staleness signal described on [TrackFetchHandle].
 *
 * No function in this domain module takes a `FileId` as a lookup key. If you find yourself wanting to
 * add one, the intended key is [TrackKey].
 */
@JvmInline
public value class FileId(public val value: String) {
    init {
        require(value.isNotBlank()) { "FileId must not be blank" }
    }

    /** The Subsonic track ID for this file, i.e. `tr-<file_id>`: the only legitimate use of a file id. */
    public val subsonicTrackId: String get() = SUBSONIC_PREFIX + value

    override fun toString(): String = value

    public companion object {
        public const val SUBSONIC_PREFIX: String = "tr-"

        public fun fromSubsonicTrackId(id: String): FileId? {
            val bare: String = id.removePrefix(SUBSONIC_PREFIX).trim()
            return if (bare.isEmpty()) null else FileId(bare)
        }
    }
}

/**
 * The stable identity of a track: the release group it belongs to plus its position on the record.
 *
 * This is the audio cache key, the track mirror key, and the key that correlates a queue item with
 * cached bytes. It survives quality upgrades and re-imports, which [FileId] does not.
 *
 * @param releaseGroupMbid the album this track belongs to.
 * @param discNumber 1-based disc number; single-disc releases use 1.
 * @param trackNumber 1-based track number within the disc.
 */
public data class TrackKey(
    val releaseGroupMbid: ReleaseGroupMbid,
    val discNumber: Int,
    val trackNumber: Int,
) {
    init {
        require(discNumber >= 1) { "discNumber must be >= 1, was " + discNumber }
        require(trackNumber >= 1) { "trackNumber must be >= 1, was " + trackNumber }
    }

    /** A stable, filesystem- and database-safe rendering of this key, e.g. `<mbid>/1/7`. */
    public val canonicalString: String
        get() = releaseGroupMbid.value + "/" + discNumber + "/" + trackNumber

    override fun toString(): String = canonicalString
}

/**
 * Everything needed to *fetch* a track's current bytes, plus the fingerprint used to notice that the
 * server has replaced them.
 *
 * Requirements: "On every album sync, compare each track's `file_id`, size, duration and format against
 * the cached record. Any difference means the bytes are stale: delete them, and re-download immediately
 * if the track is pinned." [isStaleComparedTo] is that comparison, kept in one place so it cannot drift.
 *
 * These fields are grouped away from [Track]'s display metadata precisely so nobody mistakes them for
 * identity.
 */
public data class TrackFetchHandle(
    val fileId: FileId,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val format: AudioFormat?,
    val bitrateKbps: Int?,
) {
    /** `tr-<file_id>`: the id to pass to `stream`, `download` and `scrobble`. */
    public val subsonicTrackId: String get() = fileId.subsonicTrackId

    /** The quality badge for this file, as screen 13 renders it (FLAC, MP3 320, ...). */
    public val quality: AudioQuality get() = AudioQuality(format, bitrateKbps)

    /**
     * True when bytes fetched with *this* handle must be treated as stale given [current], the handle
     * the server reports now.
     *
     * A field counts as changed **only when both sides carry a value**. A null means "the server did
     * not say", never "it changed" — and that distinction is the whole safety of this function. It is
     * called on every play, so treating an absent value as a difference would evict and re-download a
     * track each time it was played: one server release that stopped reporting durations would pull a
     * user's entire offline library back down, quite possibly over mobile data, with no visible cause.
     *
     * The file id is the exception and is compared directly. It is never null, and a changed id is the
     * server telling us plainly that these are different bytes.
     */
    public fun isStaleComparedTo(current: TrackFetchHandle): Boolean =
        fileId != current.fileId ||
            differs(sizeBytes, current.sizeBytes) ||
            differs(durationMs, current.durationMs) ||
            differs(format, current.format) ||
            differs(bitrateKbps, current.bitrateKbps)

    private companion object {
        /** Two values differ only when both are present and unequal. */
        private fun differs(cached: Any?, current: Any?): Boolean =
            cached != null && current != null && cached != current
    }
}

/**
 * Where to fetch artwork for an album or artist.
 *
 * Owned content uses Subsonic `getCoverArt`; catalogue-only content uses
 * `GET /api/v1/covers/release-group/{mbid}`. The two lanes have different endpoints, so the domain
 * records which one applies rather than letting each UI infer it from an album's state.
 */
public sealed interface ArtworkRef {
    /** Artwork served by Subsonic `getCoverArt?id=<subsonicId>`, for content the server owns. */
    public data class Owned(val subsonicId: String) : ArtworkRef

    /** Artwork served by `GET /api/v1/covers/release-group/{mbid}`, for catalogue-only content. */
    public data class Catalogue(val releaseGroupMbid: ReleaseGroupMbid) : ArtworkRef

    /** An absolute URL handed to us directly by `/api/v1` (artist images). */
    public data class Remote(val url: String) : ArtworkRef
}
