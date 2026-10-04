package app.needler.core.network.v1.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/downloads` → `DownloadListResponse`.
 *
 * Paging is blind: there is no `total` or `total_pages`.
 */
@Serializable
public data class DownloadListDto(
    @SerialName("items") val items: List<DownloadTaskDto> = emptyList(),
    @SerialName("page") val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
)

/**
 * One acquisition task. `GET /api/v1/downloads` and `GET /api/v1/downloads/{task_id}`.
 *
 * [id] is a **string**. All `*_at` fields are epoch **seconds as floats**, unlike the
 * `/requests` lane which uses ISO strings.
 *
 * [status] is one of `queued`, `downloading`, `processing`, `completed`, `partial`, `failed`,
 * `cancelled`. (`retrying` and `awaiting_review` exist but only on the per-task SSE stream, which
 * v1 does not use.) Two states are derived client-side exactly as the web UI derives them:
 *  * `queued` with no [searchJobId] → **Searching**;
 *  * `queued` with a [searchJobId] but no [candidateIndex] → **Needs attention** (spoken in full as
 *    "Needs attention on the server").
 *
 * Cancel is only offered while searching, queued or downloading; retry only on `failed`,
 * `cancelled` and `partial`.
 */
@Serializable
public data class DownloadTaskDto(
    @SerialName("id") val id: String = "",
    @SerialName("user_id") val userId: String? = null,
    /** `album` | `track`. */
    @SerialName("download_type") val downloadType: String? = null,
    /** `soulseek` | `usenet` — the source-aware copy screen 05 needs. */
    @SerialName("source") val source: String? = null,
    @SerialName("release_group_mbid") val releaseGroupMbid: String = "",
    @SerialName("release_mbid") val releaseMbid: String? = null,
    @SerialName("release_track_mbid") val releaseTrackMbid: String? = null,
    @SerialName("recording_mbid") val recordingMbid: String? = null,
    /**
     * The artist as the server recorded it against this task. See [albumTitle] for why it is
     * nullable rather than defaulting to the empty string.
     */
    @SerialName("artist_name") val artistName: String? = null,
    /**
     * The album's title as the server recorded it against this task.
     *
     * **Nullable, and a blank string means exactly what absent means.** This field is frequently
     * missing on this lane, and it used to default to `""`, which made "the server sent nothing"
     * indistinguishable from "the server sent an empty title". That is how the Pulls screen came to
     * draw 34 blank rows: the emptiness was stored in the `album` mirror as though it were a fact,
     * and a stored blank is sticky in a way an absence is not. Modelled as `String?` so a reader has
     * to decide, and so the decision is visible at the call site.
     *
     * It is a **hint**, not the thing a screen renders. REQUIREMENTS.md "Identity model" makes the
     * release-group MBID the join key, and the title the Pulls screen draws is resolved from the
     * `album` mirror on that key. This value exists to fill that mirror in when it holds nothing for
     * a release group the downloads lane has just told us about — see
     * `DefaultPullRepository.refreshPulls`.
     */
    @SerialName("album_title") val albumTitle: String? = null,
    @SerialName("track_title") val trackTitle: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("status") val status: String = "",
    @SerialName("progress_percent") val progressPercent: Int = 0,
    @SerialName("total_size_bytes") val totalSizeBytes: Long? = null,
    @SerialName("downloaded_bytes") val downloadedBytes: Long = 0,
    @SerialName("files_total") val filesTotal: Int = 0,
    @SerialName("files_completed") val filesCompleted: Int = 0,
    @SerialName("files_failed") val filesFailed: Int = 0,
    @SerialName("source_username") val sourceUsername: String? = null,
    @SerialName("search_job_id") val searchJobId: String? = null,
    @SerialName("candidate_index") val candidateIndex: Int? = null,
    @SerialName("preflight_score") val preflightScore: Double? = null,
    @SerialName("final_path") val finalPath: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("retry_count") val retryCount: Int = 0,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    @SerialName("completed_at") val completedAt: Double? = null,
    @SerialName("artist_mbid") val artistMbid: String? = null,
    /** Null for tasks held for review, even when a ladder exists. */
    @SerialName("next_retry_at") val nextRetryAt: Double? = null,
    @SerialName("retry_max") val retryMax: Int = 0,
    @SerialName("retry_ladder_minutes") val retryLadderMinutes: List<Int> = emptyList(),
    @SerialName("acquisition_cleanup_state") val acquisitionCleanupState: String? = null,
    @SerialName("quality_format") val qualityFormat: String? = null,
    @SerialName("quality_bitrate") val qualityBitrate: Int? = null,
    @SerialName("quality_bit_depth") val qualityBitDepth: Int? = null,
    @SerialName("quality_snapshot_summary") val qualitySnapshotSummary: String? = null,
    @SerialName("quality_sample_rate") val qualitySampleRate: Int? = null,
    @SerialName("quality_certainty") val qualityCertainty: String? = null,
    @SerialName("quality_provenance") val qualityProvenance: String? = null,
    @SerialName("manual_quality_override") val manualQualityOverride: Boolean = false,
    @SerialName("advertised_queue_depth") val advertisedQueueDepth: Int? = null,
    @SerialName("queue_position_start") val queuePositionStart: Int? = null,
    @SerialName("queue_position_end") val queuePositionEnd: Int? = null,
    @SerialName("remote_queued") val remoteQueued: Boolean = false,
    @SerialName("attempt_number") val attemptNumber: Int = 0,
    @SerialName("attempt_total") val attemptTotal: Int = 0,
    @SerialName("has_next_source") val hasNextSource: Boolean = false,
    /** Held and quarantined items need the web UI in v1: show as a read-only notice. */
    @SerialName("held_for_review") val heldForReview: Boolean = false,
    @SerialName("wrong_product_verdict_at") val wrongProductVerdictAt: Double? = null,
    @SerialName("wrong_product_detail") val wrongProductDetail: String? = null,
)

/**
 * `GET /api/v1/downloads/activity-summary` → `DownloadActivitySummaryResponse`.
 *
 * The cheap poll used everywhere except the foregrounded Pulls screen. [revision] advances only
 * on insert, removal, owner change or a durable state change — never on progress-only writes — so
 * an unchanged revision means every downstream refresh can be skipped.
 * [landedReleaseGroupMbids] drives the "Pull finished" notification.
 */
@Serializable
public data class DownloadActivitySummaryDto(
    @SerialName("revision") val revision: Long = 0,
    @SerialName("active_count") val activeCount: Int = 0,
    @SerialName("held_count") val heldCount: Int = 0,
    @SerialName("failed_count") val failedCount: Int = 0,
    @SerialName("landed_release_group_mbids") val landedReleaseGroupMbids: List<String> = emptyList(),
)

/**
 * `POST /api/v1/downloads/{task_id}/cancel` → `CancelDownloadResponse`.
 * Refusals are HTTP errors, not `success=false`: 404 unknown task, 403 someone else's task.
 * Cancelling during `processing` is refused by the server because files are being moved.
 */
@Serializable
public data class CancelDownloadDto(
    @SerialName("success") val success: Boolean = false,
    @SerialName("status") val status: String = "cancelled",
)

/**
 * `POST /api/v1/downloads/{task_id}/retry` → `RetryDownloadResponse`.
 * [taskId] is the **new** task's id — retry creates a new task rather than reviving the old one.
 */
@Serializable
public data class RetryDownloadDto(
    @SerialName("success") val success: Boolean = false,
    @SerialName("task_id") val taskId: String = "",
)
