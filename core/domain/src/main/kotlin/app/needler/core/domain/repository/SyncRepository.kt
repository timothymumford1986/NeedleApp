package app.needler.core.domain.repository

import app.needler.core.domain.model.AlbumSyncReport
import app.needler.core.domain.model.FullSyncReason
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.SyncReport
import app.needler.core.domain.model.SyncState
import app.needler.core.domain.model.WriteQueueEntry
import app.needler.core.domain.model.WriteQueueFlushReport
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Keeping the mirror current, and replaying what was written while offline.
 *
 * The mirror is never evicted; it is only refreshed by sync, and only dropped when the server identity
 * changes. Metadata is fully mirrored so browsing, search and queueing never wait on the server.
 */
public interface SyncRepository {

    public fun observeSyncState(): Flow<SyncState>

    public suspend fun currentSyncState(): SyncState

    /**
     * Runs a delta sync only if the mirror is older than [maxAge]. Called on app foreground, where the
     * requirement is 15 minutes.
     */
    public suspend fun syncIfStale(maxAge: Duration = SyncRepository.DefaultStaleAfter): Outcome<SyncReport>

    /**
     * A delta sync: `getIndexes` with `ifModifiedSince` set to the stored revision, then changed
     * artists, then `getAlbumList2?type=newest` for additions, then the affected albums.
     *
     * An unchanged library returns almost nothing - one request, under 100 ms - so this is safe to call
     * often. [force] skips the staleness check but not the revision check.
     */
    public suspend fun deltaSync(force: Boolean = false): Outcome<SyncReport>

    /**
     * A full sync. Only justified by [FullSyncReason]: first connect, a changed server identity, or an
     * explicit user rebuild.
     *
     * A changed server identity drops the mirror and the audio cache deliberately, because MBIDs are
     * global but `file_id` values and playlist IDs are not.
     */
    public suspend fun fullSync(reason: FullSyncReason): Outcome<SyncReport>

    /**
     * Syncs one album and applies the staleness rule to its tracks.
     *
     * Each track's current `file_id`, size, duration and format are compared against the cached record;
     * any difference means the cached bytes are the pre-upgrade copy, so they are deleted and
     * re-downloaded immediately when the track is pinned. Without this, users silently keep listening to
     * the lower-quality file they cached months ago.
     */
    public suspend fun syncAlbum(mbid: ReleaseGroupMbid): Outcome<AlbumSyncReport>

    /** Re-reads `getScanStatus` for the "last scan 47m ago" line. */
    public suspend fun refreshScanStatus(): Outcome<Unit>

    /**
     * The offline write queue, in sequence order: pulls, playlist changes, stars and scrobbles.
     *
     * Exposed so the diagnostics screen can show what is waiting and the UI can tell the user when
     * something was dropped.
     */
    public fun observeWriteQueue(): Flow<List<WriteQueueEntry>>

    /**
     * Replays the queue in order on reconnect.
     *
     * Each entry carries an attempt count and is dropped after a permanent rejection - see
     * [app.needler.core.domain.model.NeedlerError.isRetryable] - with the drop reported in
     * [WriteQueueFlushReport.dropped] so the user can be told. A queued pull for an album that arrived
     * by other means while offline is discarded silently rather than replayed.
     */
    public suspend fun flushWriteQueue(): Outcome<WriteQueueFlushReport>

    /** Drops one entry the user chose to abandon. */
    public suspend fun dropWriteQueueEntry(sequence: Long): Outcome<Unit>

    /**
     * Drops the mirror, the audio cache, the artwork cache, pins and playlist IDs, then schedules a
     * full sync. Called when the saved server's identity changes.
     */
    public suspend fun resetForServerIdentityChange(): Outcome<Unit>

    public companion object {
        /** The mirror is considered stale 15 minutes after the last delta sync. */
        public val DefaultStaleAfter: Duration = 15.minutes
    }
}
