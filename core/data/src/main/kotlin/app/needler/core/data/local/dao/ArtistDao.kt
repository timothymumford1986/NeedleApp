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

    /**
     * Alphabetical, with the index-jump letter derivable from `sort_name_normalised`.
     *
     * Kept for a caller that genuinely wants every row in one emission. `DefaultLibraryRepository`
     * is not one: it serves both the whole list and a window from [observeArtistsPaged], so there is
     * one statement to reason about rather than a query and a Kotlin slice that could disagree.
     */
    @Query(
        """
        SELECT artist_mbid, name, sort_name_normalised, album_count, art_url
        FROM artist
        ORDER BY sort_name_normalised ASC
        """,
    )
    public fun observeArtists(): Flow<List<ArtistIndexRow>>

    /**
     * One window of the same alphabetical list, for a caller that renders a page rather than a screen.
     *
     * Index-ordered off `index_artist_sort_name_normalised`, so the offset is a seek rather than a
     * sort. This is what `LibraryRepository.observeArtists(limit, offset)` serves, and the reason it
     * exists: the Android Auto browse tree used to read [observeArtists] whole and slice it in Kotlin
     * on every page turn, which is the shape REQUIREMENTS.md "Performance budgets" rules out.
     */
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
     * Prefix search over artists, for a filter field that narrows an alphabetical list.
     *
     * Index-served off `index_artist_sort_name_normalised`, and only ever matches a name that
     * *begins* with what was typed. That is right for the Artists screen's own filter box, where the
     * user is walking the alphabet they can see, and wrong for a search field - see
     * [searchArtistsByName], which is what a search field must call. Pass an already-normalised
     * prefix.
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

    /**
     * Substring search over artists: what a search field must call.
     *
     * ## Why a prefix query could not do this
     *
     * Searching "wonder" returned four catalogue strangers and not "Oh Wonder", three of whose albums
     * were on the device, because [searchArtistsByPrefix] can only match a name that begins with what
     * was typed and "oh wonder" does not begin with "wonder". Nobody types an artist's first word to
     * find them. Search was patched around it by deriving artists from the albums `album_fts` had
     * matched, which fixes the common case and cannot fix the real one: an artist with **no albums in
     * the mirror** - starred, monitored, or arrived with a discography lookup - has no album row to
     * be derived from and was unfindable by any query in this DAO.
     *
     * ## Why not a third FTS table
     *
     * An `artist_fts` index would answer this in one MATCH. It was rejected. REQUIREMENTS.md
     * "Local persistence" specifies exactly two FTS4 indexes - `album_fts` over album title plus
     * artist name, and `track_fts` over track title - and that document is canonical, so a third
     * inverted index would put the schema out of step with the table it is specified by. It would
     * also cost a schema version, a hand-written migration (REQUIREMENTS.md "Secrets and migrations"
     * allows no destructive fallback), four more triggers in
     * [app.needler.core.data.local.FtsTriggers] that nothing verifies, and a third index for sync to
     * keep consistent - all to search the smallest table in the mirror. FTS also would not have
     * matched mid-word, only mid-name.
     *
     * The scan this does instead is deliberate and bounded: `artist` is one short row per artist,
     * a few thousand at REQUIREMENTS.md "Assumptions made" ("Library sizes are in the low thousands
     * of albums"), and no `LIKE '%...%'` can use an index anyway - so matching both columns costs the
     * same scan as matching one. Comfortably inside "Local search results - Under 50 ms for 10,000
     * albums".
     *
     * ## The two columns, and the escaping
     *
     * The haystack is the normalised sort name joined to the display name, because they disagree and
     * a user may type either: the server sorts Bob Dylan under "Dylan, Bob", so "bob dylan" matches
     * only the display name, while "The Beatles" normalises to "beatles" and matches only the sort
     * name. A multi-word query can straddle the join, which can only ever match that same artist's
     * own text more loosely - not a different artist.
     *
     * `%` and `_` typed by the user are escaped in SQL rather than by the caller, so there is no way
     * to call this and forget: unescaped, "50%" would quietly match every artist with "50" in the
     * name. Pass a normalised query, as for [searchArtistsByPrefix].
     */
    @Query(
        """
        SELECT artist_mbid, name, sort_name_normalised, album_count, art_url
        FROM artist
        WHERE (sort_name_normalised || ' ' || name) LIKE
              '%' || replace(replace(replace(:normalisedQuery, '\', '\\'), '%', '\%'), '_', '\_') || '%'
              ESCAPE '\'
        ORDER BY sort_name_normalised ASC
        LIMIT :limit
        """,
    )
    public suspend fun searchArtistsByName(normalisedQuery: String, limit: Int): List<ArtistIndexRow>

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
