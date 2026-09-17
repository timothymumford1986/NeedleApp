package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One artist of the metadata mirror. Keyed on the MusicBrainz artist MBID, which is global and
 * therefore survives everything except a change of server identity.
 *
 * The Artists screen is "alphabetical, with index jump", so ordering runs off [sortNameNormalised]
 * rather than [name]: an index on a pre-normalised column is what keeps the ordered scan off the
 * sort buffer on a library of thousands of artists.
 */
@Entity(
    tableName = "artist",
    indices = [
        Index(value = ["sort_name_normalised"], name = "index_artist_sort_name_normalised"),
    ],
)
public data class ArtistEntity(

    /** Artist MBID, stored bare - without Subsonic's `ar-` prefix. */
    @PrimaryKey
    @ColumnInfo(name = "artist_mbid")
    val artistMbid: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** The server's sort name. Kept because it is what the server itself would sort by. */
    @ColumnInfo(name = "sort_name")
    val sortName: String,

    /**
     * Lower-cased, leading-article-stripped [sortName]. Written by sync, never by the UI.
     *
     * SQLite cannot use an index for ORDER BY with COLLATE NOCASE unless the index itself declares
     * the collation, and Room's Index cannot declare one. Normalising on write is how the
     * alphabetical browse gets an index-ordered scan instead of a sort.
     */
    @ColumnInfo(name = "sort_name_normalised")
    val sortNameNormalised: String,

    /** Owned albums in the mirror. The catalogue discography count is not persisted: it needs a call. */
    @ColumnInfo(name = "album_count")
    val albumCount: Int,

    /** Absolute artwork URL from /api/v1. Subsonic art is addressed by id, so it needs no column. */
    @ColumnInfo(name = "art_url")
    val artUrl: String?,

    /**
     * True when the user asked to be told about this artist's future releases (the `monitor_artist`
     * flag on a request). Drives the "new release from a followed artist" notification.
     */
    @ColumnInfo(name = "monitored", defaultValue = "0")
    val monitored: Boolean = false,

    /** Epoch milliseconds when sync last wrote this row. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
