package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/*
 * Offline search index.
 *
 * REQUIREMENTS.md makes local search a hard requirement - "typing issues search3 against the local
 * metadata mirror immediately, with no network call, so library results appear on the first
 * keystroke" - with a budget of under 50 ms for 10,000 albums. That rules out LIKE '%term%' over the
 * mirror, which cannot use an index for a leading wildcard, so both indexes are FTS4.
 *
 * ## External content tables
 *
 * Both FTS tables declare `contentEntity`, which makes Room create them with FTS4's
 * `content=<table>` option. The index then stores only the inverted index and reads the column
 * values back from `album` / `track` on demand, instead of keeping a second copy of every title.
 * On a mirror of thousands of albums that is the difference between one copy of the text and two.
 *
 * The price is that SQLite does **not** maintain an external content index automatically, and Room
 * does not generate the triggers either. They are hand-written in
 * [app.needler.core.data.local.FtsTriggers] and installed by the database callback and by every
 * migration that touches the content tables. Without those triggers the index silently stops
 * matching new rows - so if offline search ever goes quiet, check the triggers first.
 *
 * `prefix = [2, 3]` builds prefix indexes for two- and three-character prefixes, which is what makes
 * a search-as-you-type query (`need*`) fast rather than a full term scan. `unicode61` folds
 * diacritics and case, so "bjork" matches "Björk".
 */

/**
 * FTS4 index over album title and artist name (REQUIREMENTS.md persistence table: "FTS4 over album
 * title and artist name").
 *
 * Search joins back to `album` on rowid:
 * `SELECT album.* FROM album JOIN album_fts ON album_fts.docid = album.rowid WHERE album_fts MATCH ?`.
 *
 * Both columns must exist on [AlbumEntity] with these exact names, which is the reason `album`
 * carries a denormalised `artist_name` column at all.
 */
@Entity(tableName = "album_fts")
@Fts4(
    contentEntity = AlbumEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    prefix = [2, 3],
)
public data class AlbumFtsEntity(

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist_name")
    val artistName: String,
)

/**
 * FTS4 index over track title (REQUIREMENTS.md persistence table: "FTS4 over track title").
 *
 * Search joins back to `track` on rowid, which then yields the stable
 * (release group, disc, track) key - never a `file_id`.
 */
@Entity(tableName = "track_fts")
@Fts4(
    contentEntity = TrackEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    prefix = [2, 3],
)
public data class TrackFtsEntity(

    @ColumnInfo(name = "title")
    val title: String,
)
