package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.needler.core.data.local.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * The singleton sync-state row.
 *
 * Every statement hard-codes [SyncStateEntity.SINGLETON_ID], so there is no way to create a second
 * row by accident. The targeted `UPDATE`s exist because the sync phases finish at different times:
 * writing the whole entity from each phase would have a delta sync clobber the scan time a poll had
 * just recorded.
 */
@Dao
public interface SyncStateDao {

    @Upsert
    public suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE id = 0")
    public fun observeSyncState(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 0")
    public suspend fun getSyncState(): SyncStateEntity?

    @Query("SELECT server_identity FROM sync_state WHERE id = 0")
    public suspend fun getServerIdentity(): String?

    @Query("SELECT library_revision FROM sync_state WHERE id = 0")
    public suspend fun getLibraryRevision(): String?

    /** `revision` from the downloads activity summary: an unchanged value means skip all poll work. */
    @Query("SELECT downloads_revision FROM sync_state WHERE id = 0")
    public suspend fun getDownloadsRevision(): String?

    @Query(
        """
        UPDATE sync_state
        SET library_revision = :libraryRevision,
            last_delta_sync_at = :syncedAt,
            updated_at = :syncedAt
        WHERE id = 0
        """,
    )
    public suspend fun recordDeltaSync(libraryRevision: String?, syncedAt: Long)

    @Query(
        """
        UPDATE sync_state
        SET library_revision = :libraryRevision,
            last_full_sync_at = :syncedAt,
            last_delta_sync_at = :syncedAt,
            updated_at = :syncedAt
        WHERE id = 0
        """,
    )
    public suspend fun recordFullSync(libraryRevision: String?, syncedAt: Long)

    @Query("UPDATE sync_state SET last_scan_at = :lastScanAt, updated_at = :updatedAt WHERE id = 0")
    public suspend fun recordScanTime(lastScanAt: Long?, updatedAt: Long)

    @Query(
        """
        UPDATE sync_state
        SET downloads_revision = :downloadsRevision, updated_at = :updatedAt
        WHERE id = 0
        """,
    )
    public suspend fun recordDownloadsRevision(downloadsRevision: String?, updatedAt: Long)

    @Query(
        """
        UPDATE sync_state
        SET server_album_count = :albumCount,
            server_size_bytes = :sizeBytes,
            updated_at = :updatedAt
        WHERE id = 0
        """,
    )
    public suspend fun recordServerStats(albumCount: Int?, sizeBytes: Long?, updatedAt: Long)

    /**
     * Records which server this mirror belongs to.
     *
     * The normal path is `NeedlerDatabase.clearForServerChange`, which wipes the mirror and writes
     * the new identity in one transaction, so a crash mid-way leaves the old mirror *and* the old
     * identity and the next connect simply wipes again. Call this directly only to claim a database
     * that has no identity yet - never to re-point an existing mirror at a different server, which
     * would leave `file_id` values and playlist ids describing music that is not there.
     */
    @Query("UPDATE sync_state SET server_identity = :serverIdentity, updated_at = :updatedAt WHERE id = 0")
    public suspend fun recordServerIdentity(serverIdentity: String?, updatedAt: Long)

    @Query("DELETE FROM sync_state")
    public suspend fun clear()
}
