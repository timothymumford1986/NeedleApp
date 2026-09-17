package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.local.projection.ArtistIndexRow
import kotlinx.coroutines.flow.Flow

/**
 * Artists of the metadata mirror.
 *
 * Writes are `@Upsert`, never `OnConflictStrategy.REPLACE`: REPLACE is a delete plus an insert and
 * would fire cascades on child tables. Nothing currently cascades from `artist`, but the rule is
 * uniform across this package so a later foreign key cannot quietly turn every sync into a delete.
 */
@Dao
public interface ArtistDao {

    @Upsert
    public suspend fun upsert(artist: ArtistEntity)

    @Upsert
    public suspend fun upsertAll(artists: List<ArtistEntity>)

    /** Alphabetical, with the index-jump letter derivable from `sort_name_normalised`. */
    @Query(
        """
        SELECT artist_mbid, name, sort_name_normalised, album_count, art_url
        FROM artist
        ORDER BY sort_name_normalised ASC
        """,
    )
    public fun observeArtists(): Flow<List<ArtistIndexRow>>

    @Query(
        """
        SELECT artist_mbid, name, sort_name_normalised, album_count, art_url
        FROM artist
        ORDER BY sort_name_normalised ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    public fun observeArtistsPaged(limit: Int, offset: Int): Flow<List<ArtistIndexRow>>

    @Query("SELECT * FROM artist WHERE artist_mbid = :artistMbid")
    public fun observeArtist(artistMbid: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM artist WHERE artist_mbid = :artistMbid")
    public suspend fun getArtist(artistMbid: String): ArtistEntity?

    /**
     * Prefix search over artists.
     *
     * There is no `artist_fts` table: REQUIREMENTS.md's persistence table specifies FTS4 over album
     * title plus artist name and over track title, and the album index already carries artist names.
     * A prefix `LIKE` on the indexed normalised column serves the Artists screen's own filter field
     * without a third inverted index to keep in sync. Pass an already-normalised prefix.
     */
    @Query(
        """
        SELECT artist_mbid, name, sort_name_normalised, album_count, art_url
        FROM artist
        WHERE sort_name_normalised LIKE :normalisedPrefix || '%'
        ORDER BY sort_name_normalised ASC
        LIMIT :limit
        """,
    )
    public suspend fun searchArtistsByPrefix(normalisedPrefix: String, limit: Int): List<ArtistIndexRow>

    @Query("SELECT COUNT(*) FROM artist")
    public fun observeArtistCount(): Flow<Int>

    @Query("UPDATE artist SET monitored = :monitored WHERE artist_mbid = :artistMbid")
    public suspend fun setMonitored(artistMbid: String, monitored: Boolean)

    @Query("DELETE FROM artist WHERE artist_mbid = :artistMbid")
    public suspend fun delete(artistMbid: String)

    /** Used only by `clearForServerChange`. Artist MBIDs are global, but the mirror they describe is not. */
    @Query("DELETE FROM artist")
    public suspend fun clear()
}
