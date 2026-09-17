package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.needler.core.data.local.entity.PullEntity
import app.needler.core.data.local.entity.PullStatusDb
import app.needler.core.data.local.projection.PullRow
import kotlinx.coroutines.flow.Flow

/**
 * Pulls: server-side acquisitions, keyed on release-group MBID.
 *
 * Status buckets are passed in as lists of `dbValue` strings rather than hard-coded in SQL, so the
 * definition of "active" lives in exactly one place -
 * [app.needler.core.data.local.entity.PullStatusDb.ACTIVE_DB_VALUES] - and cannot drift between the
 * Pulls screen, the nav badge and the widget.
 */
@Dao
public interface PullDao {

    @Upsert
    public suspend fun upsert(pull: PullEntity)

    @Upsert
    public suspend fun upsertAll(pulls: List<PullEntity>)

    /**
     * Active pulls, newest first, joined to whatever the mirror knows about each album.
     *
     * A LEFT JOIN: a pull placed straight from a catalogue search result can reach the queue before
     * the album row has been written, and the Pulls screen must still render it.
     */
    @Query(
        """
        SELECT
            pull.release_group_mbid AS release_group_mbid,
            pull.task_id AS task_id,
            pull.status AS status,
            pull.percent AS percent,
            pull.files_done AS files_done,
            pull.files_total AS files_total,
            pull.downloaded_bytes AS downloaded_bytes,
            pull.total_size_bytes AS total_size_bytes,
            pull.source AS source,
            pull.error AS error,
            pull.search_job_id AS search_job_id,
            pull.candidate_index AS candidate_index,
            pull.created_at AS created_at,
            album.title AS album_title,
            album.artist_name AS album_artist_name,
            album.year AS album_year,
            album.cover_art_id AS album_cover_art_id
        FROM pull
        LEFT JOIN album ON album.release_group_mbid = pull.release_group_mbid
        WHERE pull.status IN (:statuses)
        ORDER BY pull.created_at DESC
        """,
    )
    public fun observePullsInStatus(statuses: List<String>): Flow<List<PullRow>>

    @Query(
        """
        SELECT
            pull.release_group_mbid AS release_group_mbid,
            pull.task_id AS task_id,
            pull.status AS status,
            pull.percent AS percent,
            pull.files_done AS files_done,
            pull.files_total AS files_total,
            pull.downloaded_bytes AS downloaded_bytes,
            pull.total_size_bytes AS total_size_bytes,
            pull.source AS source,
            pull.error AS error,
            pull.search_job_id AS search_job_id,
            pull.candidate_index AS candidate_index,
            pull.created_at AS created_at,
            album.title AS album_title,
            album.artist_name AS album_artist_name,
            album.year AS album_year,
            album.cover_art_id AS album_cover_art_id
        FROM pull
        LEFT JOIN album ON album.release_group_mbid = pull.release_group_mbid
        ORDER BY pull.created_at DESC
        """,
    )
    public fun observeAllPulls(): Flow<List<PullRow>>

    /**
     * The single pull the Pulls home-screen widget shows: the most advanced active one.
     *
     * Highest percentage rather than newest, because a widget showing 0% while another album is at
     * 90% reads as nothing happening.
     */
    @Query(
        """
        SELECT
            pull.release_group_mbid AS release_group_mbid,
            pull.task_id AS task_id,
            pull.status AS status,
            pull.percent AS percent,
            pull.files_done AS files_done,
            pull.files_total AS files_total,
            pull.downloaded_bytes AS downloaded_bytes,
            pull.total_size_bytes AS total_size_bytes,
            pull.source AS source,
            pull.error AS error,
            pull.search_job_id AS search_job_id,
            pull.candidate_index AS candidate_index,
            pull.created_at AS created_at,
            album.title AS album_title,
            album.artist_name AS album_artist_name,
            album.year AS album_year,
            album.cover_art_id AS album_cover_art_id
        FROM pull
        LEFT JOIN album ON album.release_group_mbid = pull.release_group_mbid
        WHERE pull.status IN (:statuses)
        ORDER BY pull.percent DESC, pull.created_at DESC
        LIMIT 1
        """,
    )
    public fun observeLeadingPull(statuses: List<String>): Flow<PullRow?>

    @Query("SELECT * FROM pull WHERE release_group_mbid = :releaseGroupMbid")
    public fun observePull(releaseGroupMbid: String): Flow<PullEntity?>

    @Query("SELECT * FROM pull WHERE release_group_mbid = :releaseGroupMbid")
    public suspend fun getPull(releaseGroupMbid: String): PullEntity?

    @Query("SELECT * FROM pull WHERE task_id = :taskId")
    public suspend fun getPullByTaskId(taskId: String): PullEntity?

    /**
     * The nav badge count - "the reliable channel", since notifications are best-effort. Counts
     * active pulls; unseen completions are added above the data layer.
     */
    @Query("SELECT COUNT(*) FROM pull WHERE status IN (:statuses)")
    public fun observePullCount(statuses: List<String>): Flow<Int>

    @Query(
        """
        UPDATE pull
        SET status = :status,
            percent = :percent,
            files_done = :filesDone,
            files_total = :filesTotal,
            downloaded_bytes = :downloadedBytes,
            total_size_bytes = :totalSizeBytes,
            error = :error,
            search_job_id = :searchJobId,
            candidate_index = :candidateIndex,
            updated_at = :updatedAt
        WHERE release_group_mbid = :releaseGroupMbid
        """,
    )
    public suspend fun updateProgress(
        releaseGroupMbid: String,
        status: PullStatusDb,
        percent: Int,
        filesDone: Int,
        filesTotal: Int,
        downloadedBytes: Long?,
        totalSizeBytes: Long?,
        error: String?,
        searchJobId: String?,
        candidateIndex: Int?,
        updatedAt: Long,
    )

    @Query("DELETE FROM pull WHERE release_group_mbid = :releaseGroupMbid")
    public suspend fun delete(releaseGroupMbid: String)

    /** Prunes finished pulls so the Completed bucket does not grow without bound. */
    @Query("DELETE FROM pull WHERE status IN (:statuses) AND updated_at < :olderThan")
    public suspend fun deleteFinishedBefore(statuses: List<String>, olderThan: Long): Int

    @Query("DELETE FROM pull")
    public suspend fun clear()
}
