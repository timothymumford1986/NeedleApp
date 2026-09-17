package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.local.projection.PinnedAlbumRow
import kotlinx.coroutines.flow.Flow

/**
 * Pins: albums the user asked to keep on the device.
 *
 * A pin is intent; the bytes live in `audio_cache` with `pinned = 1`. [observePinnedAlbums] joins
 * the two so the "On device" list renders the album, its download state and what is actually on
 * disk from one query.
 */
@Dao
public interface PinDao {

    @Upsert
    public suspend fun upsert(pin: PinEntity)

    @Upsert
    public suspend fun upsertAll(pins: List<PinEntity>)

    /**
     * Pinned albums with download state and real on-device coverage, newest pin first.
     *
     * `cached_track_count` and `cached_bytes` come from `audio_cache`, not from the pin's own
     * counters: after a staleness eviction the counters can claim more than the disk holds, and the
     * green check on the artwork must follow the bytes.
     */
    @Query(
        """
        SELECT
            album.*,
            pin.pinned_at AS pinned_at,
            pin.source AS pin_source,
            pin.download_state AS download_state,
            pin.tracks_complete AS tracks_complete,
            pin.tracks_total AS tracks_total,
            pin.downloaded_bytes AS downloaded_bytes,
            pin.total_bytes AS total_bytes,
            pin.error AS pin_error,
            (
                SELECT COUNT(*) FROM audio_cache ac
                WHERE ac.release_group_mbid = pin.release_group_mbid AND ac.complete = 1
            ) AS cached_track_count,
            (
                SELECT COALESCE(SUM(ac.size_bytes), 0) FROM audio_cache ac
                WHERE ac.release_group_mbid = pin.release_group_mbid
            ) AS cached_bytes
        FROM pin
        JOIN album ON album.release_group_mbid = pin.release_group_mbid
        ORDER BY pin.pinned_at DESC
        """,
    )
    public fun observePinnedAlbums(): Flow<List<PinnedAlbumRow>>

    @Query("SELECT * FROM pin WHERE release_group_mbid = :releaseGroupMbid")
    public fun observePin(releaseGroupMbid: String): Flow<PinEntity?>

    @Query("SELECT * FROM pin WHERE release_group_mbid = :releaseGroupMbid")
    public suspend fun getPin(releaseGroupMbid: String): PinEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM pin WHERE release_group_mbid = :releaseGroupMbid)")
    public fun observeIsPinned(releaseGroupMbid: String): Flow<Boolean>

    /** Pins the downloader still owes work on, oldest pin first so the queue is fair. */
    @Query(
        """
        SELECT * FROM pin
        WHERE download_state IN (:downloadStates)
        ORDER BY pinned_at ASC
        """,
    )
    public suspend fun getPinsInState(downloadStates: List<String>): List<PinEntity>

    @Query(
        """
        SELECT * FROM pin
        WHERE download_state IN (:downloadStates)
        ORDER BY pinned_at ASC
        """,
    )
    public fun observePinsInState(downloadStates: List<String>): Flow<List<PinEntity>>

    @Query("SELECT COUNT(*) FROM pin")
    public fun observePinCount(): Flow<Int>

    @Query(
        """
        UPDATE pin
        SET download_state = :downloadState,
            tracks_complete = :tracksComplete,
            tracks_total = :tracksTotal,
            downloaded_bytes = :downloadedBytes,
            total_bytes = :totalBytes,
            error = :error,
            updated_at = :updatedAt
        WHERE release_group_mbid = :releaseGroupMbid
        """,
    )
    public suspend fun setDownloadProgress(
        releaseGroupMbid: String,
        downloadState: DownloadStateDb,
        tracksComplete: Int,
        tracksTotal: Int,
        downloadedBytes: Long?,
        totalBytes: Long?,
        error: String?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE pin SET download_state = :downloadState, updated_at = :updatedAt
        WHERE release_group_mbid = :releaseGroupMbid
        """,
    )
    public suspend fun setDownloadState(
        releaseGroupMbid: String,
        downloadState: DownloadStateDb,
        updatedAt: Long,
    )

    /**
     * Removes the pin row only.
     *
     * Unpinning does **not** delete audio: the rows in `audio_cache` are flipped to `pinned = 0` by
     * the caller and then compete in the LRU like anything else played recently. Deleting the bytes
     * here would throw away a download the user might have just been listening to.
     */
    @Query("DELETE FROM pin WHERE release_group_mbid = :releaseGroupMbid")
    public suspend fun delete(releaseGroupMbid: String)

    @Query("DELETE FROM pin")
    public suspend fun clear()
}
