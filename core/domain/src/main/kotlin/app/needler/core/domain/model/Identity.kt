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
    }
}

/**
 * A MusicBrainz artist MBID. Subsonic exposes it as `ar-<mbid>`; `/api/v1/artists/{id}` takes it bare.
 *
 * ## Not every one of these is a MusicBrainz identifier
 *
 * DroppedNeedle mints an id for every artist it imports, and when it cannot match one to MusicBrainz
 * it derives a **version 5** UUID from the artist's name instead. That id is a perfectly good primary
 * key for the mirror and for `getArtist`, and it is useless to anything upstream: the catalogue lane
 * rejects it with `400 Use the local library artist route for a DroppedNeedle artist ID`.
 *
 * [isNameDerived] is that distinction, and it lives here because the requirements' "Identity model"
 * is the thing it qualifies - the MBID is the join key "the single most load-bearing fact in the
 * architecture", and this is the one case where an id shaped like one is not one. Callers must check
 * before entering the catalogue lane rather than after; see [isCatalogueIdentifier].
 */
@JvmInline
public value class ArtistMbid(public val value: String) {
    init {
        require(value.isNotBlank()) { "ArtistMbid must not be blank" }
    }

    /**
     * True when DroppedNeedle derived this id from the artist's *name* rather than matching them to
     * MusicBrainz, i.e. it is a UUID version 5.
     *
     * MusicBrainz mints version 4 UUIDs - random - for artists, releases and recordings alike. A
     * version 5 UUID is a SHA-1 of a namespace and a name, so it can only have been computed by
     * whoever held the name: the server. There is therefore no chance of a false positive here, and
     * no need to ask the server which kind of id it handed over.
     *
     * Version 3 is the same construction over MD5 and is treated the same way, because a
     * name-derived id is name-derived whichever digest produced it.
     */
    public val isNameDerived: Boolean
        get() {
            val version: Int = uuidVersionOf(value) ?: return false
            return version in NAME_DERIVED_UUID_VERSIONS
        }

    /**
     * True when this id may be handed to a `/api/v1` route that resolves artists against MusicBrainz -
     * the discography route above all.
     *
     * Deliberately **not** `uuidVersionOf(value) == 4`: an id of an unexpected shape is given the
     * benefit of the doubt and tried, because the failure mode of trying is one rejected request and
     * the failure mode of refusing is a discography silently withheld from an artist who has one.
     * Only the shape that is *known* to be rejected is excluded.
     */
    public val isCatalogueIdentifier: Boolean get() = !isNameDerived

    override fun toString(): String = value

    public companion object {
        public const val SUBSONIC_PREFIX: String = "ar-"

        /** The UUID versions built from a name: 3 (MD5) and 5 (SHA-1). See [isNameDerived]. */
        private val NAME_DERIVED_UUID_VERSIONS: Set<Int> = setOf(3, 5)

        /** Parses a Subsonic artist ID (`ar-<mbid>`) or a bare MBID. Returns null for anything empty. */
        public fun fromSubsonicArtistId(id: String): ArtistMbid? {
            val bare: String = id.removePrefix(SUBSONIC_PREFIX).trim()
            return if (bare.isEmpty()) null else ArtistMbid(bare)
        }
    }
}

/**
 * The version nibble of a canonical `8-4-4-4-12` UUID, or `null` when [value] is not one.
 *
 * The version is the first character of the third group - character 14 of the 36 - which is why a
 * one-line substring is enough and `java.util.UUID` is not needed. That matters twice over: this
 * module is a pure Kotlin/JVM library with no Android on the classpath, and `UUID.fromString` is
 * lenient in ways that would let a malformed id through with a plausible-looking version.
 *
 * Anything that is not exactly 36 characters of hex and hyphens in the right places returns `null`,
 * which callers must read as "not a UUID at all", never as "not the version I was asking about" -
 * [ArtistMbid.isCatalogueIdentifier] documents which way that ambiguity is resolved and why.
 */
public fun uuidVersionOf(value: String): Int? {
    if (value.length != UUID_LENGTH) return null
    value.forEachIndexed { index, character ->
        val expectHyphen: Boolean = index in UUID_HYPHEN_POSITIONS
        if (expectHyphen) {
            if (character != '-') return null
        } else {
            if (character.digitToIntOrNull(radix = 16) == null) return null
        }
    }
    return value[UUID_VERSION_INDEX].digitToIntOrNull(radix = 16)
}

/** Length of a canonical UUID: 32 hex digits plus four hyphens. */
private const val UUID_LENGTH: Int = 36

/** Where the four hyphens sit in a canonical UUID. */
private val UUID_HYPHEN_POSITIONS: Set<Int> = setOf(8, 13, 18, 23)

/** The version nibble: the first character of the third group. */
private const val UUID_VERSION_INDEX: Int = 14

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

    override fun toString(): String = value

    public companion object {
        public const val SUBSONIC_PREFIX: String = "pl-"
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
