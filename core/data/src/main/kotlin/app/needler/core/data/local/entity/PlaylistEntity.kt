package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Subsonic playlist, keyed on the server's playlist id (stored bare, without the `pl-` prefix).
 *
 * Playlist ids are **server-local**, not global like MBIDs, which is one half of the reason a change
 * of server identity drops the whole mirror rather than trying to re-key it.
 */
@Entity(
    tableName = "playlist",
    indices = [
        Index(value = ["name_normalised"], name = "index_playlist_name_normalised"),
    ],
)
public data class PlaylistEntity(

    /** Server playlist id, bare. For a playlist created offline this is a locally minted id. */
    @PrimaryKey
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** Lower-cased [name]; playlists are browsed alphabetically. */
    @ColumnInfo(name = "name_normalised")
    val nameNormalised: String,

    @ColumnInfo(name = "track_count")
    val trackCount: Int,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long?,

    @ColumnInfo(name = "owner")
    val owner: String?,

    @ColumnInfo(name = "is_public", defaultValue = "0")
    val isPublic: Boolean = false,

    @ColumnInfo(name = "comment")
    val comment: String?,

    @ColumnInfo(name = "cover_art_id")
    val coverArtId: String?,

    /** Epoch milliseconds, from the server's `created`. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long?,

    /** Epoch milliseconds, from the server's `changed`. Last-write-wins has no better signal. */
    @ColumnInfo(name = "changed_at")
    val changedAt: Long?,

    /**
     * True for a playlist created while offline that the server has never seen.
     *
     * REQUIREMENTS.md says offline playlist edits "queue locally and replay on reconnect", which
     * means a playlist can exist on the device before it has a server id. The write-queue replayer
     * clears this flag and re-keys the row once `createPlaylist` answers. Without the flag the UI
     * could not tell a real playlist from one that only exists locally, and a failed replay would
     * leave a permanently invisible row.
     */
    @ColumnInfo(name = "local_only", defaultValue = "0")
    val localOnly: Boolean = false,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * One entry of a playlist, in playback order.
 *
 * Keyed on (playlist, position) and pointing at a track by its **stable track key**, never by
 * `file_id`: a quality upgrade rewrites file ids, and a playlist that stored them would start
 * pointing at nothing.
 *
 * The foreign key is to `playlist` only. There is deliberately none to `track`: a delta sync that
 * temporarily removes an album must not silently empty the user's playlists.
 */
@Entity(
    tableName = "playlist_track",
    primaryKeys = ["playlist_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlist_id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlist_id", "position"], name = "index_playlist_track_order"),
        Index(
            value = ["release_group_mbid", "disc_no", "track_no"],
            name = "index_playlist_track_track_key",
        ),
    ],
)
public data class PlaylistTrackEntity(

    @ColumnInfo(name = "playlist_id")
    val playlistId: String,

    /** 0-based position in the playlist. Contiguous after any reorder. */
    @ColumnInfo(name = "position")
    val position: Int,

    @ColumnInfo(name = "release_group_mbid")
    val releaseGroupMbid: String,

    @ColumnInfo(name = "disc_no")
    val discNo: Int,

    @ColumnInfo(name = "track_no")
    val trackNo: Int,

    /**
     * The `file_id` the server used for this entry when the playlist was last read.
     *
     * Stored only so `updatePlaylist` can send back a `songId` the server recognises; it is never a
     * key and must be refreshed from `track.file_id` before use, since it may be stale.
     */
    @ColumnInfo(name = "source_file_id")
    val sourceFileId: String?,
)
