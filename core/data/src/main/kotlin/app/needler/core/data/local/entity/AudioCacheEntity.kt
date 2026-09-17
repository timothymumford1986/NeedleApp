package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One row of on-device audio: **both offline tiers in one table**, with [pinned] deciding eviction.
 *
 * REQUIREMENTS.md is explicit that pinned downloads are not stored twice - "One audio store serves
 * both pinned and cached tracks, with a pin flag deciding eviction. Storing pinned downloads twice
 * would double disk use for no benefit." So a track that is both pinned and recently played is one
 * row with `pinned = 1`, not two rows.
 *
 * The rules that follow from the flag:
 *  * `pinned = 1` rows are exempt from the storage budget **and** from LRU eviction.
 *  * `pinned = 0` rows are evicted by `last_played_at` ascending until usage fits the budget.
 *
 * ## Keyed on the track key, with no foreign key to `track`
 *
 * The primary key is (release group, disc, track) - the same tuple as [TrackEntity], and never
 * `file_id`. There is no foreign key to `track` on purpose: the cache has to outlive mirror churn.
 * A re-import that rewrites a track row must not delete the bytes on disk, because those bytes are
 * still perfectly good audio for that position on that record. Whether they are still *current* is
 * decided by the staleness check, not by a constraint.
 *
 * ## The `source_*` columns are the staleness fingerprint
 *
 * REQUIREMENTS.md: "On every album sync, compare each track's `file_id`, size, duration and format
 * against the cached record. Any difference means the bytes are stale." Those four facts are
 * snapshotted here **as they were when the bytes were downloaded**. They are not read back from
 * `track`, because sync overwrites `track` with the server's new values - comparing the mirror
 * against itself would always report "unchanged" and the user would silently keep the worse file.
 */
@Entity(
    tableName = "audio_cache",
    primaryKeys = ["release_group_mbid", "disc_no", "track_no"],
    indices = [
        // The eviction scan: unpinned rows, least recently played first. Covering, so the candidate
        // list comes straight out of the index.
        Index(value = ["pinned", "last_played_at"], name = "index_audio_cache_pinned_last_played"),
        // Cache usage split by tier (screen 12), and "which tracks of this album are on device".
        Index(value = ["release_group_mbid", "pinned"], name = "index_audio_cache_album_pinned"),
        Index(value = ["source_file_id"], name = "index_audio_cache_source_file_id"),
    ],
)
public data class AudioCacheEntity(

    @ColumnInfo(name = "release_group_mbid")
    val releaseGroupMbid: String,

    @ColumnInfo(name = "disc_no")
    val discNo: Int,

    @ColumnInfo(name = "track_no")
    val trackNo: Int,

    /** Recording MBID where the server provided one: the third component of the cache identity. */
    @ColumnInfo(name = "recording_mbid")
    val recordingMbid: String?,

    /** Path inside app-private internal storage. No permission needed; removed on uninstall. */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    /** Bytes actually on disk. For an interrupted `Range` download this is less than the total. */
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    /**
     * False while per-track `Range` GETs are still resuming this file. Incomplete rows still occupy
     * disk, so they count towards usage, but they are not playable offline.
     */
    @ColumnInfo(name = "complete", defaultValue = "0")
    val complete: Boolean = false,

    /**
     * True for pinned content: exempt from the budget and from eviction. This single flag is what
     * makes one table serve both tiers.
     */
    @ColumnInfo(name = "pinned", defaultValue = "0")
    val pinned: Boolean = false,

    /**
     * Epoch milliseconds of the last play, or 0 for never played. Ascending order over this column
     * *is* the LRU eviction order. Not nullable, so the ordering needs no COALESCE and the index
     * stays usable; 0 sorts first, which is correct - never-played bytes are the cheapest to lose.
     */
    @ColumnInfo(name = "last_played_at", defaultValue = "0")
    val lastPlayedAt: Long = 0L,

    @ColumnInfo(name = "play_count", defaultValue = "0")
    val playCount: Int = 0,

    /** Epoch milliseconds the download finished (or last progressed). */
    @ColumnInfo(name = "downloaded_at")
    val downloadedAt: Long?,

    /**
     * Staleness fingerprint, part 1: the `file_id` these bytes were fetched with.
     *
     * Not a key, and not to be used as one - it is stored so that a later sync can notice the server
     * has swapped the file underneath this track.
     */
    @ColumnInfo(name = "source_file_id")
    val sourceFileId: String?,

    /** Staleness fingerprint, part 2: the size the server reported at download time. */
    @ColumnInfo(name = "source_size_bytes")
    val sourceSizeBytes: Long?,

    /** Staleness fingerprint, part 3: the duration the server reported at download time. */
    @ColumnInfo(name = "source_duration_ms")
    val sourceDurationMs: Long?,

    /** Staleness fingerprint, part 4: the format the server reported at download time. */
    @ColumnInfo(name = "source_format")
    val sourceFormat: String?,

    /** Not a staleness signal by itself, but it is what the quality badge on a cached row shows. */
    @ColumnInfo(name = "source_bitrate_kbps")
    val sourceBitrateKbps: Int?,
)
