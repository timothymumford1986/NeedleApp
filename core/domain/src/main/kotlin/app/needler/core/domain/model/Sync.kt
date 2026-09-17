package app.needler.core.domain.model

import kotlinx.datetime.Instant

/**
 * The state of the local metadata mirror.
 *
 * The mirror is the read path: every query is served from it and written to it by sync, so the UI never
 * awaits a network call to render and offline needs no separate code path. The mirror is never evicted;
 * only a server identity change drops it.
 */
public data class SyncState(
    /** The server's library revision, used as `ifModifiedSince` on `getIndexes` for delta syncs. */
    val libraryRevision: String? = null,
    val lastFullSyncAt: Instant? = null,
    val lastDeltaSyncAt: Instant? = null,
    /** From `getScanStatus`: the "last scan 47m ago" line on screens 09 and 12. */
    val lastScanAt: Instant? = null,
    val phase: SyncPhase = SyncPhase.IDLE,
    /** The failure of the most recent attempt, if it failed. Offline is normal, not alarming. */
    val lastError: NeedlerError? = null,
) {
    public val isSyncing: Boolean get() = phase != SyncPhase.IDLE

    /** True when no sync has ever completed, so the mirror cannot be trusted to be complete. */
    public val hasNeverSynced: Boolean get() = lastFullSyncAt == null
}

public enum class SyncPhase {
    IDLE,
    DELTA,
    FULL,
}

/** Why a full sync is being run. A full sync is expensive and only these three reasons justify it. */
public enum class FullSyncReason {
    /** First connect after onboarding. */
    FIRST_CONNECT,

    /** The server identity changed: the mirror, playlists and audio cache were dropped first. */
    SERVER_IDENTITY_CHANGED,

    /** The user asked for a rebuild from the diagnostics screen. */
    USER_REQUESTED,
}

/**
 * What one sync pass did.
 *
 * A delta sync against an unchanged library is one request that returns almost nothing, which is the
 * performance budget this type exists to make measurable.
 */
public data class SyncReport(
    val phase: SyncPhase,
    val artistsUpdated: Int = 0,
    val albumsUpdated: Int = 0,
    val tracksUpdated: Int = 0,
    val playlistsUpdated: Int = 0,
    /** True when the revision had not moved, so nothing downstream ran. */
    val libraryUnchanged: Boolean = false,
    /**
     * Tracks whose cached bytes were evicted because the server replaced the file. Pinned tracks among
     * these are re-downloaded immediately.
     */
    val staleTracksEvicted: List<TrackKey> = emptyList(),
    val newRevision: String? = null,
)

/**
 * The result of syncing one album, including the staleness verdict for its tracks.
 *
 * This is where the quality-upgrade rule is enforced: each track's current `file_id`, size, duration and
 * format are compared against the cached record, and any difference means the cached bytes are the
 * older, worse copy and must go.
 */
public data class AlbumSyncReport(
    val releaseGroupMbid: ReleaseGroupMbid,
    val trackCount: Int,
    val staleTracksEvicted: List<TrackKey> = emptyList(),
    val tracksQueuedForRedownload: List<TrackKey> = emptyList(),
)

/**
 * One journalled mutation made while offline.
 *
 * Every mutation is replayed in order on reconnect. Each entry carries a monotonic [sequence] and an
 * [attempts] count, and is dropped after a permanent rejection with a notice to the user.
 */
public data class WriteQueueEntry(
    val sequence: Long,
    val operation: WriteOperation,
    val attempts: Int = 0,
    val lastError: NeedlerError? = null,
    val createdAt: Instant,
) {
    /** A permanent rejection means the entry is dropped rather than retried forever. */
    public val shouldDrop: Boolean
        get() {
            val error: NeedlerError = lastError ?: return false
            return !error.isRetryable
        }
}

/** The mutations that can be journalled. */
public sealed interface WriteOperation {
    /**
     * A pull placed while offline.
     *
     * If the album arrived by other means before this entry replays, it is discarded silently rather
     * than submitted - re-requesting music the server already has is noise.
     */
    public data class PlaceAlbumRequest(val request: AlbumRequest) : WriteOperation

    public data class PlaceTrackRequest(val request: TrackRequest) : WriteOperation

    public data class CancelRequest(val releaseGroupMbid: ReleaseGroupMbid) : WriteOperation

    public data class RetryRequest(val releaseGroupMbid: ReleaseGroupMbid) : WriteOperation

    /** A playlist mutation. Last-write-wins: Subsonic offers no revision or conflict signal. */
    public data class EditPlaylist(val edit: PlaylistEdit) : WriteOperation

    public data class SetFavourite(
        val target: FavouriteTarget,
        val starred: Boolean,
    ) : WriteOperation

    /** A play, submitted with its original timestamp so offline listening lands correctly. */
    public data class SubmitScrobble(val scrobble: ScrobbleEvent) : WriteOperation
}

/** The outcome of one write-queue flush. */
public data class WriteQueueFlushReport(
    val replayed: Int,
    val dropped: List<DroppedWrite> = emptyList(),
    val remaining: Int = 0,
)

/** A write the queue gave up on, so the UI can tell the user what did not happen. */
public data class DroppedWrite(
    val sequence: Long,
    val operation: WriteOperation,
    val error: NeedlerError,
)

/**
 * One scrobble.
 *
 * `submission=false` is sent on track start and `submission=true` past the halfway point. The server
 * forwards to ListenBrainz or Last.fm according to the user's server-side preferences, so Needler
 * decides only *whether* to report, never where.
 */
public data class ScrobbleEvent(
    val trackKey: TrackKey,
    /**
     * The fetch handle the play used. `scrobble` is keyed on the Subsonic track id, so the handle is
     * needed to submit - it is carried alongside [trackKey] rather than replacing it, since a queued
     * scrobble may be submitted after a quality upgrade has moved the file id on.
     */
    val fetchHandle: TrackFetchHandle,
    val playedAt: Instant,
    val submission: Boolean,
)
