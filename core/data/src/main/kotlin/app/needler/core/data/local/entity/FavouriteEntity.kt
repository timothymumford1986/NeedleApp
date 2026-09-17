package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * A starred item, keyed on (entity type, entity id) exactly as REQUIREMENTS.md specifies.
 *
 * Needler offers **binary favourites only**: `setRating` on this server validates its input and
 * returns success without persisting anything, so star ratings would silently do nothing.
 *
 * ## Why the three resolved key columns exist
 *
 * For an album or artist, `entity_id` is the MBID and joins directly. A track's identity is a
 * *tuple* (release group, disc, track), so storing it in one text column would force every
 * favourites query to join on a string built at runtime - `favourite.entity_id = mbid || '/' || ...`
 * - which no index can serve. The tuple is therefore also stored in three nullable columns, which
 * are populated for [FavouriteTypeDb.TRACK] rows and null otherwise. The primary key is unchanged;
 * these are a resolved, indexable copy of it.
 */
@Entity(
    tableName = "favourite",
    primaryKeys = ["entity_type", "entity_id"],
    indices = [
        // getStarred2 orders favourites recently-starred first.
        Index(value = ["entity_type", "starred_at"], name = "index_favourite_type_starred_at"),
        Index(
            value = ["release_group_mbid", "disc_no", "track_no"],
            name = "index_favourite_track_key",
        ),
    ],
)
public data class FavouriteEntity(

    @ColumnInfo(name = "entity_type")
    val entityType: FavouriteTypeDb,

    /**
     * Release-group MBID, artist MBID, or a track's canonical key `<mbid>/<disc>/<track>`.
     * Always bare - never a Subsonic `al-`/`ar-`/`tr-` id.
     */
    @ColumnInfo(name = "entity_id")
    val entityId: String,

    /** Epoch milliseconds. Null when the server reported a star with no timestamp. */
    @ColumnInfo(name = "starred_at")
    val starredAt: Long?,

    /** Resolved track key, part 1. Null unless [entityType] is TRACK. See the class comment. */
    @ColumnInfo(name = "release_group_mbid")
    val releaseGroupMbid: String?,

    /** Resolved track key, part 2. Null unless [entityType] is TRACK. */
    @ColumnInfo(name = "disc_no")
    val discNo: Int?,

    /** Resolved track key, part 3. Null unless [entityType] is TRACK. */
    @ColumnInfo(name = "track_no")
    val trackNo: Int?,

    /**
     * True while the star/unstar is still sitting in the write queue. Lets the UI show the star the
     * user tapped without claiming the server agrees yet.
     */
    @ColumnInfo(name = "pending_sync", defaultValue = "0")
    val pendingSync: Boolean = false,
)
