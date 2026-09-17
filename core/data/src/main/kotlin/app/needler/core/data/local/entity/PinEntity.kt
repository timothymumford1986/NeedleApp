package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An album the user asked to keep on the device ("Pull local"), keyed on release-group MBID.
 *
 * A pin is the user's *intent*; `audio_cache` holds the bytes that intent produced. The two are
 * separate because a pin exists the moment the user taps, before any audio has been fetched, and
 * must survive the app being killed mid-download.
 *
 * Deliberately **no foreign key to `album`**. Room writes albums with `@Upsert` precisely so a sync
 * cannot cascade rows away, but a pin must be robust even if the album row is genuinely deleted and
 * re-added by a full re-sync: losing the user's pins to a sync bug would delete gigabytes of audio
 * they asked to keep.
 */
@Entity(
    tableName = "pin",
    indices = [
        Index(value = ["download_state"], name = "index_pin_download_state"),
        Index(value = ["pinned_at"], name = "index_pin_pinned_at"),
    ],
)
public data class PinEntity(

    @PrimaryKey
    @ColumnInfo(name = "release_group_mbid")
    val releaseGroupMbid: String,

    /** Epoch milliseconds the user (or the auto-pin rule) pinned this album. */
    @ColumnInfo(name = "pinned_at")
    val pinnedAt: Long,

    /** Manual, or auto-pinned by "Keep pulled albums on device". */
    @ColumnInfo(name = "source")
    val source: PinSourceDb,

    /** How far the download has got. The green check on artwork is COMPLETE and nothing else. */
    @ColumnInfo(name = "download_state")
    val downloadState: DownloadStateDb,

    /**
     * Progress counters. REQUIREMENTS.md lists only "download state" for this table, but the
     * download-progress notification and the album screen both render "4 of 12", and per-track
     * `Range` GETs give exactly this granularity. Counting `audio_cache` rows on every frame instead
     * would put an aggregate query behind a progress bar.
     */
    @ColumnInfo(name = "tracks_complete", defaultValue = "0")
    val tracksComplete: Int = 0,

    @ColumnInfo(name = "tracks_total", defaultValue = "0")
    val tracksTotal: Int = 0,

    @ColumnInfo(name = "downloaded_bytes")
    val downloadedBytes: Long?,

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long?,

    /**
     * Last failure, already redacted for display. Distinguishes an admin-disabled library download
     * (`403` on `download`) from a transport failure, which the UI words differently.
     */
    @ColumnInfo(name = "error")
    val error: String?,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
