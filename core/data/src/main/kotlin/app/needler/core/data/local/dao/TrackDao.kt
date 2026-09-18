package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.needler.core.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Tracks of owned albums, keyed on (release group, disc, track).
 *
 * Every lookup here takes that tuple. There is intentionally no `getTrackByFileId`: the moment a
 * `file_id` becomes a lookup key, a server-side quality upgrade starts silently serving stale bytes.
 * The only `file_id` query is [findTracksWithChangedFileId], and it exists to *detect* that
 * situation rather than to identify a track.
 */
@Dao
public abstract class TrackDao {

    @Upsert
    public abstract suspend fun upsert(track: TrackEntity)

    @Upsert
    public abstract suspend fun upsertAll(tracks: List<TrackEntity>)

    /**
     * Replaces an album's tracks in one transaction, which is what an album sync does.
     *
     * Deletes the rows that no longer exist rather than clearing the table first: clearing would
     * make the album momentarily trackless for any Flow the album screen is collecting, and the
     * external-content FTS triggers would churn every row.
     */
    @Transaction
    public open suspend fun replaceAlbumTracks(releaseGroupMbid: String, tracks: List<TrackEntity>) {
        upsertAll(tracks)
        val keep: List<String> = tracks.map { it.discNo.toString() + ":" + it.trackNo.toString() }
        deleteTracksNotIn(releaseGroupMbid, keep)
    }

    /**
     * Deletes tracks of an album whose `disc:track` is absent from [keepDiscAndTrack].
     *
     * The composite key is compared as a concatenated string because SQL has no tuple-IN over a
     * bound list. The concatenation is built from integers, so it cannot be ambiguous, and the
     * statement is only ever run for one album at a time.
     */
    @Query(
        """
        DELETE FROM track
        WHERE release_group_mbid = :releaseGroupMbid
          AND (CAST(disc_no AS TEXT) || ':' || CAST(track_no AS TEXT)) NOT IN (:keepDiscAndTrack)
        """,
    )
    public abstract suspend fun deleteTracksNotIn(releaseGroupMbid: String, keepDiscAndTrack: List<String>): Int

    // ---------------------------------------------------------------- album detail

    /** Album detail ordering, fixed by REQUIREMENTS.md: disc, then track number. */
    @Query(
        """
        SELECT * FROM track
        WHERE release_group_mbid = :releaseGroupMbid
        ORDER BY disc_no ASC, track_no ASC
        """,
    )
    public abstract fun observeAlbumTracks(releaseGroupMbid: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM track
        WHERE release_group_mbid = :releaseGroupMbid
        ORDER BY disc_no ASC, track_no ASC
        """,
    )
    public abstract suspend fun getAlbumTracks(releaseGroupMbid: String): List<TrackEntity>

    @Query(
        """
        SELECT * FROM track
        WHERE release_group_mbid = :releaseGroupMbid AND disc_no = :discNo AND track_no = :trackNo
        """,
    )
    public abstract suspend fun getTrack(releaseGroupMbid: String, discNo: Int, trackNo: Int): TrackEntity?

    @Query(
        """
        SELECT * FROM track
        WHERE release_group_mbid = :releaseGroupMbid AND disc_no = :discNo AND track_no = :trackNo
        """,
    )
    public abstract fun observeTrack(releaseGroupMbid: String, discNo: Int, trackNo: Int): Flow<TrackEntity?>

    // ---------------------------------------------------------------- offline search

    /**
     * Offline track search over `track_fts`. Pass an expression from
     * [app.needler.core.data.local.FtsQuery], never raw user text.
     */
    @Query(
        """
        SELECT track.* FROM track
        JOIN track_fts ON track_fts.docid = track.rowid
        WHERE track_fts MATCH :matchExpression
        ORDER BY track.title_normalised ASC
        LIMIT :limit
        """,
    )
    public abstract suspend fun searchTracks(matchExpression: String, limit: Int): List<TrackEntity>

    @Query(
        """
        SELECT track.* FROM track
        JOIN track_fts ON track_fts.docid = track.rowid
        WHERE track_fts MATCH :matchExpression
        ORDER BY track.title_normalised ASC
        LIMIT :limit
        """,
    )
    public abstract fun observeTrackSearch(matchExpression: String, limit: Int): Flow<List<TrackEntity>>

    // ---------------------------------------------------------------- staleness support

    /**
     * Tracks of an album whose current `file_id` differs from the one the cache recorded.
     *
     * A convenience for the sync path: the authoritative staleness comparison is
     * [app.needler.core.data.local.staleness.StalenessChecker], which also compares size, duration
     * and format. This query alone would miss an in-place replacement that kept the same row id.
     */
    @Query(
        """
        SELECT track.* FROM track
        JOIN audio_cache ON audio_cache.release_group_mbid = track.release_group_mbid
            AND audio_cache.disc_no = track.disc_no
            AND audio_cache.track_no = track.track_no
        WHERE track.release_group_mbid = :releaseGroupMbid
          AND audio_cache.source_file_id IS NOT NULL
          AND track.file_id IS NOT NULL
          AND audio_cache.source_file_id <> track.file_id
        """,
    )
    public abstract suspend fun findTracksWithChangedFileId(releaseGroupMbid: String): List<TrackEntity>

    /**
     * Tracks of every owned album carrying one genre.
     *
     * `track` has no genre column - genres are denormalised onto the album, which is where the
     * design pack shows them - so the bucket is a join. [genrePattern] must come from
     * `GenreCodec.likePattern`, which brackets the term with the column's delimiters so `rock` does
     * not also match `rockabilly`.
     */
    @Query(
        """
        SELECT track.* FROM track
        JOIN album ON album.release_group_mbid = track.release_group_mbid
        WHERE album.in_library = 1 AND album.genres LIKE :genrePattern
        ORDER BY album.artist_normalised ASC, album.title_normalised ASC,
                 track.disc_no ASC, track.track_no ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    public abstract fun observeTracksByGenre(
        genrePattern: String,
        limit: Int,
        offset: Int,
    ): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM track
        WHERE (release_group_mbid || '/' || CAST(disc_no AS TEXT) || '/' || CAST(track_no AS TEXT))
              IN (:canonicalKeys)
        """,
    )
    public abstract suspend fun getTracksByCanonicalKeys(canonicalKeys: List<String>): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM track")
    public abstract fun observeTrackCount(): Flow<Int>

    @Query("DELETE FROM track WHERE release_group_mbid = :releaseGroupMbid")
    public abstract suspend fun deleteAlbumTracks(releaseGroupMbid: String)

    @Query("DELETE FROM track")
    public abstract suspend fun clear()
}
