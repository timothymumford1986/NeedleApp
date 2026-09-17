package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One album, owned or not, keyed on the **release-group MBID**.
 *
 * This is the most load-bearing key in the app: DroppedNeedle's Subsonic album id is
 * `al-<release-group-mbid>` and `POST /api/v1/requests/new` takes the same MBID, so one row
 * describes both an album in the library and an album that exists only in the MusicBrainz
 * catalogue. The MBID is stored bare, without the `al-` prefix.
 *
 * Deliberately **no foreign key to `artist`**. A catalogue-only album can name an artist the mirror
 * has never seen, and a constraint here would reject the very row the merged search list needs.
 *
 * ## Never write this table with OnConflictStrategy.REPLACE
 *
 * INSERT OR REPLACE is a delete followed by an insert, so it fires ON DELETE CASCADE on every child
 * table and an album's tracks would vanish on every sync. The DAOs use @Upsert for that reason.
 */
@Entity(
    tableName = "album",
    indices = [
        // The album grid: "in the library, newest first" and "in the library, A-Z". Composite, so the
        // filter and the ordering both come out of one index and a 5,000-album grid never sorts.
        Index(value = ["in_library", "added_at"], name = "index_album_in_library_added_at"),
        Index(value = ["in_library", "title_normalised"], name = "index_album_in_library_title"),
        Index(value = ["in_library", "artist_normalised", "year"], name = "index_album_in_library_artist"),
        // Artist detail: owned albums first, then un-owned, oldest release first within each group.
        Index(value = ["artist_mbid", "in_library", "year"], name = "index_album_artist_mbid"),
        Index(value = ["state"], name = "index_album_state"),
    ],
)
public data class AlbumEntity(

    /** Release-group MBID, bare. The join key both server lanes agree on. */
    @PrimaryKey
    @ColumnInfo(name = "release_group_mbid")
    val releaseGroupMbid: String,

    /** Artist MBID, bare, or null when the catalogue record gave none. Not a foreign key; see above. */
    @ColumnInfo(name = "artist_mbid")
    val artistMbid: String?,

    /**
     * Denormalised artist name, so an album row renders with no join - and because `album_fts`
     * indexes album title *and* artist name over this table as its FTS4 content table.
     */
    @ColumnInfo(name = "artist_name")
    val artistName: String,

    @ColumnInfo(name = "title")
    val title: String,

    /** Lower-cased, article-stripped [title], written by sync. Indexed for the alphabetical grid. */
    @ColumnInfo(name = "title_normalised")
    val titleNormalised: String,

    /** Lower-cased, article-stripped [artistName]. Indexed for "sort by artist" on screens 09 and 13. */
    @ColumnInfo(name = "artist_normalised")
    val artistNormalised: String,

    @ColumnInfo(name = "year")
    val year: Int?,

    /** Null for a catalogue-only album: the server has no files for it yet. */
    @ColumnInfo(name = "track_count")
    val trackCount: Int?,

    @ColumnInfo(name = "disc_count")
    val discCount: Int?,

    /** Total playing time in milliseconds, matching the domain's Album.durationMs. */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long?,

    /**
     * Denormalised from the tracks so a list row can badge FLAC / MP3 320 without loading tracks
     * (REQUIREMENTS.md "Notes on the schema", screen 13).
     */
    @ColumnInfo(name = "format")
    val format: String?,

    @ColumnInfo(name = "bitrate_kbps")
    val bitrateKbps: Int?,

    /** Album-state discriminator. Payload for ACQUIRING/FAILED lives in `pull`, for PINNED in `pin`. */
    @ColumnInfo(name = "state")
    val state: AlbumStateDb,

    /**
     * Redundant with [state] on purpose: `state IN ('owned','pinned')` cannot be served by one index
     * together with an ordering column, and every library query filters on exactly that predicate.
     * Sync must keep the two consistent; [AlbumStateDb.isInLibrary] is the definition.
     */
    @ColumnInfo(name = "in_library")
    val inLibrary: Boolean,

    /** Epoch milliseconds the server reported as "added". Feeds Recently added and the widget. */
    @ColumnInfo(name = "added_at")
    val addedAt: Long?,

    /**
     * On-server size in bytes. Summed for the "176 albums - 42 GB" header as the offline fallback to
     * `GET /api/v1/library/stats`.
     */
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long?,

    /** Subsonic cover-art id for owned albums. Catalogue art is addressed by MBID, so needs no column. */
    @ColumnInfo(name = "cover_art_id")
    val coverArtId: String?,

    /** Comma-separated genres from `getAlbum`. Genre browse is a server call; this is the offline copy. */
    @ColumnInfo(name = "genres")
    val genres: String?,

    /**
     * `quality_snapshot_summary` from the request response: the policy the server applied to this
     * album. Shown read-only, because the request body carries no quality field - "Prefer FLAC" is a
     * server-side policy, not a per-request choice.
     */
    @ColumnInfo(name = "quality_policy_summary")
    val qualityPolicySummary: String?,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
