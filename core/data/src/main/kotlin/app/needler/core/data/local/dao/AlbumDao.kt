package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.projection.LibraryTotalsRow
import kotlinx.coroutines.flow.Flow

/**
 * Albums: the read path for every library screen, the search results and the widgets.
 *
 * ## Two rules this DAO enforces by construction
 *
 * 1. **`@Upsert`, never REPLACE.** `INSERT OR REPLACE` on `album` is a delete plus an insert, which
 *    fires `ON DELETE CASCADE` on `track` - every sync would wipe the album's tracks and every
 *    external-content FTS row would churn. There is deliberately no `@Insert` method here.
 * 2. **Library filters go through `in_library`, not `state`.** SQLite cannot use one index for
 *    `state IN (...)` plus an ordering column, and the grid has to stay index-ordered to hold the
 *    "no dropped frames on a 5,000-album grid" budget. `in_library` is the denormalised predicate
 *    and is maintained by whoever writes the row.
 *
 * Paging is plain `LIMIT`/`OFFSET` rather than Paging 3, so `:core:data` needs no paging dependency
 * and the grid works identically in the Glance widgets and in Android Auto, neither of which can
 * consume a `PagingSource`. Adding a `PagingSource` variant later is additive.
 */
@Dao
public interface AlbumDao {

    @Upsert
    public suspend fun upsert(album: AlbumEntity)

    @Upsert
    public suspend fun upsertAll(albums: List<AlbumEntity>)

    // ---------------------------------------------------------------- single album

    @Query("SELECT * FROM album WHERE release_group_mbid = :releaseGroupMbid")
    public fun observeAlbum(releaseGroupMbid: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM album WHERE release_group_mbid = :releaseGroupMbid")
    public suspend fun getAlbum(releaseGroupMbid: String): AlbumEntity?

    @Query("SELECT * FROM album WHERE release_group_mbid IN (:releaseGroupMbids)")
    public suspend fun getAlbums(releaseGroupMbids: List<String>): List<AlbumEntity>

    // ---------------------------------------------------------------- the grid

    /**
     * Recently added first: the default library sort, and the "Recently added" row.
     *
     * `added_at DESC` with `title_normalised` as a stable tie-break, both served by
     * `index_album_in_library_added_at`. Albums with no `added_at` sort last rather than first.
     */
    @Query(
        """
        SELECT * FROM album
        WHERE in_library = 1
        ORDER BY added_at IS NULL, added_at DESC, title_normalised ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    public fun observeLibraryByRecentlyAdded(limit: Int, offset: Int): Flow<List<AlbumEntity>>

    /** Alphabetical by album title, index-ordered off `index_album_in_library_title`. */
    @Query(
        """
        SELECT * FROM album
        WHERE in_library = 1
        ORDER BY title_normalised ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    public fun observeLibraryAlphabetical(limit: Int, offset: Int): Flow<List<AlbumEntity>>

    /** Alphabetical by artist, then year: the third sort offered by the sort control on screen 09. */
    @Query(
        """
        SELECT * FROM album
        WHERE in_library = 1
        ORDER BY artist_normalised ASC, year IS NULL, year ASC, title_normalised ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    public fun observeLibraryByArtist(limit: Int, offset: Int): Flow<List<AlbumEntity>>

    /** The newest owned album, for the "Recently added" home-screen widget. */
    @Query(
        """
        SELECT * FROM album
        WHERE in_library = 1 AND added_at IS NOT NULL
        ORDER BY added_at DESC
        LIMIT 1
        """,
    )
    public fun observeNewestAlbum(): Flow<AlbumEntity?>

    // ---------------------------------------------------------------- artist detail

    /**
     * Artist detail ordering from REQUIREMENTS.md: "Owned albums first, then un-owned."
     *
     * `in_library DESC` puts owned first; year ascending reads as a discography. This query covers
     * only what the mirror knows - the artist's full catalogue discography arrives from
     * `GET /api/v1/artists/{mbid}/releases` and is merged above the data layer.
     */
    @Query(
        """
        SELECT * FROM album
        WHERE artist_mbid = :artistMbid
        ORDER BY in_library DESC, year IS NULL, year ASC, title_normalised ASC
        """,
    )
    public fun observeAlbumsByArtist(artistMbid: String): Flow<List<AlbumEntity>>

    @Query(
        """
        SELECT * FROM album
        WHERE artist_mbid = :artistMbid AND in_library = 1
        ORDER BY year IS NULL, year ASC, title_normalised ASC
        """,
    )
    public suspend fun getOwnedAlbumsByArtist(artistMbid: String): List<AlbumEntity>

    // ---------------------------------------------------------------- offline search

    /**
     * Offline album search over title and artist name (`album_fts`).
     *
     * Call with an FTS4 MATCH expression, not raw user text - build it with
     * [app.needler.core.data.local.FtsQuery.forPrefixSearch], which escapes quotes and appends the
     * `*` that makes search-as-you-type match a partial last word.
     *
     * Owned albums sort first, because the merge rule is that a local record wins over the
     * catalogue copy of the same release group.
     */
    @Query(
        """
        SELECT album.* FROM album
        JOIN album_fts ON album_fts.docid = album.rowid
        WHERE album_fts MATCH :matchExpression
        ORDER BY album.in_library DESC, album.title_normalised ASC
        LIMIT :limit
        """,
    )
    public suspend fun searchAlbums(matchExpression: String, limit: Int): List<AlbumEntity>

    /** Observable form, for a search field that re-queries on every keystroke. */
    @Query(
        """
        SELECT album.* FROM album
        JOIN album_fts ON album_fts.docid = album.rowid
        WHERE album_fts MATCH :matchExpression
        ORDER BY album.in_library DESC, album.title_normalised ASC
        LIMIT :limit
        """,
    )
    public fun observeAlbumSearch(matchExpression: String, limit: Int): Flow<List<AlbumEntity>>

    /** Owned-only search, which is what Android Auto voice search is restricted to. */
    @Query(
        """
        SELECT album.* FROM album
        JOIN album_fts ON album_fts.docid = album.rowid
        WHERE album_fts MATCH :matchExpression AND album.in_library = 1
        ORDER BY album.title_normalised ASC
        LIMIT :limit
        """,
    )
    public suspend fun searchOwnedAlbums(matchExpression: String, limit: Int): List<AlbumEntity>

    // ---------------------------------------------------------------- totals and state

    /**
     * The "176 albums - 42 GB" header, computed from the mirror. This is the offline fallback;
     * `sync_state.server_album_count` / `server_size_bytes` hold the authoritative answer from
     * `GET /api/v1/library/stats` when it was last reachable.
     */
    @Query(
        """
        SELECT COUNT(*) AS album_count, COALESCE(SUM(size_bytes), 0) AS size_bytes
        FROM album WHERE in_library = 1
        """,
    )
    public fun observeLibraryTotals(): Flow<LibraryTotalsRow>

    @Query("SELECT COUNT(*) FROM album WHERE in_library = 1")
    public fun observeLibraryAlbumCount(): Flow<Int>

    /**
     * Moves an album through the state machine, keeping [AlbumEntity.inLibrary] consistent with
     * [AlbumEntity.state]. The two columns must never be written separately, which is why this is
     * the only state-setter.
     */
    @Query(
        """
        UPDATE album
        SET state = :state, in_library = :inLibrary, updated_at = :updatedAt
        WHERE release_group_mbid = :releaseGroupMbid
        """,
    )
    public suspend fun setState(
        releaseGroupMbid: String,
        state: AlbumStateDb,
        inLibrary: Boolean,
        updatedAt: Long,
    )

    @Query("DELETE FROM album WHERE release_group_mbid = :releaseGroupMbid")
    public suspend fun delete(releaseGroupMbid: String)

    /**
     * Drops catalogue-only rows that nothing references any more, so a long search session does not
     * grow the mirror without bound. Owned, pinned, pulled and starred albums are kept.
     */
    @Query(
        """
        DELETE FROM album
        WHERE in_library = 0
          AND updated_at < :olderThan
          AND release_group_mbid NOT IN (SELECT release_group_mbid FROM pin)
          AND release_group_mbid NOT IN (SELECT release_group_mbid FROM pull)
          AND release_group_mbid NOT IN (
              SELECT entity_id FROM favourite WHERE entity_type = 'album'
          )
        """,
    )
    public suspend fun pruneCatalogueAlbums(olderThan: Long): Int

    @Query("DELETE FROM album")
    public suspend fun clear()
}
