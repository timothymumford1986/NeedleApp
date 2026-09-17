package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One track of an owned album.
 *
 * ## The key is (release group, disc, track) - never `file_id`
 *
 * A Subsonic track id is `tr-<file_id>`, where `file_id` is a row id in the server's track-files
 * table. DroppedNeedle performs automatic quality upgrades by replacing files **in place**, and
 * re-imports rewrite rows too. Keying anything on `file_id` therefore means that after an upgrade
 * the app keeps serving the older, worse bytes it downloaded months ago, and nothing reports an
 * error. The failure is silent, which is why this composite primary key is not negotiable.
 *
 * The foreign key to `album` cascades, because deleting an album genuinely does mean its tracks are
 * gone. That is also why no DAO may write `album` with REPLACE - see [AlbumEntity].
 */
@Entity(
    tableName = "track",
    primaryKeys = ["release_group_mbid", "disc_no", "track_no"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["release_group_mbid"],
            childColumns = ["release_group_mbid"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Album detail, ordered by disc then track. Also the index the foreign key needs.
        Index(value = ["release_group_mbid", "disc_no", "track_no"], name = "index_track_album_order"),
        Index(value = ["recording_mbid"], name = "index_track_recording_mbid"),
        // Not a key: only a reverse lookup for "the server says file_id X has changed".
        Index(value = ["file_id"], name = "index_track_file_id"),
    ],
)
public data class TrackEntity(

    @ColumnInfo(name = "release_group_mbid")
    val releaseGroupMbid: String,

    /** 1-based disc number. Single-disc releases use 1, never 0, so the key is always comparable. */
    @ColumnInfo(name = "disc_no")
    val discNo: Int,

    /** 1-based track number within the disc. */
    @ColumnInfo(name = "track_no")
    val trackNo: Int,

    @ColumnInfo(name = "title")
    val title: String,

    /** Lower-cased [title]. `track_fts` indexes [title]; this column orders and prefix-matches. */
    @ColumnInfo(name = "title_normalised")
    val titleNormalised: String,

    /** Track artist, which differs from the album artist on compilations. */
    @ColumnInfo(name = "artist_name")
    val artistName: String?,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long?,

    /**
     * Recording MBID where the server provides one. Part of the cache identity per REQUIREMENTS.md
     * "Track identity is not stable", and the key taken by
     * `POST /api/v1/tracks/{recording_mbid}/request` for a single-track request.
     */
    @ColumnInfo(name = "recording_mbid")
    val recordingMbid: String?,

    /**
     * **Fetch handle and staleness signal only - never a key.**
     *
     * The current server-side track-file row id behind `tr-<file_id>`. It has exactly two
     * legitimate uses:
     *
     *  1. building `stream?id=`, `download?id=` and `scrobble?id=` URLs;
     *  2. comparing against `audio_cache.source_file_id` on sync to notice that the server has
     *     replaced the file (REQUIREMENTS.md "Invalidating upgraded files").
     *
     * Any other use is a bug. The identity of a track is
     * (`release_group_mbid`, `disc_no`, `track_no`). Do not add a lookup, map, cache entry,
     * playlist row or UI key on this column.
     */
    @ColumnInfo(name = "file_id")
    val fileId: String?,

    /** Size of the server's current file in bytes. One of the four staleness signals. */
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long?,

    /** Container/codec token as the server reports it. One of the four staleness signals. */
    @ColumnInfo(name = "format")
    val format: String?,

    @ColumnInfo(name = "bitrate_kbps")
    val bitrateKbps: Int?,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
