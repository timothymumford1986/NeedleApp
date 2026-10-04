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
     * [candidateIndex] means a manual source pick is parked (drawn as "Needs attention", spoken as
     * "Needs attention on the server").
     *
     * REQUIREMENTS.md's schema table **does** list both, and says why in "Notes on the schema": the
     * first draft required the two derived states while specifying a schema that stored neither,
     * "which made both states impossible to derive. They are columns." This KDoc used to say the
     * table did not list them, which stopped being true when the document was corrected and is the
     * kind of stale aside that makes a reader distrust the next one. [requestKind] below is the
     * genuinely unlisted pair, and says so.
     */
    @ColumnInfo(name = "search_job_id")
    val searchJobId: String?,

    @ColumnInfo(name = "candidate_index")
    val candidateIndex: Int?,

    /**
     * `request_kind`: `album` or `track`, as both request lanes report it.
     *
     * ## Why this column exists although REQUIREMENTS.md's schema table does not list it
     *
     * REQUIREMENTS.md "Placing a request" gives cancel and retry one endpoint each - "`DELETE
     * /api/v1/requests/active/{mbid}`", "takes `request_kind`, either `album` or `track`" - and the
     * id in the path is **not** the same id for the two kinds: a track request is "keyed on
     * recording MBID, not release group". This table is keyed on the release group for both, because
     * that is the join key REQUIREMENTS.md "Identity model" mandates and what the album screen looks
     * up. So the kind and the recording MBID have to be stored, or a cancel on a track row is sent
     * as `request_kind=album` with a release-group id the endpoint was never given.
     *
     * The alternative - refusing to offer cancel on a track row at all - was rejected because it
     * cannot be built either: deciding not to offer it requires knowing at read time that the row is
     * a track request, which is this column. The only way to reach that decision without a column is
     * to store a status the server did not report, which REQUIREMENTS.md "Placing a request"
     * forbids in so many words.
     *
     * ## Why a `String` and not a `*Db` enum
     *
     * Every other enum in this schema stores a frozen `dbValue` through
     * [app.needler.core.data.local.NeedlerTypeConverters], so that a Kotlin rename cannot become a
     * data migration. There is nothing to freeze here: the stored value *is* the server's own wire
     * token, `RequestTarget.fromServerToken` is already the one reader of it, and a
     * `RequestKindDb("album")` would be a third spelling of a string the network layer and the
     * domain already agree on. Unrecognised values read as `album`, which is what that function
     * does.
     */
    @ColumnInfo(name = "request_kind", defaultValue = "'album'")
    val requestKind: String = PullEntity.REQUEST_KIND_ALBUM,

    /**
     * The recording this row is a request for, set only when [requestKind] is `track`.
     *
     * The id `DELETE /api/v1/requests/active/{mbid}` and `POST /api/v1/requests/retry/{mbid}` take
     * for a track request. Null on every album row, and null is also what a track row written by a
     * build older than this column has - such a row cannot be cancelled from the app, and the
     * repository says so rather than cancelling the album instead.
     */
    @ColumnInfo(name = "recording_mbid")
    val recordingMbid: String? = null,

    /** True when this device placed the request, which is what "Keep pulled albums on device" keys off. */
    @ColumnInfo(name = "requested_by_this_device", defaultValue = "1")
    val requestedByThisDevice: Boolean = true,

    /** Epoch milliseconds the request was placed. Pulls are sorted newest first. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    /** True when this row is a single-track request rather than an album one. */
    public val isTrackRequest: Boolean
        get() = requestKind.trim().equals(REQUEST_KIND_TRACK, ignoreCase = true)

    public companion object {
        /**
         * The two values [requestKind] holds, as the server spells them.
         *
         * Constants rather than an enum for the reason [requestKind] gives at length, and here
         * rather than on the mapper so that the SQL default in `NeedlerMigrations.MIGRATION_2_3`,
         * the `defaultValue` on the column and the Kotlin default cannot drift to three answers.
         */
        public const val REQUEST_KIND_ALBUM: String = "album"

        public const val REQUEST_KIND_TRACK: String = "track"
    }
}
