package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.data.local.projection.AlbumCacheStateRow
import app.needler.core.data.local.projection.CacheUsageRow
import app.needler.core.data.local.projection.CachedAudioSignatureRow
import app.needler.core.data.local.projection.EvictionCandidateRow
import kotlinx.coroutines.flow.Flow

/**
 * The audio cache index: one table for both offline tiers, with `pinned` deciding eviction.
 *
 * This DAO owns the three queries the storage budget depends on:
 *
 *  * [observeUsage] - usage split into pinned and cached, which screen 12 shows so the user can see
 *    what a cleanup would free;
 *  * [getEvictionCandidates] - unpinned rows by `last_played_at` ascending, which is the LRU order;
 *  * [getAlbumSignatures] / [isStale] - the staleness fingerprint comparison.
 *
 * Nothing here deletes files from disk. Row and file are removed together by
 * [app.needler.core.data.local.cache.CacheIndex], which deletes the bytes first so a crash between
 * the two leaves an orphaned file the next sweep can find, rather than a row pointing at nothing.
 */
@Dao
public interface AudioCacheDao {

    @Upsert
    public suspend fun upsert(row: AudioCacheEntity)

    @Upsert
    public suspend fun upsertAll(rows: List<AudioCacheEntity>)

    // ---------------------------------------------------------------- lookups

    @Query(
        """
        SELECT * FROM audio_cache
        WHERE release_group_mbid = :releaseGroupMbid AND disc_no = :discNo AND track_no = :trackNo
        """,
    )
    public suspend fun get(releaseGroupMbid: String, discNo: Int, trackNo: Int): AudioCacheEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM audio_cache
            WHERE release_group_mbid = :releaseGroupMbid
              AND disc_no = :discNo
              AND track_no = :trackNo
              AND complete = 1
        )
        """,
    )
    public suspend fun isOnDevice(releaseGroupMbid: String, discNo: Int, trackNo: Int): Boolean

    /** Which tracks of an album are on the device, for the per-row check on the album screen. */
    @Query(
        """
        SELECT release_group_mbid, disc_no, track_no, pinned, complete, size_bytes
        FROM audio_cache
        WHERE release_group_mbid = :releaseGroupMbid
        ORDER BY disc_no ASC, track_no ASC
        """,
    )
    public fun observeAlbumCacheState(releaseGroupMbid: String): Flow<List<AlbumCacheStateRow>>

    @Query("SELECT * FROM audio_cache WHERE release_group_mbid = :releaseGroupMbid")
    public suspend fun getAlbumRows(releaseGroupMbid: String): List<AudioCacheEntity>

    // ---------------------------------------------------------------- accounting

    /**
     * Usage split by tier, in one query so the two halves can never come from different snapshots.
     *
     * Incomplete rows are counted: a half-finished `Range` download still occupies disk, and a
     * budget that ignored it would let the cache overshoot by the size of whatever is in flight.
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN pinned = 1 THEN size_bytes ELSE 0 END), 0) AS pinned_bytes,
            COALESCE(SUM(CASE WHEN pinned = 0 THEN size_bytes ELSE 0 END), 0) AS unpinned_bytes,
            COALESCE(SUM(CASE WHEN pinned = 1 THEN 1 ELSE 0 END), 0) AS pinned_tracks,
            COALESCE(SUM(CASE WHEN pinned = 0 THEN 1 ELSE 0 END), 0) AS unpinned_tracks
        FROM audio_cache
        """,
    )
    public fun observeUsage(): Flow<CacheUsageRow>

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN pinned = 1 THEN size_bytes ELSE 0 END), 0) AS pinned_bytes,
            COALESCE(SUM(CASE WHEN pinned = 0 THEN size_bytes ELSE 0 END), 0) AS unpinned_bytes,
            COALESCE(SUM(CASE WHEN pinned = 1 THEN 1 ELSE 0 END), 0) AS pinned_tracks,
            COALESCE(SUM(CASE WHEN pinned = 0 THEN 1 ELSE 0 END), 0) AS unpinned_tracks
        FROM audio_cache
        """,
    )
    public suspend fun getUsage(): CacheUsageRow

    // ---------------------------------------------------------------- LRU eviction

    /**
     * Eviction candidates in LRU order: **unpinned only**, least recently played first.
     *
     * Pinned rows are absent from this query entirely, which is the enforcement of "Pinned content
     * is exempt from the limit and from LRU eviction" - a caller cannot accidentally evict a pin
     * because a pin is never offered.
     *
     * `last_played_at = 0` means never played and sorts first, then `downloaded_at`, then the key,
     * so the order is total and two runs over the same data evict the same rows. How far down this
     * list to cut is decided by [app.needler.core.data.local.cache.EvictionPlanner]: the running
     * total needs a window function, and SQLite only gained those in 3.25, which is newer than the
     * library shipped with API 26.
     *
     * [limit] bounds the scan. Pass a limit comfortably larger than the expected eviction, or
     * [Int.MAX_VALUE] for the whole cache.
     */
    @Query(
        """
        SELECT release_group_mbid, disc_no, track_no, file_path, size_bytes, last_played_at, downloaded_at
        FROM audio_cache
        WHERE pinned = 0
        ORDER BY last_played_at ASC, downloaded_at ASC, release_group_mbid ASC, disc_no ASC, track_no ASC
        LIMIT :limit
        """,
    )
    public suspend fun getEvictionCandidates(limit: Int): List<EvictionCandidateRow>

    /** Everything unpinned, for "Remove all from device" and for a full budget recomputation. */
    @Query(
        """
        SELECT release_group_mbid, disc_no, track_no, file_path, size_bytes, last_played_at, downloaded_at
        FROM audio_cache
        WHERE pinned = 0
        ORDER BY last_played_at ASC, downloaded_at ASC, release_group_mbid ASC, disc_no ASC, track_no ASC
        """,
    )
    public suspend fun getAllUnpinnedCandidates(): List<EvictionCandidateRow>

    @Query(
        """
        SELECT release_group_mbid, disc_no, track_no, file_path, size_bytes, last_played_at, downloaded_at
        FROM audio_cache
        ORDER BY pinned ASC, last_played_at ASC
        """,
    )
    public suspend fun getAllRowsForRemoval(): List<EvictionCandidateRow>

    // ---------------------------------------------------------------- staleness

    /**
     * The staleness fingerprint of every cached track of one album: what the server said about each
     * file when its bytes were fetched.
     *
     * Compared against fresh `getAlbum` metadata by
     * [app.needler.core.data.local.staleness.StalenessChecker]. Loading the album's rows and
     * comparing in Kotlin - rather than pushing the comparison into SQL - is deliberate: the fresh
     * side of the comparison arrives from the network as a list, and SQL has no way to join against
     * a bound list of tuples without building a temporary table per sync.
     */
    @Query(
        """
        SELECT release_group_mbid, disc_no, track_no, file_path, pinned, complete, size_bytes,
               source_file_id, source_size_bytes, source_duration_ms, source_format
        FROM audio_cache
        WHERE release_group_mbid = :releaseGroupMbid
        ORDER BY disc_no ASC, track_no ASC
        """,
    )
    public suspend fun getAlbumSignatures(releaseGroupMbid: String): List<CachedAudioSignatureRow>

    /** Every cached row's fingerprint, for a full-sync sweep. */
    @Query(
        """
        SELECT release_group_mbid, disc_no, track_no, file_path, pinned, complete, size_bytes,
               source_file_id, source_size_bytes, source_duration_ms, source_format
        FROM audio_cache
        ORDER BY release_group_mbid ASC, disc_no ASC, track_no ASC
        """,
    )
    public suspend fun getAllSignatures(): List<CachedAudioSignatureRow>

    /**
     * Single-track staleness check in SQL, for the playback path: before streaming cached bytes, ask
     * whether the handle they were fetched with still matches what the server now reports.
     *
     * A difference is only reported when **both** sides have a value, which is the same rule
     * [app.needler.core.data.local.staleness.StalenessChecker] applies: a server version that stops
     * reporting durations must not declare the whole cache stale and re-download a multi-gigabyte
     * library over whatever connection is to hand.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM audio_cache
            WHERE release_group_mbid = :releaseGroupMbid
              AND disc_no = :discNo
              AND track_no = :trackNo
              AND (
                  (source_file_id IS NOT NULL AND :fileId IS NOT NULL AND source_file_id <> :fileId)
                  OR (source_size_bytes IS NOT NULL AND :sizeBytes IS NOT NULL
                      AND source_size_bytes <> :sizeBytes)
                  OR (source_duration_ms IS NOT NULL AND :durationMs IS NOT NULL
                      AND source_duration_ms <> :durationMs)
                  OR (source_format IS NOT NULL AND :format IS NOT NULL
                      AND LOWER(TRIM(source_format)) <> LOWER(TRIM(:format)))
              )
        )
        """,
    )
    public suspend fun isStale(
        releaseGroupMbid: String,
        discNo: Int,
        trackNo: Int,
        fileId: String?,
        sizeBytes: Long?,
        durationMs: Long?,
        format: String?,
    ): Boolean

    /**
     * Cached rows of an album whose fingerprint no longer matches the mirror.
     *
     * A convenience for the sync path once `track` has been updated. It is a join against the mirror
     * rather than against fresh network data, so it must run **after** the album's tracks have been
     * written, and it cannot see a track the server has removed entirely - that case is covered by
     * [app.needler.core.data.local.staleness.StalenessChecker].
     */
    @Query(
        """
        SELECT ac.release_group_mbid, ac.disc_no, ac.track_no, ac.file_path, ac.pinned, ac.complete,
               ac.size_bytes, ac.source_file_id, ac.source_size_bytes, ac.source_duration_ms,
               ac.source_format
        FROM audio_cache ac
        JOIN track t ON t.release_group_mbid = ac.release_group_mbid
            AND t.disc_no = ac.disc_no
            AND t.track_no = ac.track_no
        WHERE ac.release_group_mbid = :releaseGroupMbid
          AND (
              (ac.source_file_id IS NOT NULL AND t.file_id IS NOT NULL
                  AND ac.source_file_id <> t.file_id)
              OR (ac.source_size_bytes IS NOT NULL AND t.size_bytes IS NOT NULL
                  AND ac.source_size_bytes <> t.size_bytes)
              OR (ac.source_duration_ms IS NOT NULL AND t.duration_ms IS NOT NULL
                  AND ac.source_duration_ms <> t.duration_ms)
              OR (ac.source_format IS NOT NULL AND t.format IS NOT NULL
                  AND LOWER(TRIM(ac.source_format)) <> LOWER(TRIM(t.format)))
          )
        """,
    )
    public suspend fun findStaleAgainstMirror(releaseGroupMbid: String): List<CachedAudioSignatureRow>

    // ---------------------------------------------------------------- mutation

    /**
     * Records a play. `last_played_at` is the LRU key, so this is the one write on the playback hot
     * path; it touches a single indexed row.
     */
    @Query(
        """
        UPDATE audio_cache
        SET last_played_at = :playedAt, play_count = play_count + 1
        WHERE release_group_mbid = :releaseGroupMbid AND disc_no = :discNo AND track_no = :trackNo
        """,
    )
    public suspend fun markPlayed(releaseGroupMbid: String, discNo: Int, trackNo: Int, playedAt: Long)

    @Query(
        """
        UPDATE audio_cache
        SET size_bytes = :sizeBytes, complete = :complete, downloaded_at = :downloadedAt
        WHERE release_group_mbid = :releaseGroupMbid AND disc_no = :discNo AND track_no = :trackNo
        """,
    )
    public suspend fun setDownloadProgress(
        releaseGroupMbid: String,
        discNo: Int,
        trackNo: Int,
        sizeBytes: Long,
        complete: Boolean,
        downloadedAt: Long?,
    )

    /** Flips the tier of every cached track of an album: what pinning and unpinning actually do. */
    @Query("UPDATE audio_cache SET pinned = :pinned WHERE release_group_mbid = :releaseGroupMbid")
    public suspend fun setAlbumPinned(releaseGroupMbid: String, pinned: Boolean)

    @Query(
        """
        DELETE FROM audio_cache
        WHERE release_group_mbid = :releaseGroupMbid AND disc_no = :discNo AND track_no = :trackNo
        """,
    )
    public suspend fun delete(releaseGroupMbid: String, discNo: Int, trackNo: Int)

    /**
     * Deletes rows by canonical track key (`<mbid>/<disc>/<track>`), so an eviction plan of many
     * rows is one statement rather than one per victim.
     */
    @Query(
        """
        DELETE FROM audio_cache
        WHERE (release_group_mbid || '/' || CAST(disc_no AS TEXT) || '/' || CAST(track_no AS TEXT))
              IN (:canonicalKeys)
        """,
    )
    public suspend fun deleteByCanonicalKeys(canonicalKeys: List<String>): Int

    @Query("DELETE FROM audio_cache WHERE release_group_mbid = :releaseGroupMbid")
    public suspend fun deleteAlbum(releaseGroupMbid: String)

    /** "Remove all from device" clears audio; the metadata mirror is never touched by it. */
    @Query("DELETE FROM audio_cache")
    public suspend fun clear()
}
