package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.local.entity.FavouriteEntity
import app.needler.core.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Starred items - albums, artists and tracks - ordered recently-starred first, matching
 * `getStarred2`.
 *
 * Binary favourites only. `setRating` on this server validates and returns success without
 * persisting anything, so there is no rating column and no rating query anywhere in this module.
 */
@Dao
public interface FavouriteDao {

    @Upsert
    public suspend fun upsert(favourite: FavouriteEntity)

    @Upsert
    public suspend fun upsertAll(favourites: List<FavouriteEntity>)

    @Query("SELECT * FROM favourite ORDER BY starred_at IS NULL, starred_at DESC")
    public fun observeFavourites(): Flow<List<FavouriteEntity>>

    /**
     * Starred albums, newest star first.
     *
     * An INNER JOIN on purpose: a star for an album the mirror has never seen has nothing to render,
     * and the favourite row stays in place until the next sync resolves it.
     */
    @Query(
        """
        SELECT album.* FROM favourite
        JOIN album ON album.release_group_mbid = favourite.entity_id
        WHERE favourite.entity_type = 'album'
        ORDER BY favourite.starred_at IS NULL, favourite.starred_at DESC
        """,
    )
    public fun observeStarredAlbums(): Flow<List<AlbumEntity>>

    @Query(
        """
        SELECT artist.* FROM favourite
        JOIN artist ON artist.artist_mbid = favourite.entity_id
        WHERE favourite.entity_type = 'artist'
        ORDER BY favourite.starred_at IS NULL, favourite.starred_at DESC
        """,
    )
    public fun observeStarredArtists(): Flow<List<ArtistEntity>>

    /**
     * Starred tracks, joined on the three resolved key columns rather than on the composite text of
     * `entity_id`, so the join is index-served. See [FavouriteEntity] for why both exist.
     */
    @Query(
        """
        SELECT track.* FROM favourite
        JOIN track ON track.release_group_mbid = favourite.release_group_mbid
            AND track.disc_no = favourite.disc_no
            AND track.track_no = favourite.track_no
        WHERE favourite.entity_type = 'track'
        ORDER BY favourite.starred_at IS NULL, favourite.starred_at DESC
        """,
    )
    public fun observeStarredTracks(): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM favourite
            WHERE entity_type = 'album' AND entity_id = :releaseGroupMbid
        )
        """,
    )
    public fun observeAlbumIsStarred(releaseGroupMbid: String): Flow<Boolean>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM favourite
            WHERE entity_type = 'track'
              AND release_group_mbid = :releaseGroupMbid
              AND disc_no = :discNo
              AND track_no = :trackNo
        )
        """,
    )
    public fun observeTrackIsStarred(releaseGroupMbid: String, discNo: Int, trackNo: Int): Flow<Boolean>

    /** Marks a star as awaiting replay, so the UI can show it without claiming the server agrees. */
    @Query(
        """
        UPDATE favourite SET pending_sync = :pendingSync
        WHERE entity_type = :entityType AND entity_id = :entityId
        """,
    )
    public suspend fun setPendingSync(entityType: String, entityId: String, pendingSync: Boolean)

    @Query("DELETE FROM favourite WHERE entity_type = :entityType AND entity_id = :entityId")
    public suspend fun delete(entityType: String, entityId: String)

    /**
     * Replaces the server's answer to `getStarred2` for one type. Kept per type so an unstar that has
     * not yet replayed for tracks is not wiped by an album refresh.
     */
    @Query("DELETE FROM favourite WHERE entity_type = :entityType AND pending_sync = 0")
    public suspend fun deleteSyncedOfType(entityType: String)

    @Query("DELETE FROM favourite")
    public suspend fun clear()
}
