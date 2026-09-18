package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.needler.core.data.local.entity.PlaylistEntity
import app.needler.core.data.local.entity.PlaylistTrackEntity
import app.needler.core.data.local.projection.PlaylistTrackRow
import kotlinx.coroutines.flow.Flow

/**
 * Playlists and their entries.
 *
 * Subsonic offers no revision or conflict signal, so an offline edit is replayed last-write-wins and
 * the local copy is simply overwritten by whatever the next `getPlaylist` returns. That is why
 * [replacePlaylistTracks] deletes and re-inserts the whole entry list in one transaction: positions
 * are contiguous and server-authoritative, and patching them individually would leave gaps whenever
 * a replay raced a refresh.
 */
@Dao
public abstract class PlaylistDao {

    @Upsert
    public abstract suspend fun upsert(playlist: PlaylistEntity)

    @Upsert
    public abstract suspend fun upsertAll(playlists: List<PlaylistEntity>)

    /** Alphabetical, as REQUIREMENTS.md specifies for the Playlists screen. */
    @Query("SELECT * FROM playlist ORDER BY name_normalised ASC")
    public abstract fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE playlist_id = :playlistId")
    public abstract fun observePlaylist(playlistId: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist WHERE playlist_id = :playlistId")
    public abstract suspend fun getPlaylist(playlistId: String): PlaylistEntity?

    /**
     * A playlist's entries in order, joined to the mirror for display metadata and to `audio_cache`
     * for the on-device check.
     *
     * The join to `track` is a LEFT JOIN: an entry must survive its album disappearing from the
     * mirror, showing as unavailable rather than vanishing from the user's playlist.
     */
    @Query(
        """
        SELECT
            pt.position AS position,
            pt.release_group_mbid AS release_group_mbid,
            pt.disc_no AS disc_no,
            pt.track_no AS track_no,
            t.title AS title,
            t.artist_name AS artist_name,
            t.duration_ms AS duration_ms,
            t.file_id AS file_id,
            t.format AS format,
            t.bitrate_kbps AS bitrate_kbps,
            EXISTS(
                SELECT 1 FROM audio_cache ac
                WHERE ac.release_group_mbid = pt.release_group_mbid
                  AND ac.disc_no = pt.disc_no
                  AND ac.track_no = pt.track_no
                  AND ac.complete = 1
            ) AS on_device
        FROM playlist_track pt
        LEFT JOIN track t
            ON t.release_group_mbid = pt.release_group_mbid
           AND t.disc_no = pt.disc_no
           AND t.track_no = pt.track_no
        WHERE pt.playlist_id = :playlistId
        ORDER BY pt.position ASC
        """,
    )
    public abstract fun observePlaylistTracks(playlistId: String): Flow<List<PlaylistTrackRow>>

    @Upsert
    public abstract suspend fun upsertTracks(entries: List<PlaylistTrackEntity>)

    /** Replaces a playlist's whole entry list. See the class comment for why it is not a patch. */
    @Transaction
    public open suspend fun replacePlaylistTracks(playlistId: String, entries: List<PlaylistTrackEntity>) {
        deleteTracksOf(playlistId)
        upsertTracks(entries)
    }

    @Query("DELETE FROM playlist_track WHERE playlist_id = :playlistId")
    public abstract suspend fun deleteTracksOf(playlistId: String)

    /**
     * The raw entry rows, in order.
     *
     * The observable projection above joins `track` for rendering; a reorder or an index-based
     * removal needs the rows themselves, because it has to renumber positions the join has already
     * flattened.
     */
    @Query("SELECT * FROM playlist_track WHERE playlist_id = :playlistId ORDER BY position ASC")
    public abstract suspend fun getTracksOf(playlistId: String): List<PlaylistTrackEntity>

    @Query("SELECT COUNT(*) FROM playlist_track WHERE playlist_id = :playlistId")
    public abstract suspend fun countTracks(playlistId: String): Int

    /**
     * Re-keys a playlist created offline once `createPlaylist` has answered with the server's id.
     *
     * Both statements run in one transaction because the entry rows carry the old id as a foreign
     * key; updating the parent alone would cascade the entries to the new id and updating the
     * children alone would orphan them.
     */
    @Transaction
    public open suspend fun adoptServerId(localId: String, serverId: String, updatedAt: Long) {
        updatePlaylistId(localId = localId, serverId = serverId, updatedAt = updatedAt)
        updateTrackPlaylistId(localId = localId, serverId = serverId)
    }

    @Query(
        """
        UPDATE playlist
        SET playlist_id = :serverId, local_only = 0, updated_at = :updatedAt
        WHERE playlist_id = :localId
        """,
    )
    public abstract suspend fun updatePlaylistId(localId: String, serverId: String, updatedAt: Long)

    @Query("UPDATE playlist_track SET playlist_id = :serverId WHERE playlist_id = :localId")
    public abstract suspend fun updateTrackPlaylistId(localId: String, serverId: String)

    @Query("DELETE FROM playlist WHERE playlist_id = :playlistId")
    public abstract suspend fun delete(playlistId: String)

    @Query("DELETE FROM playlist_track")
    public abstract suspend fun clearTracks()

    /** Playlist ids are server-local, so a change of server identity drops every playlist. */
    @Query("DELETE FROM playlist")
    public abstract suspend fun clear()
}
