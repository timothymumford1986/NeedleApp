package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row describing how current the mirror is.
 *
 * Singleton is enforced by convention rather than by a CHECK constraint, because Room's schema
 * validator compares the CREATE TABLE statement it generates against the one in the file and a
 * hand-added CHECK would not survive that comparison. Every DAO method therefore hard-codes
 * [SINGLETON_ID], and nothing else may write this table.
 */
@Entity(tableName = "sync_state")
public data class SyncStateEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SINGLETON_ID,

    /**
     * Which server this mirror belongs to: origin plus base path, as
     * `app.needler.core.network.ServerUrl.baseUrl` renders it.
     *
     * Compared on connect. A mismatch means the mirror and the audio cache must be dropped, because
     * MBIDs are global but `file_id` values and playlist ids are not - see
     * `NeedlerDatabase.clearForServerChange`.
     */
    @ColumnInfo(name = "server_identity")
    val serverIdentity: String?,

    /**
     * Opaque library revision passed back as `ifModifiedSince` on `getIndexes`. An unchanged library
     * then returns almost nothing, which is what makes the "one request, under 100 ms" delta-sync
     * budget achievable.
     */
    @ColumnInfo(name = "library_revision")
    val libraryRevision: String?,

    /** Epoch milliseconds of the last full sync (first connect, or server identity changed). */
    @ColumnInfo(name = "last_full_sync_at")
    val lastFullSyncAt: Long?,

    /**
     * Epoch milliseconds of the last delta sync. The foreground trigger is "mirror older than 15
     * min", which is this value plus fifteen minutes.
     */
    @ColumnInfo(name = "last_delta_sync_at")
    val lastDeltaSyncAt: Long?,

    /** Epoch milliseconds from `getScanStatus`, for the "last scan 47m ago" line on screens 09 and 12. */
    @ColumnInfo(name = "last_scan_at")
    val lastScanAt: Long?,

    /**
     * `revision` from `GET /api/v1/downloads/activity-summary`. Persisted because the whole point of
     * the field is that an unchanged poll is nearly free: compare, and skip all downstream work.
     * Keeping it in memory only would make the first poll after every process death do full work.
     */
    @ColumnInfo(name = "downloads_revision")
    val downloadsRevision: String?,

    /** Authoritative album count from `GET /api/v1/library/stats`; null falls back to the local sum. */
    @ColumnInfo(name = "server_album_count")
    val serverAlbumCount: Int?,

    /** Authoritative library size from `GET /api/v1/library/stats`, in bytes. */
    @ColumnInfo(name = "server_size_bytes")
    val serverSizeBytes: Long?,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    public companion object {
        /** The only legal value of [id]. */
        public const val SINGLETON_ID: Int = 0
    }
}
