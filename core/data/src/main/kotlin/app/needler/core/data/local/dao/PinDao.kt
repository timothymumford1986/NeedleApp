package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.local.projection.DownloadedAlbumRow
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

    /**
     * Downloaded albums with the bytes each one occupies, largest first: the Storage screen's
     * removal list.
     *
     * Ordered by size descending because the whole point of the list is to answer "what is taking up
     * the room", and a user freeing space wants the 4 GB box set at the top, not the album they
     * happened to pin most recently. Title breaks ties so the order is stable between reads.
     *
     * Only `pinned = 1` rows are summed. An album that was unpinned still has cached bytes, but those
     * belong to the cached-while-listening tier and are cleared by its own action, not by removing a
     * download.
     */
    @Query(
        """
        SELECT
            pin.release_group_mbid AS release_group_mbid,
            album.title AS title,
            album.artist_name AS artist_name,
            pin.pinned_at AS pinned_at,
            (
                SELECT COALESCE(SUM(ac.size_bytes), 0) FROM audio_cache ac
                WHERE ac.release_group_mbid = pin.release_group_mbid AND ac.pinned = 1
            ) AS size_bytes
        FROM pin
        JOIN album ON album.release_group_mbid = pin.release_group_mbid
        ORDER BY size_bytes DESC, album.title ASC
        """,
    )
    public fun observeDownloadedAlbums(): Flow<List<DownloadedAlbumRow>>

    @Query(
        """
        SELECT
            pin.release_group_mbid AS release_group_mbid,
            album.title AS title,
            album.artist_name AS artist_name,
            pin.pinned_at AS pinned_at,
            (
                SELECT COALESCE(SUM(ac.size_bytes), 0) FROM audio_cache ac
                WHERE ac.release_group_mbid = pin.release_group_mbid AND ac.pinned = 1
            ) AS size_bytes
        FROM pin
        JOIN album ON album.release_group_mbid = pin.release_group_mbid
        ORDER BY size_bytes DESC, album.title ASC
        """,
    )
    public suspend fun getDownloadedAlbums(): List<DownloadedAlbumRow>

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
     * Removes the pin row only - the *intent* to keep the album, not its bytes.
     *
     * This statement is half of "remove from device" and must never be the whole of it. The audio
     * rows are deleted alongside it, in the same transaction, by
     * [app.needler.core.data.local.NeedlerDatabase.removeDownloadedAlbum], which collects the file
     * paths first so the caller can unlink them.
     *
     * Deleting only this row would leave the bytes on disk in the cached tier, where they free
     * nothing now and may sit until an LRU pass that never comes. With no storage limit left in the
     * app, removing a download is the user's only lever on a full device, so a removal that frees
     * nothing is a broken removal.
     */
    @Query("DELETE FROM pin WHERE release_group_mbid = :releaseGroupMbid")
    public suspend fun delete(releaseGroupMbid: String)

    @Query("DELETE FROM pin")
    public suspend fun clear()
}
