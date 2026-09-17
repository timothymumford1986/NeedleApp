package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A server-side acquisition ("pull"), keyed on release-group MBID.
 *
 * Keyed on the MBID rather than the task id because the MBID is what the user sees, what the album
 * screen looks up, and what `DELETE /api/v1/requests/active/{mbid}` and
 * `POST /api/v1/requests/retry/{mbid}` take. The task id is a column, since a retried request gets a
 * new task for the same album.
 *
 * No foreign key to `album`: a pull can be placed from a catalogue search result, and while offline
 * the album row and the queued request are written together but must not depend on each other's
 * ordering.
 */
@Entity(
    tableName = "pull",
    indices = [
        // The Pulls screen buckets by status, newest first.
        Index(value = ["status", "created_at"], name = "index_pull_status_created_at"),
        Index(value = ["task_id"], name = "index_pull_task_id"),
    ],
)
public data class PullEntity(

    @PrimaryKey
    @ColumnInfo(name = "release_group_mbid")
    val releaseGroupMbid: String,

    /** `/api/v1/downloads` task id. Null while the request is still only an approval record. */
    @ColumnInfo(name = "task_id")
    val taskId: String?,

    /**
     * Status as rendered, including the two states derived client-side from [searchJobId] and
     * [candidateIndex]. Always the status the *server* returned, never one inferred from the cached
     * role: an admin may have changed the role moments earlier.
     */
    @ColumnInfo(name = "status")
    val status: PullStatusDb,

    /** 0-100 from `progress_percent`. */
    @ColumnInfo(name = "percent", defaultValue = "0")
    val percent: Int = 0,

    @ColumnInfo(name = "files_done", defaultValue = "0")
    val filesDone: Int = 0,

    @ColumnInfo(name = "files_total", defaultValue = "0")
    val filesTotal: Int = 0,

    /** `downloaded_bytes` against `total_size_bytes`, both required by the Queue screen. */
    @ColumnInfo(name = "downloaded_bytes")
    val downloadedBytes: Long?,

    @ColumnInfo(name = "total_size_bytes")
    val totalSizeBytes: Long?,

    /** Which acquisition source the server used (Soulseek, Usenet, ...), for source-aware copy. */
    @ColumnInfo(name = "source")
    val source: String?,

    /** Server-reported failure, already safe to display. */
    @ColumnInfo(name = "error")
    val error: String?,

    /**
     * Present so the client-side status derivation works: `queued` with no [searchJobId] means the
     * server is still searching (show "Searching"); `queued` with a [searchJobId] but no
     * [candidateIndex] means a manual source pick is parked (show "Needs attention on the server").
     * REQUIREMENTS.md's table does not list these two columns, but the derivation it mandates is
     * impossible without them.
     */
    @ColumnInfo(name = "search_job_id")
    val searchJobId: String?,

    @ColumnInfo(name = "candidate_index")
    val candidateIndex: Int?,

    /** True when this device placed the request, which is what "Keep pulled albums on device" keys off. */
    @ColumnInfo(name = "requested_by_this_device", defaultValue = "1")
    val requestedByThisDevice: Boolean = true,

    /** Epoch milliseconds the request was placed. Pulls are sorted newest first. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
